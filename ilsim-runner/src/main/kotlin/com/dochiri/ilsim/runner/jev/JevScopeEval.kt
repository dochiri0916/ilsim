package com.dochiri.ilsim.runner.jev

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ScopeFile(val path: String, val diff: String, val inside: Boolean, val note: String = "")

@Serializable
data class ScopeCase(val stage: String, val spec: String, val files: List<ScopeFile>)

@Serializable
data class ScopeRecord(val sample: String, val path: String, val inside: Boolean, val listed: Boolean, val p: List<Double?>, val note: String)

object JevScopeEval {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    class Sample(val name: String, val stage: String, val spec: String, val files: List<Pair<ScopeFile, String>>)

    fun load(dir: Path): List<Sample> = dir.listDirectoryEntries().filter { it.resolve("case.json").exists() }.sortedBy { it.fileName.toString() }.map { root ->
        val case = json.decodeFromString<ScopeCase>(root.resolve("case.json").readText())
        Sample(root.fileName.toString(), case.stage, root.resolve(case.spec).readText(), case.files.map { it to root.resolve(it.diff).readText() })
    }

    fun run(samples: List<Sample>, jev: Jev, repeat: Int = 1): List<ScopeRecord> = samples.flatMap { s ->
        val changed = s.files.map { it.first.path }
        val rounds = (1..repeat).map { ScopeCheck.ask(jev, s.spec, s.stage, changed, s.files.associate { (f, diff) -> f.path to diff }) }
        s.files.map { (f, _) -> ScopeRecord(s.name, f.path, f.inside, ScopeCheck.listed(s.spec, f.path), rounds.map { it?.get(f.path) }, f.note) }
    }

    fun report(records: List<ScopeRecord>): String = buildString {
        val inside = records.filter { it.inside }
        val outside = records.filter { !it.inside }
        appendLine("JEV 영향 범위 판정 평가: 파일 ${records.size}개(범위 안 ${inside.size} · 범위 밖 ${outside.size}), 사례 ${records.map { it.sample }.distinct().size}개")
        appendLine("정적 목록: 범위 안 ${inside.count { it.listed }}/${inside.size}개가 문서에 적혀 JEV 없이 통과, 범위 밖인데 문서에 적혀 못 잡는 파일 ${outside.count { it.listed }}개")
        val firsts = records.mapNotNull { r -> r.p.firstOrNull()?.let { r to it } }
        appendLine("AUROC(범위 안을 높게): ${JevRiskEval.auroc(firsts.filter { it.first.inside }.map { it.second }, firsts.filter { !it.first.inside }.map { it.second })?.let { "%.2f".format(it) } ?: "-"}")
        appendLine()
        appendLine("막는 기준(범위 안 확률 이하) | 범위 안을 막음 | 범위 밖을 막음 | 애매(0.8 미만) 범위 안 | 애매 범위 밖 · 반복 중 나쁜 값")
        for (t in listOf(0.1, 0.15, 0.2, 0.25, 0.3, 0.4)) {
            val falseBlock = inside.count { r -> r.p.any { it != null && it <= t } }
            val caught = outside.count { r -> r.p.all { it != null && it <= t } }
            val unsureIn = inside.count { r -> r.p.any { it != null && it > t && it < ScopeCheck.INSIDE } }
            val unsureOut = outside.count { r -> r.p.any { it != null && it > t && it < ScopeCheck.INSIDE } }
            appendLine("≤%.2f | %d/%d | %d/%d | %d | %d".format(t, falseBlock, inside.size, caught, outside.size, unsureIn, unsureOut))
        }
        appendLine("범위 밖인데 ${ScopeCheck.INSIDE} 이상으로 통과: ${outside.count { r -> r.p.any { it != null && it >= ScopeCheck.INSIDE } }}개")
        appendLine()
        appendLine("사례 | 파일 | 정답 | 문서에 적힘 | 범위 안 확률 | 메모")
        records.forEach { r ->
            appendLine("${r.sample} | ${r.path} | ${if (r.inside) "안" else "밖"} | ${if (r.listed) "예" else "-"} | ${r.p.joinToString("/") { it?.let { v -> "%.2f".format(v) } ?: "실패" }} | ${r.note}")
        }
    }

    fun toJson(records: List<ScopeRecord>): String = json.encodeToString(records)
}
