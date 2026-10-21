package com.dochiri.ilsim.runner.corpus

import com.dochiri.ilsim.runner.Shell
import com.dochiri.ilsim.runner.architecture.ArchitectureMiner
import com.dochiri.ilsim.runner.convention.ConventionMiner
import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CorpusEntry(val id: String, val path: String, val commit: String)

@Serializable
data class CorpusManifest(val projects: List<CorpusEntry> = emptyList())

@Serializable
data class RuleStat(val id: String, val severity: String, val compliant: Int, val total: Int)

@Serializable
data class Label(val id: String, val note: String = "")

@Serializable
data class Labels(val correct: List<Label> = emptyList(), val wrong: List<Label> = emptyList(), val expect: List<Label> = emptyList())

@Serializable
data class CorpusSnapshot(
    val id: String,
    val commit: String,
    val mode: String,
    val applicability: String,
    val slices: List<String>,
    val architecture: List<RuleStat>,
    val convention: List<RuleStat>,
)

class Corpus(private val data: Path, private val base: Path) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val manifestFile get() = data.resolve("manifest.json")
    private fun baselineFile(id: String) = data.resolve("baseline").resolve("$id.json")
    private fun labelFile(id: String) = data.resolve("labels").resolve("$id.json")

    fun labels(id: String): Labels = labelFile(id).let { if (it.exists()) json.decodeFromString(it.readText()) else Labels() }

    fun addLabels(id: String, kind: String, items: List<Label>): Labels {
        require(manifest().projects.any { it.id == id }) { "목록에 없는 프로젝트: $id" }
        val cur = labels(id)
        fun merge(old: List<Label>) = (old.filter { o -> items.none { it.id == o.id } } + items).sortedBy { it.id }
        val next = when (kind) {
            "correct" -> cur.copy(correct = merge(cur.correct), wrong = cur.wrong.filter { w -> items.none { it.id == w.id } })
            "wrong" -> cur.copy(wrong = merge(cur.wrong), correct = cur.correct.filter { c -> items.none { it.id == c.id } })
            "expect" -> cur.copy(expect = merge(cur.expect))
            else -> throw IllegalArgumentException("라벨 종류는 correct|wrong|expect 입니다: $kind")
        }
        Files.createDirectories(labelFile(id).parent)
        labelFile(id).writeText(json.encodeToString(next) + "\n")
        return next
    }

    fun manifest(): CorpusManifest = if (manifestFile.exists()) json.decodeFromString(manifestFile.readText()) else CorpusManifest()

    private fun saveManifest(m: CorpusManifest) {
        Files.createDirectories(data)
        manifestFile.writeText(json.encodeToString(m) + "\n")
    }

    private fun resolve(entry: CorpusEntry): Path = base.resolve(entry.path).normalize()

    private fun short(state: String) = state.take(7) + state.substringAfter("+", "").let { if (it.isEmpty()) "" else "+$it" }

    private fun head(dir: Path): String {
        val sha = Shell.gitOk(dir, "rev-parse", "HEAD").trim()
        val changes = Shell.gitOk(dir, "status", "--porcelain").trim()
        if (changes.isEmpty()) return sha
        val digest = MessageDigest.getInstance("SHA-256").digest((Shell.gitOk(dir, "diff", "HEAD") + changes).toByteArray())
        return "$sha+${digest.take(4).joinToString("") { "%02x".format(it) }}"
    }

    fun add(id: String, relative: String): CorpusEntry {
        val dir = base.resolve(relative).normalize()
        require(dir.resolve(".git").exists()) { "git 저장소가 아닙니다: $dir" }
        val entry = CorpusEntry(id, relative, head(dir))
        val m = manifest()
        saveManifest(m.copy(projects = m.projects.filter { it.id != id } + entry))
        return entry
    }

    fun update(ids: List<String>): List<String> {
        val m = manifest()
        val targets = select(m, ids)
        val out = mutableListOf<String>()
        val refreshed = m.projects.map { e ->
            if (e !in targets) return@map e
            val dir = resolve(e)
            val commit = head(dir)
            val snap = snapshot(e.id, dir, commit)
            Files.createDirectories(baselineFile(e.id).parent)
            baselineFile(e.id).writeText(json.encodeToString(snap) + "\n")
            out += "${e.id}: 규칙 아키텍처 ${snap.architecture.size}개 · 컨벤션 ${snap.convention.size}개 저장 (${short(commit)})"
            e.copy(commit = commit)
        }
        saveManifest(m.copy(projects = refreshed))
        return out
    }

    fun check(ids: List<String>): Pair<Boolean, List<String>> {
        val m = manifest()
        val lines = mutableListOf<String>()
        var clean = true
        for (e in select(m, ids)) {
            val dir = resolve(e)
            if (!dir.resolve(".git").exists()) { lines += "${e.id}: 건너뜀 (프로젝트 없음: $dir)"; clean = false; continue }
            val commit = head(dir)
            if (commit != e.commit) { lines += "${e.id}: 건너뜀 (기준 커밋 ${short(e.commit)} 와 다름: ${short(commit)})"; clean = false; continue }
            val file = baselineFile(e.id)
            if (!file.exists()) { lines += "${e.id}: 기준 없음 (corpus update 필요)"; clean = false; continue }
            val current = snapshot(e.id, dir, commit)
            val diff = diff(json.decodeFromString(file.readText()), current)
            val labels = labels(e.id)
            val problems = labelProblems(labels, current)
            val total = labels.correct.size + labels.wrong.size + labels.expect.size
            val quality = if (total == 0) "" else " · 라벨 ${total - problems.size}/$total 충족"
            if (diff.isEmpty() && problems.isEmpty()) lines += "${e.id}: 변화 없음$quality"
            else { clean = false; lines += "${e.id}: 변화 ${diff.size}건 · 라벨 문제 ${problems.size}건$quality"; lines += (diff + problems).map { "  $it" } }
        }
        return clean to lines
    }

    private fun select(m: CorpusManifest, ids: List<String>): List<CorpusEntry> {
        if (ids.isEmpty()) return m.projects
        val unknown = ids.filter { id -> m.projects.none { it.id == id } }
        require(unknown.isEmpty()) { "목록에 없는 프로젝트: ${unknown.joinToString()}" }
        return m.projects.filter { it.id in ids }
    }

    companion object {
        fun snapshot(id: String, dir: Path, commit: String): CorpusSnapshot {
            val profile = StackDetector.detect(dir)
            val arch = ArchitectureMiner.mine(dir, profile)
            val conv = ConventionMiner.mine(dir, profile)
            return CorpusSnapshot(
                id, commit, arch.layout.mode, arch.applicability.status, arch.layout.slices,
                arch.rules.map { RuleStat(it.id, it.proposedSeverity, it.evidence.compliant, it.evidence.total) }.sortedBy { it.id },
                conv.rules.map { RuleStat(it.id, it.severity, it.evidence.compliant, it.evidence.total) }.sortedBy { it.id },
            )
        }

        fun labelProblems(labels: Labels, snap: CorpusSnapshot): List<String> {
            val ids = (snap.architecture + snap.convention).map { it.id }.toSet()
            return labels.correct.filter { it.id !in ids }.map { "맞다고 표시한 규칙이 사라짐: ${it.id}" } +
                labels.wrong.filter { it.id in ids }.map { "틀리다고 표시한 규칙이 채굴됨: ${it.id}${if (it.note.isEmpty()) "" else " (${it.note})"}" } +
                labels.expect.filter { x -> ids.none { it.startsWith(x.id) } }.map { "기대한 규칙이 채굴되지 않음: ${it.id}${if (it.note.isEmpty()) "" else " (${it.note})"}" }
        }

        fun diff(old: CorpusSnapshot, new: CorpusSnapshot): List<String> {
            val out = mutableListOf<String>()
            if (old.applicability != new.applicability) out += "적용 가능성 ${old.applicability} → ${new.applicability}"
            if (old.mode != new.mode) out += "구조 모드 ${old.mode} → ${new.mode}"
            if (old.slices != new.slices) out += "슬라이스 ${old.slices} → ${new.slices}"
            out += rules("아키텍처", old.architecture, new.architecture)
            out += rules("컨벤션", old.convention, new.convention)
            return out
        }

        private fun rules(label: String, old: List<RuleStat>, new: List<RuleStat>): List<String> {
            val before = old.associateBy { it.id }
            val after = new.associateBy { it.id }
            val out = mutableListOf<String>()
            (after.keys - before.keys).sorted().forEach { out += "$label 규칙 추가: $it (${after.getValue(it).compliant}/${after.getValue(it).total})" }
            (before.keys - after.keys).sorted().forEach { out += "$label 규칙 삭제: $it" }
            before.keys.intersect(after.keys).sorted().forEach { id ->
                val a = before.getValue(id); val b = after.getValue(id)
                if (a != b) out += "$label 규칙 변경: $id ${a.severity} ${a.compliant}/${a.total} → ${b.severity} ${b.compliant}/${b.total}"
            }
            return out
        }
    }
}
