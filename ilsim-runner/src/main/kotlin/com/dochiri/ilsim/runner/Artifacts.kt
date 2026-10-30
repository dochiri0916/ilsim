package com.dochiri.ilsim.runner

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UserDecision(val question: String? = null, val text: String, val stage: String, val at: String)

class Artifacts(config: ConfigStore) {
    private val base: Path = config.location.parent.resolve("projects")

    fun path(projectId: String, feature: String, taskId: String, stage: String): Path? {
        val name = name(stage) ?: return null
        return folder(projectId, feature, taskId).resolve(name)
    }

    private fun folder(projectId: String, feature: String, taskId: String): Path {
        val folder = "$feature-${taskId.take(8)}"
        require(SAFE.matches(projectId) && SAFE.matches(folder)) { "잘못된 산출물 위치: $projectId/$folder" }
        return base.resolve(projectId).resolve("specs").resolve(folder)
    }

    fun decisions(projectId: String, feature: String, taskId: String): List<UserDecision> =
        folder(projectId, feature, taskId).resolve(DECISIONS).takeIf { it.exists() }?.let { runCatching { json.decodeFromString<List<UserDecision>>(it.readText()) }.getOrNull() }.orEmpty()

    fun addDecision(projectId: String, feature: String, taskId: String, decision: UserDecision): List<UserDecision> {
        val dir = folder(projectId, feature, taskId)
        return KnowledgeFiles.locked(dir) {
            val next = decisions(projectId, feature, taskId).filter { decision.question == null || it.question != decision.question } + decision
            KnowledgeFiles.write(dir.resolve(DECISIONS), json.encodeToString(next) + "\n")
            next
        }
    }

    fun read(projectId: String, feature: String, taskId: String, stage: String): String? =
        path(projectId, feature, taskId, stage)?.takeIf { it.exists() }?.readText()

    fun write(projectId: String, feature: String, taskId: String, stage: String, text: String): Path {
        val file = checkNotNull(path(projectId, feature, taskId, stage)) { "$stage 단계에는 문서 산출물이 없습니다" }
        KnowledgeFiles.write(file, text.trimEnd() + "\n")
        return file
    }

    fun writeLedger(projectId: String, feature: String, taskId: String, stage: String, text: String): Path {
        val file = folder(projectId, feature, taskId).resolve(ledgerName(stage))
        KnowledgeFiles.write(file, text.trimEnd() + "\n")
        return file
    }

    fun ledgers(projectId: String, feature: String, taskId: String): List<String> =
        STAGES.mapNotNull { folder(projectId, feature, taskId).resolve(ledgerName(it)).takeIf { f -> f.exists() }?.readText()?.trimEnd() }

    companion object {
        private val STAGES = listOf("REQUIREMENTS", "TEST_CASES", "TEST_CODE", "IMPLEMENTATION")

        private fun ledgerName(stage: String) = "verification-${stage.lowercase()}.md"

        private val SAFE = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
        private const val DECISIONS = "decisions.json"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val DOCUMENT_STAGES = setOf("REQUIREMENTS", "TEST_CASES")

        fun render(decisions: List<UserDecision>): String? = decisions.takeIf { it.isNotEmpty() }?.let { items ->
            "## 사용자가 정한 사항\n\n요구사항 승인 뒤 사용자가 정했다. 요구사항 문서와 같게 따른다.\n" +
                items.joinToString("\n") { d -> "- ${d.question?.let { "$it: " }.orEmpty()}${d.text}" }
        }

        fun name(stage: String): String? = when (stage) {
            "REQUIREMENTS" -> "requirements.md"
            "TEST_CASES" -> "test-spec.md"
            else -> null
        }
    }
}
