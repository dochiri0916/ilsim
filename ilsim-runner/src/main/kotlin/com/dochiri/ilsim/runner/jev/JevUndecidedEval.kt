package com.dochiri.ilsim.runner.jev

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UndecidedDecision(val question: String? = null, val text: String)

@Serializable
data class UndecidedChange(val id: String, val stage: String, val file: String, val decisions: List<UndecidedDecision> = emptyList(), val expect: Map<String, Boolean>, val note: String = "")

@Serializable
data class UndecidedCase(val spec: String, val changes: List<UndecidedChange>)

@Serializable
data class UndecidedRecord(val sample: String, val change: String, val stage: String, val question: String, val decided: Boolean, val p: List<Double?>, val note: String)

object JevUndecidedEval {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    class Sample(val name: String, val spec: String, val change: UndecidedChange, val text: String, val decisions: List<UndecidedCheck.Decision>, val questions: List<UndecidedCheck.Question>)

    fun load(dir: Path): List<Sample> = dir.listDirectoryEntries().filter { it.resolve("case.json").exists() }.sortedBy { it.fileName.toString() }.flatMap { root ->
        val case = json.decodeFromString<UndecidedCase>(root.resolve("case.json").readText())
        val spec = root.resolve(case.spec).readText()
        case.changes.map { change ->
            val decisions = change.decisions.map { UndecidedCheck.Decision(it.question, it.text) }
            val questions = UndecidedCheck.open(spec, decisions)
            require(questions.map { it.id }.toSet() == change.expect.keys) { "${root.fileName}/${change.id}: 정답 질문 ${change.expect.keys}와 남은 미결정 질문 ${questions.map { it.id }}가 다릅니다" }
            Sample(root.fileName.toString(), spec, change, root.resolve(change.file).readText(), decisions, questions)
        }
    }

    fun run(samples: List<Sample>, jev: Jev, repeat: Int = 1, fresh: Jev = jev): List<UndecidedRecord> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        try {
            val answers = samples.map { s ->
                pool.submit<List<Map<String, Double>?>> {
                    val shown = UndecidedCheck.shown(s.spec, s.change.stage, s.questions, s.decisions, s.text)
                    (1..repeat).map { UndecidedCheck.ask(if (it == 1) jev else fresh, s.spec, s.change.stage, s.questions, s.decisions, shown) }
                }
            }.map { it.get() }
            return samples.zip(answers).flatMap { (s, rounds) ->
                s.questions.map { q -> UndecidedRecord(s.name, s.change.id, s.change.stage, q.id, s.change.expect.getValue(q.id), rounds.map { it?.get(q.id) }, s.change.note) }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    fun report(records: List<UndecidedRecord>): String = buildString {
        val decided = records.filter { it.decided }
        val open = records.filter { !it.decided }
        appendLine("JEV 미결정 사항 판정 평가: 판정 ${records.size}개(추측해 정함 ${decided.size} · 정하지 않음 ${open.size}), 변경 ${records.map { it.sample to it.change }.distinct().size}개, 사례 ${records.map { it.sample }.distinct().size}개")
        val firsts = records.mapNotNull { r -> r.p.firstOrNull()?.let { r to it } }
        appendLine("AUROC(추측해 정함을 높게): ${JevRiskEval.auroc(firsts.filter { it.first.decided }.map { it.second }, firsts.filter { !it.first.decided }.map { it.second })?.let { "%.2f".format(it) } ?: "-"}")
        appendLine("호출 실패: ${records.sumOf { r -> r.p.count { it == null } }}건")
        appendLine()
        appendLine("막는 기준(정함 확률 이상) | 정하지 않았는데 막음 | 추측을 모든 반복에서 막음 | 통과(${UndecidedCheck.OPEN} 이하) 중 추측 · 애매 정하지 않음 · 애매 추측")
        for (t in listOf(0.5, 0.6, 0.7, 0.8, 0.9)) {
            val falseBlock = open.count { r -> r.p.any { it != null && it >= t } }
            val caught = decided.count { r -> r.p.all { it != null && it >= t } }
            val missed = decided.count { r -> r.p.any { it != null && it <= UndecidedCheck.OPEN } }
            val unsureOpen = open.count { r -> r.p.any { it != null && it > UndecidedCheck.OPEN && it < t } }
            val unsureDecided = decided.count { r -> r.p.any { it != null && it > UndecidedCheck.OPEN && it < t } }
            appendLine("≥%.1f | %d/%d | %d/%d | %d · %d · %d".format(t, falseBlock, open.size, caught, decided.size, missed, unsureOpen, unsureDecided))
        }
        appendLine()
        appendLine("단계별(기준 ${UndecidedCheck.DECIDED}): 단계 | 정하지 않았는데 막음 | 추측을 막음 | 추측인데 통과")
        records.groupBy { it.stage }.forEach { (stage, rs) ->
            val o = rs.filter { !it.decided }
            val d = rs.filter { it.decided }
            appendLine("$stage | ${o.count { r -> r.p.any { it != null && it >= UndecidedCheck.DECIDED } }}/${o.size} | ${d.count { r -> r.p.all { it != null && it >= UndecidedCheck.DECIDED } }}/${d.size} | ${d.count { r -> r.p.any { it != null && it <= UndecidedCheck.OPEN } }}")
        }
        appendLine()
        appendLine("사례 | 변경 | 질문 | 정답 | 정함 확률 | 메모")
        records.forEach { r ->
            val wrong = if (r.decided) r.p.any { it == null || it < UndecidedCheck.DECIDED } else r.p.any { it == null || it > UndecidedCheck.OPEN }
            appendLine("${if (wrong) "!" else " "} ${r.sample} | ${r.change} | ${r.question} | ${if (r.decided) "정함" else "안 정함"} | ${r.p.joinToString("/") { it?.let { v -> "%.2f".format(v) } ?: "실패" }} | ${r.note}")
        }
    }

    fun toJson(records: List<UndecidedRecord>): String = json.encodeToString(records)
}
