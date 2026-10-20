package com.dochiri.ilsim.runner

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProjectEntry(
    val path: String,
    val name: String,
    val testCommand: String? = null,
    val analysisCommands: List<String> = emptyList(),
    val mutationCommand: String? = null,
)

@Serializable
data class RunnerConfig(
    val runnerId: String = "runner-" + java.util.UUID.randomUUID().toString().take(8),
    val serverUrl: String = "http://localhost:8080",
    val projects: Map<String, ProjectEntry> = emptyMap(),
)

class ConfigStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".ilsim", "runner.json"),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): RunnerConfig =
        if (file.exists()) json.decodeFromString(file.readText()) else RunnerConfig().also(::save)

    fun save(config: RunnerConfig) {
        file.parent.createDirectories()
        file.writeText(json.encodeToString(config))
    }

    val location: Path get() = file
}

object RunnerToken {
    fun resolve(env: String?, file: Path): String? =
        env?.takeIf { it.isNotBlank() } ?: file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
}
