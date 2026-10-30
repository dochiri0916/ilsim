package com.dochiri.ilsim.runner.jev

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ScopeChangeDiff(val id: String, val file: String, val outside: List<String> = emptyList(), val outsideFiles: List<String> = emptyList(), val note: String = "")

@Serializable
data class ScopeChangeCase(val stage: String, val spec: String, val diffs: List<ScopeChangeDiff>)

@Serializable
data class ScopeChangeRecord(val sample: String, val diff: String, val path: String, val inside: Boolean, val p: List<Double?>, val raw: List<Double?> = emptyList(), val edits: Boolean = false, val note: String, val changed: String)

object JevScopeChangeEval {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    class Sample(val name: String, val stage: String, val spec: String, val diffs: List<Pair<ScopeChangeDiff, String>>)

    fun load(dir: Path): List<Sample> = dir.listDirectoryEntries().filter { it.resolve("case.json").exists() }.sortedBy { it.fileName.toString() }.map { root ->
        val case = json.decodeFromString<ScopeChangeCase>(root.resolve("case.json").readText())
        Sample(root.fileName.toString(), case.stage, root.resolve(case.spec).readText(), case.diffs.map { it to root.resolve(it.file).readText() })
    }

    fun inside(d: ScopeChangeDiff, change: ScopeCheck.Change): Boolean = change.path !in d.outsideFiles && d.outside.none { it in change.changed }

    fun run(samples: List<Sample>, jev: Jev, repeat: Int = 1, fresh: Jev = jev): List<ScopeChangeRecord> = samples.flatMap { s ->
        s.diffs.flatMap { (d, text) ->
            val changes = ScopeCheck.changes(text)
            val unmatched = d.outside.filter { o -> changes.none { o in it.changed } }
            require(unmatched.isEmpty()) { "${s.name}/${d.id}: 변경 묶음에서 찾지 못한 범위 밖 표시 $unmatched" }
            val changed = ScopeCheck.files(text).keys.toList()
            val raw = (1..repeat).map { ScopeCheck.askChanges(if (it == 1) jev else fresh, s.spec, s.stage, changed, changes) }
            val rounds = raw.mapIndexed { n, p -> p?.let { ScopeCheck.recheck(if (n == 0) jev else fresh, s.spec, s.stage, changed, changes, it) } }
            changes.mapIndexed { i, c -> ScopeChangeRecord(s.name, d.id, c.path, inside(d, c), rounds.map { it?.getOrNull(i) }, raw.map { it?.getOrNull(i) }, ScopeCheck.editsExisting(c), d.note, c.changed.lines().take(4).joinToString(" ⏎ ").take(200)) }
        }
    }

    fun report(records: List<ScopeChangeRecord>): String = buildString {
        val inside = records.filter { it.inside }
        val outside = records.filter { !it.inside }
        val diffs = records.groupBy { "${it.sample}/${it.diff}" }
        val clean = diffs.filterValues { g -> g.all { it.inside } }
        val dirty = diffs.filterValues { g -> g.any { !it.inside } }
        appendLine("JEV 변경 묶음 영향 범위 판정 평가: 묶음 ${records.size}개(범위 안 ${inside.size} · 범위 밖 ${outside.size}), 변경 ${diffs.size}개(범위 밖 묶음 있음 ${dirty.size}), 사례 ${records.map { it.sample }.distinct().size}개")
        val firsts = records.mapNotNull { r -> r.p.firstOrNull()?.let { r to it } }
        appendLine("AUROC(범위 안을 높게): ${JevRiskEval.auroc(firsts.filter { it.first.inside }.map { it.second }, firsts.filter { !it.first.inside }.map { it.second })?.let { "%.2f".format(it) } ?: "-"}")
        appendLine()
        appendLine("막는 기준 | 범위 안 묶음을 막음 | 범위 밖 묶음을 막음 | 범위 밖인데 ${ScopeCheck.INSIDE} 이상 | 정상 변경을 막음 | 정상 변경을 검토로 올림 | 범위 밖 변경을 막음 | 범위 밖 변경을 검토로만 올림 | 범위 밖 변경 조용히 통과 (모든 반복 기준)")
        for (t in listOf(0.1, 0.15, 0.2, 0.25, 0.3)) {
            val blocked = { r: ScopeChangeRecord -> r.p.any { it != null && it <= t } }
            val caught = { r: ScopeChangeRecord -> r.p.all { it != null && it <= t } }
            val unsure = { r: ScopeChangeRecord -> r.p.any { it != null && it < ScopeCheck.INSIDE } }
            val dirtyBlocked = dirty.values.count { g -> g.filter { !it.inside }.any(caught) }
            val dirtyUnsure = dirty.values.count { g -> !g.filter { !it.inside }.any(caught) && g.any(unsure) }
            appendLine("≤%.2f | %d/%d | %d/%d | %d | %d/%d | %d | %d/%d | %d | %d".format(t,
                inside.count(blocked), inside.size, outside.count(caught), outside.size, outside.count { r -> r.p.any { it != null && it >= ScopeCheck.INSIDE } },
                clean.values.count { g -> g.any(blocked) }, clean.size, clean.values.count { g -> !g.any(blocked) && g.any(unsure) },
                dirtyBlocked, dirty.size, dirtyUnsure, dirty.size - dirtyBlocked - dirtyUnsure))
        }
        val reviewed = { r: ScopeChangeRecord -> r.edits && r.p.any { it != null && it > ScopeCheck.OUTSIDE && it < ScopeCheck.INSIDE } }
        val caught = { r: ScopeChangeRecord -> r.p.all { it != null && it <= ScopeCheck.OUTSIDE } }
        val dirtyCaught = dirty.values.count { g -> g.filter { !it.inside }.any(caught) }
        val dirtyReviewed = dirty.values.count { g -> !g.filter { !it.inside }.any(caught) && g.filter { !it.inside }.any(reviewed) }
        appendLine("기존 코드를 고친 애매한 묶음(${ScopeCheck.OUTSIDE} 초과 ${ScopeCheck.INSIDE} 미만)을 검토로: 정상 변경을 검토로 올림 ${clean.values.count { g -> !g.any { r -> r.p.any { it != null && it <= ScopeCheck.OUTSIDE } } && g.any(reviewed) }}/${clean.size} | 범위 밖 변경을 막음 $dirtyCaught · 검토로 잡음 $dirtyReviewed · 조용히 통과 ${dirty.size - dirtyCaught - dirtyReviewed}/${dirty.size}")
        appendLine()
        appendLine("범위 안 확률이 ${ScopeCheck.INSIDE} 미만인 묶음과 모든 범위 밖 묶음")
        appendLine("사례/변경 | 파일 | 정답 | 범위 안 확률 | 바뀐 줄 | 메모")
        records.filter { r -> !r.inside || r.p.any { it == null || it < ScopeCheck.INSIDE } }.forEach { r ->
            appendLine("${r.sample}/${r.diff} | ${r.path.substringAfterLast('/')} | ${if (r.inside) "안" else "밖"} | ${r.p.joinToString("/") { it?.let { v -> "%.2f".format(v) } ?: "실패" }}${if (r.raw != r.p && r.raw.isNotEmpty()) " (묶어 물을 때 ${r.raw.joinToString("/") { it?.let { v -> "%.2f".format(v) } ?: "실패" }})" else ""} | ${r.changed} | ${r.note}")
        }
    }

    fun toJson(records: List<ScopeChangeRecord>): String = json.encodeToString(records)
}
