package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class ExecutionCheckpoint(private val file: Path) {
    @Serializable data class State(
        val scope: String, val snapshot: String, val executionId: String?, val usage: List<AiUsage>, val sessionId: String?, val reusable: Boolean = false,
        val result: StageResult? = null, val commitParent: String? = null, val commitTree: String? = null,
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): State? = file.takeIf { it.exists() }?.let { runCatching { json.decodeFromString<State>(it.readText()) }.getOrNull() }
    fun save(state: State) = KnowledgeFiles.write(file, json.encodeToString(state))
    fun invalidate() { Files.deleteIfExists(file) }

    companion object {
        fun snapshot(worktree: Path): String {
            val tracked = Shell.gitOk(worktree, "ls-files", "-co", "--exclude-standard").lines().filter { it.isNotBlank() && !Gate.isIgnored(it) }.distinct().sorted()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(Shell.gitOk(worktree, "rev-parse", "HEAD").toByteArray())
            tracked.forEach { path ->
                digest.update("\u0000$path\u0000".toByteArray())
                val file = worktree.resolve(path)
                digest.update(if (Files.isExecutable(file)) 1.toByte() else 0.toByte())
                if (Files.isRegularFile(file)) Files.newInputStream(file).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class SessionCostLedger(private val directory: Path) {
    fun delta(call: AiUsage): Double? {
        val cost = call.costUsd?.takeIf { it.isFinite() && it >= 0 } ?: return null
        if (!call.cumulative || call.sessionId == null) return cost
        val file = directory.resolve(ReviewCache.digest("${call.sessionId}/${call.model}/${call.cliVersion}") + ".cost")
        return KnowledgeFiles.locked(directory) {
            val previous = file.takeIf { it.exists() }?.readText()?.toDoubleOrNull() ?: 0.0
            if (cost < previous) return@locked null
            KnowledgeFiles.write(file, cost.toString())
            cost - previous
        }
    }
}
