package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.AiUsage
import com.dochiri.ilsim.runner.UsageScope
import io.github.gudcks0305.jev.NoulQuestion
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Requirement(val id: String, val text: String)

@Serializable
data class RequirementVariant(val id: String, val replace: Map<String, String> = emptyMap(), val expect: Map<String, String> = emptyMap(), val note: String = "")

@Serializable
data class RequirementScenario(val requirements: List<Requirement>, val variants: List<RequirementVariant> = emptyList(), val tests: List<String> = emptyList())

@Serializable
data class RequirementRecord(val id: String, val note: String, val expected: Map<String, String>, val runs: Map<String, Map<String, List<Judgment>>>)

object JevRequirementEval {
    const val MET = "MET"
    const val MISSING = "MISSING"
    private const val HIGH = RequirementCheck.MET
    private const val LOW = RequirementCheck.MISSING
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    class Sample(val id: String, val requirements: List<Requirement>, val diff: String, val expected: Map<String, String>, val note: String)

    class Run(val mode: String, val judgments: Map<String, Map<String, List<Judgment>>>, val millis: List<Long>, val usage: List<AiUsage> = emptyList())

    data class Summary(
        val judged: Int, val decided: Int, val correct: Int, val missed: Int, val falseAlarm: Int, val unsure: Int, val errors: Int,
        val auroc: Double?, val unstable: Int, val variants: Int, val autoComplete: Int, val wrongComplete: Int, val incomplete: Int,
    )

    @Serializable
    internal data class Answer(val id: String, val verdict: String, val reason: String = "")

    @Serializable
    internal data class Answers(val requirements: List<Answer> = emptyList())

    fun load(dir: Path): List<Sample> = dir.listDirectoryEntries().filter { it.resolve("case.json").exists() }.sortedBy { it.fileName.toString() }.flatMap { root ->
        val scenario = json.decodeFromString<RequirementScenario>(root.resolve("case.json").readText())
        val built = scenario.requirements.filter { it.id !in scenario.tests }
        val hunks = built.associate { it.id to root.resolve("hunks/${it.id}.diff").readText() }
        val name = root.fileName.toString()
        val all = scenario.requirements.associate { it.id to MET }
        fun diff(parts: Map<String, String>) = scenario.requirements.mapNotNull { parts[it.id] }.joinToString("")
        listOf(Sample("$name/full", scenario.requirements, diff(hunks), all, "전부 구현")) +
            built.map { r -> Sample("$name/drop-${r.id}", scenario.requirements, diff(hunks - r.id), all + (r.id to MISSING), "${r.id} 변경 제거") } +
            scenario.variants.map { v -> Sample("$name/${v.id}", scenario.requirements, diff(hunks + v.replace.mapValues { root.resolve(it.value).readText() }), all + v.expect, v.note) }
    }

    private fun verdict(p: Double?): String = when {
        p == null -> "UNSURE"
        p >= HIGH -> MET
        p <= LOW -> MISSING
        else -> "UNSURE"
    }

    internal fun questions(requirements: List<Requirement>): Map<String, NoulQuestion> = RequirementCheck.questions(requirements)

    fun jev(samples: List<Sample>, jev: Jev, repeat: Int = 1, fresh: Jev = jev): List<Run> {
        val usage = mutableListOf<AiUsage>()
        val raw = UsageScope.collect(usage) {
            samples.associate { s ->
                s.id to (1..repeat).map { JevRiskEval.timed { (if (it == 1) jev else fresh).probabilities(RequirementCheck.state(s.requirements, s.diff), questions(s.requirements)) } }
            }
        }
        fun run(mode: String, gated: Boolean) = Run(mode, samples.associate { s ->
            s.id to s.requirements.withIndex().associate { (i, r) ->
                r.id to raw.getValue(s.id).map { (answers, millis) ->
                    if (answers == null) Judgment(verdict = "ERROR", millis = millis)
                    else {
                        val m = answers["m$i"]
                        val d = answers["d$i"]
                        Judgment(m, d, verdict(m.takeIf { !gated || (d ?: 0.0) >= JevRiskScreen.DECIDABLE }), millis)
                    }
                }
            }
        }, raw.values.flatten().map { it.second }, usage.toList())
        val operated = Run("운영(애매만 하나씩 다시 물음)", samples.associate { s ->
            val rounds = (1..repeat).map { JevRiskEval.timed { RequirementCheck.judge(if (it == 1) jev else fresh, s.requirements, s.diff) } }
            s.id to s.requirements.associate { r -> r.id to rounds.map { (result, millis) -> result?.probabilities?.get(r.id).let { m -> Judgment(m, null, if (result == null) "ERROR" else verdict(m), millis) } } }
        }, emptyList())
        return listOf(run("JEV 확정 가능할 때만", true), run("JEV 충족 확률만", false), operated)
    }

    internal fun prompt(s: Sample): String = buildString {
        appendLine("아래 요구사항과 코드 변경(diff)을 보고 요구사항마다 이 변경이 그 요구사항을 실행 코드나 설정에서 완전히 구현했는지 판정하라.")
        appendLine("테스트만 있거나 TODO·주석뿐이거나 조건·범위가 요구사항과 다르면 MISSING, 구현이 확인되면 MET, diff만으로 판단할 수 없으면 UNSURE.")
        appendLine("JSON 만 출력하라(설명·코드펜스 금지): {\"requirements\":[{\"id\":\"R1\",\"verdict\":\"MET|MISSING|UNSURE\",\"reason\":\"한 문장\"}]}")
        appendLine()
        appendLine("### 요구사항")
        s.requirements.forEach { appendLine("- ${it.id}: ${it.text}") }
        appendLine()
        appendLine("### 변경")
        appendLine("```diff")
        append(s.diff)
        appendLine("```")
    }

    internal fun parse(response: String): Map<String, String> {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start in 0 until end) { "AI 응답에서 JSON 을 찾지 못했습니다" }
        return json.decodeFromString<Answers>(response.substring(start, end + 1)).requirements
            .filter { it.verdict in setOf(MET, MISSING, "UNSURE") }.associate { it.id to it.verdict }
    }

    fun agent(samples: List<Sample>, mode: String, ask: (String) -> String): Run {
        val millis = mutableListOf<Long>()
        val judgments = samples.associate { s ->
            val (answers, ms) = JevRiskEval.timed { runCatching { parse(ask(prompt(s))) }.getOrNull() }
            millis += ms
            s.id to s.requirements.associate { r -> r.id to listOf(Judgment(verdict = answers?.get(r.id) ?: "ERROR", millis = ms)) }
        }
        return Run(mode, judgments, millis)
    }

    fun summarize(samples: List<Sample>, run: Run): Summary {
        val cells = samples.flatMap { s -> s.requirements.mapNotNull { r -> run.judgments[s.id]?.get(r.id)?.let { Triple(s, s.expected.getValue(r.id), it) } } }
        val first = cells.map { (_, expected, j) -> expected to j.first() }
        val scored = first.filter { it.second.r != null }
        val complete = samples.filter { s -> s.requirements.all { r -> run.judgments[s.id]?.get(r.id)?.first()?.verdict == MET } }
        return Summary(
            judged = cells.size,
            decided = first.count { it.second.verdict == MET || it.second.verdict == MISSING },
            correct = first.count { (e, j) -> e == j.verdict },
            missed = first.count { (e, j) -> e == MISSING && j.verdict == MET },
            falseAlarm = first.count { (e, j) -> e == MET && j.verdict == MISSING },
            unsure = first.count { it.second.verdict == "UNSURE" },
            errors = first.count { it.second.verdict == "ERROR" },
            auroc = JevRiskEval.auroc(scored.filter { it.first == MET }.map { it.second.r!! }, scored.filter { it.first == MISSING }.map { it.second.r!! }),
            unstable = cells.count { (_, _, j) -> j.map { it.verdict }.distinct().size > 1 || j.mapNotNull { it.r }.let { r -> r.isNotEmpty() && r.max() - r.min() > 0.2 } },
            variants = samples.size,
            autoComplete = complete.size,
            wrongComplete = complete.count { s -> s.expected.values.any { it == MISSING } },
            incomplete = samples.count { s -> s.expected.values.any { it == MISSING } },
        )
    }

    fun report(samples: List<Sample>, runs: List<Run>): String = buildString {
        val missing = samples.sumOf { s -> s.expected.values.count { it == MISSING } }
        val total = samples.sumOf { it.requirements.size }
        val incomplete = samples.count { s -> s.expected.values.any { it == MISSING } }
        appendLine("JEV 요구사항 충족 평가: 변형 ${samples.size}건(미완성 ${incomplete}), 요구사항 판정 ${total}건 (충족 ${total - missing} · 누락 ${missing})")
        appendLine()
        appendLine("방식 | 자동 확정 | 확정 정확도 | 누락 놓침 | 오경보 | 이관 | 오류 | AUROC | 불안정 | 자동 완료(잘못된 완료) | 비용 | 지연 p50/p95")
        appendLine("기준선(JEV 없음, 전부 Agent) | 0% | - | 0 | 0 | $total | 0 | - | - | 0(0) | - | -")
        for (run in runs) {
            val m = summarize(samples, run)
            val known = run.usage.mapNotNull { it.costUsd }
            val cost = when {
                run.usage.isEmpty() -> "-"
                known.size == run.usage.size -> "$${"%.4f".format(known.sum())}"
                else -> "$${"%.4f".format(known.sum())}+ (미확인 ${run.usage.size - known.size})"
            }
            appendLine("${run.mode} | ${JevRiskEval.pct(m.decided, m.judged)} | ${JevRiskEval.pct(m.correct, m.decided)} | ${m.missed} | ${m.falseAlarm} | ${m.unsure} | ${m.errors} | ${m.auroc?.let { "%.2f".format(it) } ?: "-"} | ${m.unstable} | ${m.autoComplete}(${m.wrongComplete}) | $cost | ${JevRiskEval.percentile(run.millis, 0.5)}/${JevRiskEval.percentile(run.millis, 0.95)}ms")
        }
        appendLine()
        appendLine("변형 | 정답 | ${runs.joinToString(" | ") { it.mode }}")
        for (s in samples) {
            val answer = s.requirements.joinToString(" ") { r -> "${r.id}${if (s.expected[r.id] == MET) "✓" else "✗"}" }
            val cells = runs.map { run ->
                s.requirements.joinToString(" ") { r ->
                    val j = run.judgments[s.id]?.get(r.id)?.first() ?: return@joinToString "${r.id} -"
                    val short = when (j.verdict) { MET -> "✓"; MISSING -> "✗"; "UNSURE" -> "?"; else -> "!" }
                    val wrong = when {
                        s.expected[r.id] == MISSING && j.verdict == MET -> "(놓침)"
                        s.expected[r.id] == MET && j.verdict == MISSING -> "(오경보)"
                        else -> ""
                    }
                    "${r.id}$short${j.r?.let { "%.2f".format(it) } ?: ""}$wrong"
                }
            }
            appendLine("${s.id} | $answer | ${cells.joinToString(" | ")}")
        }
    }

    fun toJson(samples: List<Sample>, runs: List<Run>): String = json.encodeToString(samples.map { s ->
        RequirementRecord(s.id, s.note, s.expected, runs.associate { run -> run.mode to run.judgments[s.id].orEmpty() })
    })
}
