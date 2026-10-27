package com.dochiri.ilsim.runner

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class Artifacts(config: ConfigStore) {
    private val base: Path = config.location.parent.resolve("projects")

    fun path(projectId: String, feature: String, taskId: String, stage: String): Path? {
        val name = name(stage) ?: return null
        val folder = "$feature-${taskId.take(8)}"
        require(SAFE.matches(projectId) && SAFE.matches(folder)) { "잘못된 산출물 위치: $projectId/$folder" }
        return base.resolve(projectId).resolve("specs").resolve(folder).resolve(name)
    }

    fun read(projectId: String, feature: String, taskId: String, stage: String): String? =
        path(projectId, feature, taskId, stage)?.takeIf { it.exists() }?.readText()

    fun write(projectId: String, feature: String, taskId: String, stage: String, text: String): Path {
        val file = checkNotNull(path(projectId, feature, taskId, stage)) { "$stage 단계에는 문서 산출물이 없습니다" }
        KnowledgeFiles.write(file, text.trimEnd() + "\n")
        return file
    }

    companion object {
        private val SAFE = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""")
        val DOCUMENT_STAGES = setOf("REQUIREMENTS", "TEST_CASES")

        fun name(stage: String): String? = when (stage) {
            "REQUIREMENTS" -> "requirements.md"
            "TEST_CASES" -> "test-spec.md"
            else -> null
        }
    }
}
