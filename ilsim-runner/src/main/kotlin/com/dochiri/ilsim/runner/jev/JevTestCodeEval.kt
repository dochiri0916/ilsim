package com.dochiri.ilsim.runner.jev

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TestCodeDiff(val id: String, val file: String, val covered: Map<String, Boolean>, val bypass: Boolean, val note: String = "")

@Serializable
data class TestCodeCase(val testspec: String, val diffs: List<TestCodeDiff>)

@Serializable
data class TestCodeRecord(val sample: String, val diff: String, val kind: String, val item: String, val bad: Boolean, val p: List<Double?>, val note: String)

object JevTestCodeEval {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    class Sample(val name: String, val cases: List<TestCodeCheck.TestCase>, val diff: TestCodeDiff, val text: String)

    fun load(dir: Path): List<Sample> = dir.listDirectoryEntries().filter { it.resolve("case.json").exists() }.sortedBy { it.fileName.toString() }.flatMap { root ->
        val case = json.decodeFromString<TestCodeCase>(root.resolve("case.json").readText())
        val cases = TestCodeCheck.cases(root.resolve(case.testspec).readText())
        case.diffs.map { d ->
            require(cases.map { it.id }.toSet() == d.covered.keys) { "${root.fileName}/${d.id}: 정답 TC ${d.covered.keys}와 문서 TC ${cases.map { it.id }}가 다릅니다" }
            Sample(root.fileName.toString(), cases, d, root.resolve(d.file).readText())
        }
    }

    fun run(samples: List<Sample>, jev: Jev, repeat: Int = 1, fresh: Jev = jev): List<TestCodeRecord> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        try {
            val answers = samples.map { s ->
                pool.submit<List<Pair<Map<String, Double>?, Map<String, Double>?>>> {
                    val shown = Guidelines.cut(s.text, Guidelines.MAX_DIFF_WEIGHT)
                    val files = ScopeCheck.files(s.text).filterKeys { com.dochiri.ilsim.runner.Gate.isTestPath(it) }
                    (1..repeat).map { round ->
                        val client = if (round == 1) jev else fresh
                        TestCodeCheck.coverage(client, s.cases.filterNot { it.static }, shown) to TestCodeCheck.bypass(client, s.cases, files)
                    }
                }
            }.map { it.get() }
            return samples.zip(answers).flatMap { (s, rounds) ->
                s.cases.filterNot { it.static }.map { c -> TestCodeRecord(s.name, s.diff.id, "coverage", c.id, s.diff.covered.getValue(c.id).not(), rounds.map { it.first?.get(c.id)?.let { p -> 1 - p } }, s.diff.note) } +
                    TestCodeRecord(s.name, s.diff.id, "bypass", "file", s.diff.bypass, rounds.map { r -> r.second?.values?.maxOrNull() }, s.diff.note)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    fun report(records: List<TestCodeRecord>): String = buildString {
        appendLine("JEV 테스트 코드 판정 평가: TC ${records.count { it.kind == "coverage" }}개(누락 ${records.count { it.kind == "coverage" && it.bad }}), 테스트 변경 ${records.count { it.kind == "bypass" }}개(우회 ${records.count { it.kind == "bypass" && it.bad }}), 사례 ${records.map { it.sample }.distinct().size}개")
        appendLine("값은 나쁜 쪽 확률(TC는 1-시나리오 테스트 확률, 우회는 우회 확률)이고, 0.8 이상이면 막고 TC는 0.2·우회는 ${TestCodeCheck.CLEAN} 이하면 통과, 사이는 검토로 올린다")
        for (kind in listOf("coverage", "bypass")) {
            val rs = records.filter { it.kind == kind }
            val bad = rs.filter { it.bad }
            val good = rs.filter { !it.bad }
            val firsts = rs.mapNotNull { r -> r.p.firstOrNull()?.let { r to it } }
            appendLine()
            appendLine("[$kind] AUROC ${JevRiskEval.auroc(firsts.filter { it.first.bad }.map { it.second }, firsts.filter { !it.first.bad }.map { it.second })?.let { "%.2f".format(it) } ?: "-"}, 호출 실패 ${rs.sumOf { r -> r.p.count { it == null } }}")
            val pass = if (kind == "bypass") TestCodeCheck.CLEAN else 1 - TestCodeCheck.COVERED
            appendLine("나쁨 ${bad.size}: 막음 ${bad.count { r -> r.p.all { it != null && it >= 0.8 } }}, 검토로 올림 ${bad.count { r -> r.p.any { it != null && it > pass && it < 0.8 } }}, 조용히 통과 ${bad.count { r -> r.p.any { it != null && it <= pass } }}")
            appendLine("좋음 ${good.size}: 잘못 막음 ${good.count { r -> r.p.any { it != null && it >= 0.8 } }}, 검토로 올림 ${good.count { r -> r.p.any { it != null && it > pass && it < 0.8 } }}, 통과 ${good.count { r -> r.p.all { it != null && it <= pass } }}")
        }
        appendLine()
        appendLine("사례 | 변경 | 종류 | 항목 | 정답 | 나쁜 쪽 확률 | 메모")
        records.forEach { r ->
            val pass = if (r.kind == "bypass") TestCodeCheck.CLEAN else 1 - TestCodeCheck.COVERED
            val wrong = if (r.bad) r.p.any { it == null || it < 0.8 } else r.p.any { it == null || it > pass }
            appendLine("${if (wrong) "!" else " "} ${r.sample} | ${r.diff} | ${r.kind} | ${r.item} | ${if (r.bad) "나쁨" else "좋음"} | ${r.p.joinToString("/") { it?.let { v -> "%.2f".format(v) } ?: "실패" }} | ${r.note}")
        }
    }

    fun toJson(records: List<TestCodeRecord>): String = json.encodeToString(records)
}
