package com.dochiri.ilsim.runner.memory

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.KnowledgeFiles
import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.risk.RiskOpinion
import com.dochiri.ilsim.runner.risk.RiskReviewer
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MemoryEntry(
    val id: String,
    val kind: String,
    val ruleId: String,
    val file: String,
    val fingerprint: String,
    val summary: String,
    val recommendation: String = "",
    val evidence: List<String> = emptyList(),
    val source: String,
    val commit: String? = null,
    val status: String = "CANDIDATE",
    val createdAt: String,
    val approvedAt: String? = null,
    val supersededReason: String? = null,
)

class ProjectMemory(config: ConfigStore) {
    private val base: Path = config.location.parent.resolve("projects")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private fun file(projectId: String): Path {
        require(Regex("""[A-Za-z0-9._-]+""").matches(projectId)) { "잘못된 프로젝트 id: $projectId" }
        return base.resolve(projectId).resolve("memory.json")
    }

    fun load(projectId: String): List<MemoryEntry> =
        file(projectId).takeIf { it.exists() }?.let { json.decodeFromString<List<MemoryEntry>>(it.readText()) } ?: emptyList()

    fun save(projectId: String, entries: List<MemoryEntry>) {
        KnowledgeFiles.write(file(projectId), json.encodeToString(entries))
    }

    private fun <T> locked(projectId: String, action: () -> T): T = KnowledgeFiles.locked(file(projectId).parent, action)

    fun suppressed(projectId: String): Set<String> =
        load(projectId).filter { it.status == "ACTIVE" && it.kind == "FALSE_POSITIVE" }.map { it.fingerprint }.toSet()

    fun collect(projectId: String, root: Path, findings: List<Violation>, opinions: Map<String, RiskOpinion>, commit: String?): Int = locked(projectId) {
        val entries = load(projectId).toMutableList()
        val known = entries.filter { it.status != "SUPERSEDED" }.map { it.kind + it.fingerprint }.toSet()
        var added = 0
        for (v in findings) {
            val fp = RiskReviewer.fingerprint(root, v)
            val op = opinions[fp] ?: continue
            val kind = when (op.verdict) { "FALSE" -> "FALSE_POSITIVE"; "TRUE" -> "CONFIRMED_RISK"; else -> continue }
            if (kind + fp in known) continue
            entries += MemoryEntry(
                id = "mem-${fp.take(8)}-${if (kind == "FALSE_POSITIVE") "fp" else "risk"}", kind = kind, ruleId = v.ruleId, file = v.file, fingerprint = fp,
                summary = "${v.message} → ${op.reason}", recommendation = op.recommendation, evidence = listOf("${v.file}:${v.line}"),
                source = "risk-review:${op.reviewer}", commit = commit, createdAt = Instant.now().toString(),
            )
            added++
        }
        save(projectId, entries)
        added
    }

    fun dismiss(projectId: String, root: Path, findings: List<Violation>, commit: String?, matches: (Violation) -> Boolean): Int = locked(projectId) {
        val entries = load(projectId).toMutableList()
        val active = entries.filter { it.status == "ACTIVE" && it.kind == "FALSE_POSITIVE" }.map { it.fingerprint }.toSet()
        var added = 0
        for (v in findings.filter(matches)) {
            val fp = RiskReviewer.fingerprint(root, v)
            if (fp in active) continue
            val now = Instant.now().toString()
            entries.removeAll { it.fingerprint == fp && it.kind == "FALSE_POSITIVE" && it.status == "CANDIDATE" }
            entries += MemoryEntry(
                id = "mem-${fp.take(8)}-fp", kind = "FALSE_POSITIVE", ruleId = v.ruleId, file = v.file, fingerprint = fp,
                summary = "${v.message} → 사용자가 오탐으로 제외", evidence = listOf("${v.file}:${v.line}"),
                source = "user", commit = commit, status = "ACTIVE", createdAt = now, approvedAt = now,
            )
            added++
        }
        save(projectId, entries)
        added
    }

    fun approve(projectId: String, ids: Collection<String>): List<MemoryEntry> = decide(projectId, ids) {
        it.copy(status = "ACTIVE", approvedAt = Instant.now().toString(), supersededReason = null)
    }

    fun reject(projectId: String, ids: Collection<String>): List<MemoryEntry> = decide(projectId, ids) {
        it.copy(status = "SUPERSEDED", supersededReason = "사용자가 거절")
    }

    fun pendingIds(projectId: String): List<String> = load(projectId).filter { it.status == "CANDIDATE" }.map { it.id }

    private fun decide(projectId: String, ids: Collection<String>, change: (MemoryEntry) -> MemoryEntry): List<MemoryEntry> = locked(projectId) {
        val entries = load(projectId)
        val unknown = ids.filterNot { id -> entries.any { it.id == id } }
        require(unknown.isEmpty()) { "알 수 없는 메모리 id: ${unknown.joinToString()}" }
        val updated = entries.map { if (it.id in ids && it.status != "SUPERSEDED") change(it) else it }
        save(projectId, updated)
        updated
    }

    fun verify(projectId: String, root: Path, findings: List<Violation>): Int = locked(projectId) {
        val current = findings.map { RiskReviewer.fingerprint(root, it) }.toSet()
        var changed = 0
        val updated = load(projectId).map {
            if (it.status != "SUPERSEDED" && it.fingerprint !in current) {
                changed++
                it.copy(status = "SUPERSEDED", supersededReason = "코드가 바뀌어 근거가 더 이상 확인되지 않음")
            } else it
        }
        save(projectId, updated)
        changed
    }

    fun report(entries: List<MemoryEntry>): String = buildString {
        appendLine("메모리 ${entries.size}건 · 후보 ${entries.count { it.status == "CANDIDATE" }} · 활성 ${entries.count { it.status == "ACTIVE" }} · 폐기 ${entries.count { it.status == "SUPERSEDED" }}")
        entries.forEach {
            appendLine("  [${it.status}] ${it.id} ${it.kind} ${it.ruleId} ${it.evidence.firstOrNull().orEmpty()} (${it.source}, commit ${it.commit?.take(7) ?: "-"})")
            appendLine("      ${it.summary}")
            if (it.recommendation.isNotBlank()) appendLine("      권고: ${it.recommendation}")
            it.supersededReason?.let { r -> appendLine("      폐기 사유: $r") }
        }
    }
}
