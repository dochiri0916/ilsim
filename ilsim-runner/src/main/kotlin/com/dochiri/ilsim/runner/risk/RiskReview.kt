package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.KnowledgeFiles
import com.dochiri.ilsim.runner.ReviewRouter
import com.dochiri.ilsim.runner.DecisionScope
import com.dochiri.ilsim.runner.architecture.Violation
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RiskOpinion(val verdict: String, val reason: String, val recommendation: String = "", val reviewer: String = "")

object RiskReviewer {
    private const val BEFORE = 8
    private const val AFTER = 28
    private const val BATCH = 10
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    internal data class Answer(val id: Int, val verdict: String, val reason: String = "", val recommendation: String = "")

    @Serializable
    internal data class Answers(val findings: List<Answer> = emptyList())

    internal fun excerpt(root: Path, v: Violation): String {
        val f = root.resolve(v.file)
        if (!f.exists()) return ""
        val lines = f.readLines()
        val from = maxOf(1, v.line - BEFORE)
        val to = minOf(lines.size, v.line + AFTER)
        return (from..to).joinToString("\n") { "${if (it == v.line) ">" else " "}${it.toString().padStart(4)} ${lines[it - 1]}" }
    }

    fun fingerprint(root: Path, v: Violation): String =
        MessageDigest.getInstance("SHA-256").digest("risk-v2|${ReviewRouter.policyKey}|${v.ruleId}|${v.file}|${v.line}|${v.message}|${RiskRules.remedies[v.ruleId]}|${excerpt(root, v).lines().joinToString("") { it.drop(5).trim() }}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    internal fun prompt(root: Path, batch: List<Violation>): String = buildString {
        appendLine("아래는 정적 분석이 찾은 트랜잭션·동시성·멱등성·복원력 위험 후보와 해당 코드 발췌다(`>` 표시 줄이 지적 위치).")
        appendLine("후보마다 실제 위험인지 오탐인지 판정하고, 실제 위험이면 이 코드에 맞는 적용 방안을 제안하라.")
        appendLine("다른 곳에서 이미 보호하고 있을 수 있음(예: 호출자의 트랜잭션, 유니크 제약, 인프라 계층의 재시도)을 고려하되, 발췌에서 확인되지 않으면 UNSURE 로 답하라.")
        appendLine("JSON 만 출력하라(설명·코드펜스 금지): {\"findings\":[{\"id\":0,\"verdict\":\"TRUE|FALSE|UNSURE\",\"reason\":\"한 문장\",\"recommendation\":\"적용 방안 한두 문장(오탐이면 빈 문자열)\"}]}")
        batch.forEachIndexed { i, v ->
            appendLine()
            appendLine("### id=$i ${v.ruleId} ${v.file}:${v.line}")
            appendLine("지적: ${v.message}")
        }
        batch.groupBy { it.file }.forEach { (file, candidates) ->
            val lines = root.resolve(file).takeIf { it.exists() }?.readLines().orEmpty()
            val ranges = candidates.flatMap { v -> (maxOf(1, v.line - BEFORE)..minOf(lines.size, v.line + AFTER)).toList() }.toSortedSet()
            appendLine("### 코드 $file\n```")
            ranges.forEach { line -> appendLine("${line.toString().padStart(4)} ${lines[line - 1]}") }
            appendLine("```")
        }
    }

    internal fun parse(response: String): Answers {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start in 0 until end) { "AI 응답에서 JSON 을 찾지 못했습니다" }
        return try {
            json.decodeFromString<Answers>(response.substring(start, end + 1))
        } catch (e: Exception) {
            throw IllegalArgumentException("AI 응답을 해석하지 못했습니다: ${e.message}")
        }
    }

    const val SCREEN_LOW = 0.2
    const val SCREEN_HIGH = 0.8

    fun screenVerdict(p: Double): String? = when {
        p <= SCREEN_LOW -> "FALSE"
        p >= SCREEN_HIGH -> "TRUE"
        else -> null
    }

    fun review(
        root: Path, findings: List<Violation>, known: Map<String, RiskOpinion>, reviewer: String, force: Boolean,
        screen: (Violation) -> Double? = { null }, checkpoint: (Map<String, RiskOpinion>) -> Unit = {}, ask: (String) -> String,
    ): Map<String, RiskOpinion> {
        val result = known.toMutableMap()
        val fingerprints = findings.associateWith { fingerprint(root, it) }
        val pending = findings.filter { it.severity == "REVIEW" && (force || known[fingerprints[it]]?.let { o -> o.reviewer != reviewer && o.reviewer != "jev" } != false) }
        findings.filter { it.severity == "REVIEW" && it !in pending }.forEach { DecisionScope.record("RISK_CANDIDATE", "CACHE", "동일 지적·코드·정책의 유효한 의견", fingerprints.getValue(it)) }
        val todo = pending.filter { v ->
            val p = screen(v)?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return@filter true
            when (screenVerdict(p)) {
                "FALSE" -> { result[fingerprint(root, v)] = RiskOpinion("FALSE", "JEV 위험 확률 ${"%.2f".format(p)}: 보호 장치가 보이거나 위험이 확인되지 않음", reviewer = "jev"); DecisionScope.record("RISK_CANDIDATE", "JEV", "충분한 근거와 낮은 위험 확률 $p", fingerprints.getValue(v)); false }
                "TRUE" -> { result[fingerprint(root, v)] = RiskOpinion("TRUE", "JEV 위험 확률 ${"%.2f".format(p)}: 보호 장치 없이 위험이 확인됨", RiskRules.remedies[v.ruleId].orEmpty(), reviewer = "jev"); DecisionScope.record("RISK_CANDIDATE", "JEV", "충분한 근거와 높은 위험 확률 $p", fingerprints.getValue(v)); false }
                else -> true
            }
        }
        if (result != known) checkpoint(result.toMap())
        for (batch in todo.chunked(BATCH)) {
            batch.forEach { DecisionScope.record("RISK_CANDIDATE", "AGENT", "JEV 불확실·근거 부족·장애 또는 요청 범위 밖", fingerprints.getValue(it)) }
            val answers = parse(ask(prompt(root, batch))).findings.associateBy { it.id }
            batch.forEachIndexed { i, v ->
                val a = answers[i]?.takeIf { it.verdict in setOf("TRUE", "FALSE", "UNSURE") && it.reason.isNotBlank() } ?: return@forEachIndexed
                result[fingerprints.getValue(v)] = RiskOpinion(a.verdict, a.reason, a.recommendation, reviewer)
            }
            checkpoint(result.toMap())
        }
        return result
    }

    class Store(config: ConfigStore) {
        private val base: Path = config.location.parent.resolve("projects")
        private val jsonOut = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        private fun file(projectId: String): Path {
            require(Regex("""[A-Za-z0-9._-]+""").matches(projectId)) { "잘못된 프로젝트 id: $projectId" }
            return base.resolve(projectId).resolve("risk-review.json")
        }

        fun load(projectId: String): Map<String, RiskOpinion> =
            file(projectId).takeIf { it.exists() }?.let { jsonOut.decodeFromString<Map<String, RiskOpinion>>(it.readText()) } ?: emptyMap()

        fun save(projectId: String, opinions: Map<String, RiskOpinion>) {
            val file = file(projectId)
            KnowledgeFiles.locked(file.parent) { KnowledgeFiles.write(file, jsonOut.encodeToString(load(projectId) + opinions)) }
        }
    }
}
