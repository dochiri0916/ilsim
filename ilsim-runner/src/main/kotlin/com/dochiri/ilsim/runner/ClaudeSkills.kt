package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable

@Serializable
data class ClaudeSkillReport(val name: String, val files: Map<String, String> = emptyMap())

object ClaudeSkills {
    private const val MANIFEST = ".ilsim-skills"
    private val NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val executable = setOf("sh", "py", "rb", "pl", "js", "mjs", "ts")

    fun install(root: Path, skills: List<ClaudeSkillReport>) {
        Files.createDirectories(root)
        val manifest = root.resolve(MANIFEST)
        val previous = if (manifest.exists()) manifest.readLines().filter { NAME.matches(it) } else emptyList()
        val current = skills.filter { NAME.matches(it.name) && it.name != "synced" }
        (previous - current.map { it.name }.toSet()).forEach { root.resolve(it).toFile().deleteRecursively() }
        for (skill in current) {
            val dir = root.resolve(skill.name).normalize()
            dir.toFile().deleteRecursively()
            for ((path, text) in skill.files) {
                val target = dir.resolve(path).normalize()
                require(target.startsWith(dir) && target != dir) { "스킬 폴더 밖 경로: ${skill.name}/$path" }
                Files.createDirectories(target.parent)
                KnowledgeFiles.write(target, text)
                if (path.substringAfterLast('.', "") in executable) target.toFile().setExecutable(true)
            }
        }
        manifest.writeText(current.joinToString("\n") { it.name })
    }
}
