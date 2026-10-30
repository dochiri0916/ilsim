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
    val auditSampleRate: Double? = null,
    val auditMaxDiffLines: Int? = null,
    val docs: List<String> = emptyList(),
    val docsExclude: List<String> = emptyList(),
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

@Serializable
data class AccessToken(val clientId: String, val clientSecret: String)

object RunnerAccess {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun headers(env: Map<String, String>, file: Path): Map<String, String> {
        val id = env["ILSIM_ACCESS_CLIENT_ID"]?.takeIf { it.isNotBlank() }
        val secret = env["ILSIM_ACCESS_CLIENT_SECRET"]?.takeIf { it.isNotBlank() }
        val token = if (id != null && secret != null) AccessToken(id, secret)
            else file.takeIf { it.exists() }?.let { runCatching { json.decodeFromString<AccessToken>(it.readText()) }.getOrNull() }
        return token?.let { mapOf("CF-Access-Client-Id" to it.clientId, "CF-Access-Client-Secret" to it.clientSecret) }.orEmpty()
    }

    fun save(file: Path, token: AccessToken): Unit = Secrets.write(file, json.encodeToString(token) + "\n")
}

object Secrets {
    fun write(file: Path, text: String) {
        file.parent.createDirectories()
        file.writeText(text)
        runCatching { java.nio.file.Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
    }
}
