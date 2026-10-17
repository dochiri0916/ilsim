package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.ConfigStore
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

/** verdict: TRUE(실제 위험) | FALSE(오탐) | UNSURE. 판단 보조 의견이며 게이트의 BLOCK 판정에는 쓰지 않는다. */
@Serializable
data class RiskOpinion(val verdict: String, val reason: String, val recommendation: String = "", val reviewer: String = "")

/**
 * 정적 탐지가 만든 REVIEW 후보를 AI가 한 번 검토한다. 후보마다 해당 코드 발췌(약 36줄)만 보내고,
 * 오탐 여부와 이 코드에 맞는 적용 방안(outbox, inbox, 서킷브레이커 등)을 받는다. 코드가 바뀌면 지문이 달라져 다시 검토한다.
 */
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
        MessageDigest.getInstance("SHA-256").digest("${v.ruleId}|${v.file}|${excerpt(root, v).lines().joinToString("") { it.drop(5).trim() }}".toByteArray())
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
            appendLine("```")
            appendLine(excerpt(root, v))
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

    /** 아직 검토하지 않은 REVIEW 후보만 AI에 보내 의견을 합쳐 돌려준다. */
    /** JEV 확률이 이 값 이하면 오탐, 이상이면 실제 위험으로 보고 Claude 에 묻지 않는다. 사이는 Claude 가 본다. */
    const val SCREEN_LOW = 0.2
    const val SCREEN_HIGH = 0.8

    /**
     * [screen] 이 있으면 먼저 후보별 위험 확률을 받아 확실한 것은 거기서 정하고(reviewer=jev), 애매한 것만 Claude 에게 보낸다.
     * [screen] 이 null 을 돌려주면(JEV 를 쓸 수 없거나 호출 상한) 그 후보는 Claude 가 본다.
     */
    fun review(
        root: Path, findings: List<Violation>, known: Map<String, RiskOpinion>, reviewer: String, force: Boolean,
        screen: (Violation) -> Double? = { null }, ask: (String) -> String,
    ): Map<String, RiskOpinion> {
        val result = known.toMutableMap()
        val pending = findings.filter { it.severity == "REVIEW" && (force || fingerprint(root, it) !in known) }
        val todo = pending.filter { v ->
            val p = screen(v) ?: return@filter true
            when {
                p <= SCREEN_LOW -> { result[fingerprint(root, v)] = RiskOpinion("FALSE", "JEV 위험 확률 ${"%.2f".format(p)}: 보호 장치가 보이거나 위험이 확인되지 않음", reviewer = "jev"); false }
                p >= SCREEN_HIGH -> { result[fingerprint(root, v)] = RiskOpinion("TRUE", "JEV 위험 확률 ${"%.2f".format(p)}: 보호 장치 없이 위험이 확인됨", RiskRules.remedies[v.ruleId].orEmpty(), reviewer = "jev"); false }
                else -> true
            }
        }
        for (batch in todo.chunked(BATCH)) {
            val answers = parse(ask(prompt(root, batch))).findings.associateBy { it.id }
            batch.forEachIndexed { i, v ->
                val a = answers[i]?.takeIf { it.verdict in setOf("TRUE", "FALSE", "UNSURE") } ?: return@forEachIndexed
                result[fingerprint(root, v)] = RiskOpinion(a.verdict, a.reason, a.recommendation, reviewer)
            }
        }
        return result
    }

    /** 위험 규칙 검토 의견도 프로젝트 id 별로 저장한다: `~/.ilsim/projects/<id>/risk-review.json` */
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
            file(projectId).also { it.parent.createDirectories() }.writeText(jsonOut.encodeToString(opinions))
        }
    }
}
