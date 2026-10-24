package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.AiUsage
import com.dochiri.ilsim.runner.UsageScope
import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.profile.StackDetector
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RiskCase(val rule: String, val label: String, val hidden: Boolean = false, val note: String = "")

@Serializable
data class Judgment(val r: Double? = null, val e: Double? = null, val verdict: String, val millis: Long = 0)

object JevRiskEval {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    const val UNLABELED = "UNLABELED"

    class Sample(val id: String, val root: Path, val case: RiskCase, val finding: Violation?)

    class Run(val mode: String, val judgments: Map<String, List<Judgment>>, val usage: List<AiUsage> = emptyList())

    data class Summary(
        val detected: Int, val decided: Int, val correct: Int, val missed: Int, val falseAlarm: Int,
        val unsure: Int, val errors: Int, val hiddenDecided: Int, val auroc: Double?, val unstable: Int,
    )

    fun load(dir: Path): List<Sample> = dir.listDirectoryEntries().filter { it.resolve("case.json").exists() }.sortedBy { it.fileName.toString() }.map { root ->
        val case = json.decodeFromString<RiskCase>(root.resolve("case.json").readText())
        val finding = RiskScanner.scan(root, StackDetector.detect(root))?.firstOrNull { it.ruleId == case.rule }
        Sample(root.fileName.toString(), root, case, finding)
    }

    private fun verdict(p: Double?): String = p?.let { RiskReviewer.screenVerdict(it) } ?: "UNSURE"

    internal fun <T> timed(action: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = action()
        return result to (System.nanoTime() - start) / 1_000_000
    }

    private fun collect(mode: String, samples: List<Sample>, repeat: Int, judge: (Sample) -> Judgment): Run {
        val usage = mutableListOf<AiUsage>()
        val judgments = UsageScope.collect(usage) {
            samples.filter { it.finding != null }.associate { s -> s.id to (1..repeat).map { judge(s) } }
        }
        return Run(mode, judgments, usage.toList())
    }

    fun batched(samples: List<Sample>, jev: Jev, repeat: Int = 1): Run = collect("JEV 묶음 질문(운영)", samples, repeat) { s ->
        val (answers, millis) = timed { JevRiskScreen.answers(jev, s.root, listOf(s.finding!!)) }
        if (answers == null) Judgment(verdict = "ERROR", millis = millis)
        else {
            val r = answers["r0"]
            val e = answers["e0"]
            Judgment(r, e, verdict(r.takeIf { (e ?: 0.0) >= JevRiskScreen.DECIDABLE }), millis)
        }
    }

    fun single(samples: List<Sample>, jev: Jev, repeat: Int = 1): Run = collect("JEV 단일 질문", samples, repeat) { s ->
        val (p, millis) = timed { JevRiskScreen.of(jev, s.root, Int.MAX_VALUE)(s.finding!!) }
        if (p == null) Judgment(verdict = "ERROR", millis = millis) else Judgment(p, null, verdict(p), millis)
    }

    fun unlabeled(roots: List<Path>, limit: Int): List<Sample> = roots.flatMap { root ->
        val findings = RiskScanner.scan(root, StackDetector.detect(root)).orEmpty().filter { it.severity == "REVIEW" }
        val byRule = findings.groupBy { it.ruleId }.values.map { it.toMutableList() }
        val picked = generateSequence { byRule.mapNotNull { it.removeFirstOrNull() }.takeIf { it.isNotEmpty() } }.flatten().take(limit).toList()
        picked.map { v -> Sample("${root.fileName}/${v.file}:${v.line} ${v.ruleId}", root, RiskCase(v.ruleId, UNLABELED), v) }
    }

    fun agent(samples: List<Sample>, mode: String, ask: (String) -> String): Run {
        val merged = Files.createTempDirectory("ilsim-jev-eval")
        try {
            val detected = samples.filter { it.finding != null }
            val moved = detected.withIndex().associate { (i, s) ->
                val v = s.finding!!
                val target = merged.resolve("s$i").resolve(v.file).also { it.parent.createDirectories() }
                s.root.resolve(v.file).takeIf { it.isRegularFile() }?.copyTo(target)
                s to v.copy(file = "s$i/${v.file}")
            }
            val judgments = mutableMapOf<String, List<Judgment>>()
            for (batch in detected.chunked(10)) {
                val (answers, millis) = timed { RiskReviewer.parse(ask(RiskReviewer.prompt(merged, batch.map { moved.getValue(it) }))).findings.associateBy { it.id } }
                batch.forEachIndexed { i, s ->
                    val v = answers[i]?.verdict?.takeIf { it in setOf("TRUE", "FALSE", "UNSURE") } ?: "ERROR"
                    judgments[s.id] = listOf(Judgment(verdict = v, millis = millis / batch.size))
                }
            }
            return Run(mode, judgments)
        } finally { merged.toFile().deleteRecursively() }
    }

    fun auroc(positives: List<Double>, negatives: List<Double>): Double? {
        if (positives.isEmpty() || negatives.isEmpty()) return null
        val wins = positives.sumOf { p -> negatives.sumOf { n -> if (p > n) 1.0 else if (p == n) 0.5 else 0.0 } }
        return wins / (positives.size * negatives.size)
    }

    fun summarize(samples: List<Sample>, run: Run): Summary {
        val judged = samples.mapNotNull { s -> run.judgments[s.id]?.let { s to it } }
        val first = judged.map { (s, j) -> s to j.first() }
        fun count(label: String, verdict: String) = first.count { (s, j) -> s.case.label == label && j.verdict == verdict }
        val scores = first.filter { (s, j) -> j.r != null && !s.case.hidden }
        return Summary(
            detected = judged.size,
            decided = first.count { it.second.verdict in setOf("TRUE", "FALSE") },
            correct = count("RISK", "TRUE") + count("SAFE", "FALSE"),
            missed = count("RISK", "FALSE"),
            falseAlarm = count("SAFE", "TRUE"),
            unsure = first.count { it.second.verdict == "UNSURE" },
            errors = first.count { it.second.verdict == "ERROR" },
            hiddenDecided = first.count { (s, j) -> s.case.hidden && j.verdict in setOf("TRUE", "FALSE") },
            auroc = auroc(scores.filter { it.first.case.label == "RISK" }.map { it.second.r!! }, scores.filter { it.first.case.label == "SAFE" }.map { it.second.r!! }),
            unstable = judged.count { (_, j) -> j.map { it.verdict }.distinct().size > 1 || j.mapNotNull { it.r }.let { r -> r.isNotEmpty() && r.max() - r.min() > 0.2 } },
        )
    }

    internal fun pct(n: Int, total: Int) = if (total == 0) "-" else "${(n * 100.0 / total).roundToInt()}%"

    internal fun percentile(values: List<Long>, q: Double): Long = values.sorted().let { if (it.isEmpty()) 0 else it[((it.size - 1) * q).roundToInt()] }

    private fun mark(s: Sample, j: Judgment): String = when {
        j.verdict == "ERROR" -> " 오류"
        s.case.label == "RISK" && j.verdict == "FALSE" -> " 놓침"
        s.case.label == "SAFE" && j.verdict == "TRUE" -> " 오경보"
        s.case.hidden && j.verdict == "FALSE" -> " 근거 밖 확정"
        else -> ""
    }

    fun report(samples: List<Sample>, runs: List<Run>): String = buildString {
        val missing = samples.filter { it.finding == null }
        val detected = samples.size - missing.size
        appendLine("JEV 위험 선별 평가: 사례 ${samples.size}건, 탐지 ${detected}건 (RISK ${samples.count { it.finding != null && it.case.label == "RISK" }} · SAFE ${samples.count { it.finding != null && it.case.label == "SAFE" }}, 근거 밖 보호 ${samples.count { it.finding != null && it.case.hidden }})")
        if (missing.isNotEmpty()) appendLine("탐지 안 됨(평가 제외): ${missing.joinToString { "${it.id}(${it.case.rule})" }}")
        appendLine()
        appendLine("방식 | 자동 확정 | 확정 정확도 | 위험 놓침 | 오경보 | 이관 | 오류 | AUROC | 불안정 | 비용 | 지연 p50/p95")
        appendLine("기준선(JEV 없음, 전부 Agent) | 0% | - | 0 | 0 | $detected | 0 | - | - | - | -")
        for (run in runs) {
            val m = summarize(samples, run)
            val millis = run.judgments.values.flatten().map { it.millis }
            val known = run.usage.mapNotNull { it.costUsd }
            val cost = when {
                run.usage.isEmpty() -> "-"
                known.size == run.usage.size -> "$${"%.4f".format(known.sum())}"
                else -> "$${"%.4f".format(known.sum())}+ (미확인 ${run.usage.size - known.size})"
            }
            appendLine("${run.mode} | ${pct(m.decided, m.detected)} | ${pct(m.correct, m.decided)} | ${m.missed} | ${m.falseAlarm} | ${m.unsure} | ${m.errors} | ${m.auroc?.let { "%.2f".format(it) } ?: "-"} | ${m.unstable} | $cost | ${percentile(millis, 0.5)}/${percentile(millis, 0.95)}ms")
        }
        appendLine()
        appendLine("사례 | 정답 | ${runs.joinToString(" | ") { it.mode }}")
        for (s in samples.filter { it.finding != null }) {
            val cells = runs.map { run ->
                run.judgments[s.id]?.first()?.let { j ->
                    listOfNotNull(j.verdict, j.r?.let { "r=%.2f".format(it) }, j.e?.let { "e=%.2f".format(it) }).joinToString(" ") + mark(s, j)
                } ?: "-"
            }
            appendLine("${s.id} | ${s.case.label}${if (s.case.hidden) "(밖)" else ""} | ${cells.joinToString(" | ")}")
        }
    }

    fun disagreements(samples: List<Sample>, runs: List<Run>): String = buildString {
        val reference = runs.last()
        fun decided(run: Run, s: Sample) = run.judgments[s.id]?.first()?.verdict?.takeIf { it == "TRUE" || it == "FALSE" }
        appendLine("실제 코드 판단 비교: 후보 ${samples.size}건, 기준 ${reference.mode}")
        appendLine("방식 | 자동 확정 | 기준과 같은 확정 | 기준과 반대")
        for (run in runs) {
            val both = samples.mapNotNull { s -> decided(run, s)?.let { it to decided(reference, s) } }
            appendLine("${run.mode} | ${pct(both.size, samples.size)} | ${both.count { it.first == it.second }} | ${both.count { it.second != null && it.first != it.second }}")
        }
        val split = samples.filter { s -> runs.mapNotNull { decided(it, s) }.distinct().size > 1 }
        appendLine()
        appendLine("판단이 갈린 후보 ${split.size}건 (직접 확인 대상)")
        split.forEach { s ->
            val cells = runs.joinToString(" | ") { run -> run.judgments[s.id]?.first()?.let { j -> j.verdict + (j.r?.let { " r=%.2f".format(it) } ?: "") } ?: "-" }
            appendLine("${s.id} | $cells")
            appendLine("    ${s.finding?.message}")
        }
    }

    fun toJson(samples: List<Sample>, runs: List<Run>): String = json.encodeToString(samples.map { s ->
        EvalRecord(s.id, s.case, s.finding != null, runs.associate { run -> run.mode to run.judgments[s.id].orEmpty() })
    })
}

@Serializable
data class EvalRecord(val id: String, val case: RiskCase, val detected: Boolean, val runs: Map<String, List<Judgment>>)
