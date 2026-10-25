package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.Gate
import com.dochiri.ilsim.runner.ProjectEntry
import com.dochiri.ilsim.runner.ReviewCache
import com.dochiri.ilsim.runner.RunnerConfig
import com.dochiri.ilsim.runner.Shell
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class Workspace(val root: Path, val projectId: String, val project: ProjectEntry) {
    fun changedFiles(): List<String> = Shell.gitOk(root, "status", "--porcelain", "-uall")
        .lines().filter { it.length > 3 }
        .map { it.substring(3).trim().removeSurrounding("\"") }
        .filterNot(Gate::isIgnored)

    fun branch(): String = Shell.gitOk(root, "rev-parse", "--abbrev-ref", "HEAD").trim()

    fun installCommitHook(command: String): String? {
        val hooks = root.resolve(Shell.gitOk(root, "rev-parse", "--git-path", "hooks").trim()).normalize()
        val hook = hooks.resolve("pre-commit")
        val content = "#!/bin/sh\n$HOOK_MARK\nexec \"$command\" hook commit\n"
        if (hook.exists()) {
            val text = hook.readText()
            if (HOOK_MARK !in text) return "기존 pre-commit 훅($hook)이 있어 일심 커밋 차단 훅을 설치하지 않았습니다"
            if (text == content) return null
        }
        hooks.createDirectories()
        hook.writeText(content)
        hook.toFile().setExecutable(true)
        return null
    }

    companion object {
        const val HOOK_MARK = "ilsim_hook=1"

        fun root(dir: Path): Path? = Shell.git(dir, "rev-parse", "--show-toplevel").takeIf { it.exit == 0 }
            ?.output?.trim()?.takeIf { it.isNotEmpty() }?.let { real(Path.of(it)) }

        fun of(config: RunnerConfig, dir: Path): Workspace? {
            val root = root(dir) ?: return null
            val (id, project) = config.projects.entries.firstOrNull { real(Path.of(it.value.path)) == root } ?: return null
            return Workspace(root, id, project)
        }

        private fun real(path: Path): Path = path.toAbsolutePath().normalize().let { if (it.exists()) it.toRealPath() else it }
    }
}

@Serializable
data class ActiveTask(
    val taskId: String,
    val projectId: String,
    val feature: String,
    val branch: String,
    val stage: String? = null,
    val status: String? = null,
    val stopBlocks: Int = 0,
    val shelved: String? = null,
    val shelvedStage: String? = null,
    val attention: List<String> = emptyList(),
)

class ActiveTasks(private val dir: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun file(root: Path): Path = dir.resolve(ReviewCache.digest(root.toString()).take(16) + ".json")

    fun load(root: Path): ActiveTask? = file(root).takeIf { it.exists() }?.let { runCatching { json.decodeFromString<ActiveTask>(it.readText()) }.getOrNull() }

    fun save(root: Path, task: ActiveTask) {
        dir.createDirectories()
        file(root).writeText(json.encodeToString(task))
    }

    fun clear(root: Path) {
        file(root).deleteIfExists()
    }
}
