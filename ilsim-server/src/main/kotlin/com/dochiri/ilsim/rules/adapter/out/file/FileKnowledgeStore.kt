package com.dochiri.ilsim.rules.adapter.out.file

import com.dochiri.ilsim.rules.application.port.out.KnowledgeStore
import com.dochiri.ilsim.rules.domain.Decision
import com.dochiri.ilsim.rules.domain.MemoryView
import com.dochiri.ilsim.rules.domain.RuleSet
import com.dochiri.ilsim.rules.domain.RuleView
import com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * `<home>/projects/<프로젝트 id>/{architecture,convention,memory}.json`.
 * 파일을 JSON 트리로 읽고 승인 관련 필드만 바꿔 다시 쓰므로, Runner 가 넣은 나머지 필드는 그대로 보존된다.
 * 쓸 때는 임시 파일에 쓴 뒤 옮겨서 Runner 가 반쯤 쓰인 파일을 읽지 않게 하고,
 * 읽고-고치고-쓰는 동안 프로젝트 디렉터리의 `.lock` 을 OS 파일 잠금으로 잡아 Runner 의 변경과 서로 덮어쓰지 않게 한다(Runner `KnowledgeFiles` 와 같은 규약).
 */
class FileKnowledgeStore(home: Path) : KnowledgeStore {

    private val base: Path = home.resolve("projects")
    private val json: JsonMapper = JsonMapper.builder().build()
    private val lock = Any()

    override fun rules(projectId: String, set: RuleSet): List<RuleView> = synchronized(lock) {
        val rules = read(file(projectId, set.fileName))?.get("rules") ?: return@synchronized emptyList<RuleView>()
        (rules as Iterable<JsonNode>).map { r ->
            RuleView(
                id = text(r, "id").orEmpty(),
                kind = text(r, "kind").orEmpty(),
                description = text(r, "description").orEmpty(),
                severity = text(r, "proposedSeverity") ?: text(r, "severity"),
                approved = r.path("approved").asBoolean(false),
                rejected = r.path("rejected").asBoolean(false),
                decidedBy = text(r, "decidedBy"),
                reviewVerdict = text(r.path("review"), "verdict"),
                reviewReason = text(r.path("review"), "reason"),
            )
        }
    }

    override fun memory(projectId: String): List<MemoryView> = synchronized(lock) {
        (read(file(projectId, MEMORY)) ?: return@synchronized emptyList<MemoryView>()).let { it as Iterable<JsonNode> }.map { m ->
            MemoryView(
                id = text(m, "id").orEmpty(),
                kind = text(m, "kind").orEmpty(),
                ruleId = text(m, "ruleId").orEmpty(),
                file = text(m, "file").orEmpty(),
                summary = text(m, "summary").orEmpty(),
                recommendation = text(m, "recommendation").orEmpty(),
                status = text(m, "status") ?: "CANDIDATE",
                createdAt = text(m, "createdAt"),
                approvedAt = text(m, "approvedAt"),
                supersededReason = text(m, "supersededReason"),
            )
        }
    }

    override fun decideRules(projectId: String, set: RuleSet, ids: Collection<String>, decision: Decision) = synchronized(lock) {
        val path = file(projectId, set.fileName)
        fileLocked(path) {
            val root = read(path) ?: throw InvalidRuleRequestException("규칙 파일이 없습니다")
            val approve = decision == Decision.APPROVE
            (root.get("rules") as? ArrayNode)?.forEach { r ->
                if (r is ObjectNode && text(r, "id") in ids) {
                    r.put("approved", approve)
                    r.put("rejected", !approve)
                    r.put("decidedBy", "user")
                }
            }
            write(path, root)
        }
    }

    override fun decideMemory(projectId: String, ids: Collection<String>, decision: Decision) = synchronized(lock) {
        val path = file(projectId, MEMORY)
        fileLocked(path) {
            val root = read(path) ?: throw InvalidRuleRequestException("메모리 파일이 없습니다")
            root.forEach { m ->
                if (m is ObjectNode && text(m, "id") in ids && text(m, "status") != "SUPERSEDED") {
                    if (decision == Decision.APPROVE) {
                        m.put("status", "ACTIVE")
                        m.put("approvedAt", Instant.now().toString())
                        m.putNull("supersededReason")
                    } else {
                        m.put("status", "SUPERSEDED")
                        m.put("supersededReason", "사용자가 거절")
                    }
                }
            }
            write(path, root)
        }
    }

    private fun <T> fileLocked(path: Path, action: () -> T): T {
        if (!Files.isDirectory(path.parent)) return action()
        return FileChannel.open(path.resolveSibling(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use { action() }
        }
    }

    private fun file(projectId: String, name: String): Path {
        if (!ID.matches(projectId)) throw InvalidRuleRequestException("잘못된 프로젝트 id: $projectId")
        return base.resolve(projectId).resolve(name)
    }

    private fun read(path: Path): JsonNode? = if (Files.exists(path)) json.readTree(Files.readString(path)) else null

    private fun write(path: Path, root: JsonNode) {
        val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(root))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun text(node: JsonNode?, field: String): String? = node?.get(field)?.takeIf { it.isString }?.asString()

    private companion object {
        const val MEMORY = "memory.json"
        const val LOCK = ".lock"
        val ID = Regex("""[A-Za-z0-9._-]+""")
    }
}
