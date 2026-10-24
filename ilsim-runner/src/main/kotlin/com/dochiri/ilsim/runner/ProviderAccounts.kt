package com.dochiri.ilsim.runner

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProviderConfig(
    val provider: String,
    val baseUrl: String,
    val model: String,
    val effort: String = "max",
    val keyFile: String = "deepseek.key",
    val inputUsdPerMillion: Double = 0.14,
    val outputUsdPerMillion: Double = 0.28,
    val cacheReadUsdPerMillion: Double = 0.0028,
    val maxAttempt: Int = 1,
)

object ProviderAccounts {
    private val json = Json { ignoreUnknownKeys = true }
    var home: Path = Path.of(System.getProperty("user.home"), ".ilsim")

    fun config(name: String?): ProviderConfig? {
        val file = name?.let { ClaudeAccounts.dir(it).resolve("provider.json") } ?: return null
        if (!file.isRegularFile()) return null
        return runCatching { json.decodeFromString<ProviderConfig>(file.readText()) }.getOrNull()
    }

    fun key(config: ProviderConfig): String? =
        home.resolve(config.keyFile).takeIf { it.isRegularFile() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun usableName(): String? = ClaudeAccounts.names().sorted().firstOrNull { name -> config(name)?.let { key(it) != null } == true }

    fun eligible(task: AssignedTask, config: ProviderConfig): Boolean =
        task.attempt <= config.maxAttempt && !task.model.contains("opus", ignoreCase = true) && task.effort in setOf("low", "medium")

    fun environment(config: ProviderConfig, env: MutableMap<String, String>) {
        val key = key(config) ?: return
        env["ANTHROPIC_BASE_URL"] = config.baseUrl
        env["ANTHROPIC_AUTH_TOKEN"] = key
        env["ANTHROPIC_MODEL"] = config.model
        listOf("SONNET", "OPUS", "HAIKU").forEach { env["ANTHROPIC_DEFAULT_${it}_MODEL"] = config.model }
        env["CLAUDE_CODE_SUBAGENT_MODEL"] = config.model
    }

    fun adapt(task: AssignedTask): AssignedTask =
        config(ClaudeAccountScope.account)?.let { task.copy(model = it.model, effort = it.effort) }
            ?: task.copy(effort = ClaudeModels.clampEffort(task.model, task.effort))

    fun priced(usage: List<AiUsage>): List<AiUsage> {
        val config = config(ClaudeAccountScope.account) ?: return usage
        return usage.map {
            val usd = (it.inputTokens * config.inputUsdPerMillion + it.cacheWriteTokens * config.inputUsdPerMillion +
                it.outputTokens * config.outputUsdPerMillion + it.cacheReadTokens * config.cacheReadUsdPerMillion) / 1_000_000
            it.copy(costUsd = usd, status = "CONFIRMED")
        }
    }
}
