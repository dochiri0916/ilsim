package com.dochiri.ilsim.runner

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val CLAUDE_TIMEOUT_MINUTES = 30L

data class AgentRun(val exit: Int, val timedOut: Boolean = false, val costUsd: Double? = null, val sessionId: String? = null,
    val failureKind: String? = null, val usage: List<AiUsage> = emptyList(), val actualModel: String? = null, val diagnostic: String? = null)

object ClaudeCost {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(line: String): Double? {
        if (!line.contains("\"total_cost_usd\"")) return null
        return try {
            val obj = json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject ?: return null
            if (obj["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } != "result") return null
            (obj["total_cost_usd"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        } catch (e: Exception) {
            null
        }
    }
}

object ClaudeSession {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(line: String): String? {
        if (!line.contains("\"session_id\"")) return null
        return try {
            val obj = json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject ?: return null
            (obj["session_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}

fun interface ClaudeAgent {
    fun run(worktree: Path, task: AssignedTask, prompt: String, onLine: (String) -> Unit): AgentRun
}

object ProcessClaudeAgent : ClaudeAgent {
    private val version by lazy { runCatching { Shell.run(Path.of(System.getProperty("java.io.tmpdir")), "claude", "--version").output.trim().substringBefore(' ') }.getOrNull() }

    internal fun command(task: AssignedTask, prompt: String): List<String> =
        listOf("claude", "-p") + (task.resumeSessionId?.let { listOf("--resume", ClaudeAccountScope.rawSession(it)) } ?: emptyList()) + listOf(
            "--model", task.model, "--effort", task.effort, "--output-format", "stream-json", "--verbose",
            "--permission-mode", "acceptEdits", "--tools", if (task.stage in setOf("REQUIREMENTS", "TEST_CASES")) "Read,Glob,Grep,Write,Edit" else "Read,Glob,Grep,Write,Edit,Bash",
            "--disallowedTools", "mcp__*", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}",
            "--allowedTools", Gate.allowedTools(task.stage, task.testCommand))

    override fun run(worktree: Path, assigned: AssignedTask, prompt: String, onLine: (String) -> Unit): AgentRun {
        val task = ProviderAccounts.adapt(assigned)
        val cliVersion = version
        val builder = Shell.process(
            worktree,
            command(task, prompt),
        ).also { it.environment()["CLAUDE_CODE_EFFORT_LEVEL"] = task.effort }
        val claude = Shell.start(builder)
        val pendingId = java.util.UUID.randomUUID().toString()
        UsageScope.record(AiUsage(pendingId, "WRITE", task.model, task.effort, sessionId = task.resumeSessionId, cliVersion = cliVersion, status = "UNKNOWN"))
        val inputFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val writer = Thread {
            try { claude.outputStream.bufferedWriter().use { it.write(prompt) } } catch (e: Exception) { inputFailure.set(e) }
        }.apply { isDaemon = true; start() }
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                if (!claude.waitFor(CLAUDE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    timedOut.set(true)
                    claude.destroyForcibly()
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        var cost: Double? = null
        var session: String? = null
        var result: String? = null
        var tools = 0
        var actualModel: String? = null
        val steps = linkedMapOf<String, AiUsage>()
        val errors = StringBuilder()
        claude.inputStream.bufferedReader().forEachLine { line ->
            ClaudeCost.parse(line)?.let { cost = it }
            ClaudeSession.parse(line)?.let { session = it }
            if (line.contains("\"usage\"")) ClaudeUsage.assistant(line)?.let { step ->
                if (steps.putIfAbsent(step.id, step) == null) UsageScope.complete(pendingId, listOf(AiUsage(pendingId, "WRITE", actualModel ?: task.model, task.effort,
                    sessionId = session, cliVersion = cliVersion, inputTokens = steps.values.sumOf { it.inputTokens },
                    cacheReadTokens = steps.values.sumOf { it.cacheReadTokens }, cacheWriteTokens = steps.values.sumOf { it.cacheWriteTokens }, status = "RECOVERED")))
            }
            if (line.contains("\"model\"") && line.contains("\"init\"")) {
                actualModel = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject }
                    .getOrNull()?.get("model")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: actualModel
            }
            if (line.contains("\"type\":\"result\"") || line.contains("\"type\": \"result\"")) result = line
            tools += Regex("\"type\"\\s*:\\s*\"tool_use\"").findAll(line).count()
            errors.appendLine(line)
            if (errors.length > 8000) errors.delete(0, errors.length - 8000)
            ClaudePlanUsage.parse(line)?.let { PlanUsageWatch.observe(it.copy(provider = ClaudeAccountScope.provider(ClaudeAccountScope.account))) }
            onLine(line)
        }
        val code = claude.waitFor()
        writer.join(2000)
        inputFailure.get()?.let { errors.appendLine("프롬프트 입력 실패: ${it.message}") }
        val exit = if (code == 0 && inputFailure.get() != null) 1 else code
        watchdog.interrupt()
        val failure = if (exit == 0) null else FailureKind.classify(errors.toString())
        val parsed = ClaudeUsage.parse(result.orEmpty(), task.model, task.effort, "WRITE", cliVersion, tools)
        val usage = if (parsed.all { it.status != "CONFIRMED" } && steps.isNotEmpty()) steps.values.groupBy { it.model }.map { (model, group) ->
            AiUsage(java.util.UUID.randomUUID().toString(), "WRITE", model, task.effort, session, cliVersion, costUsd = null,
                inputTokens = group.sumOf { it.inputTokens }, cacheReadTokens = group.sumOf { it.cacheReadTokens }, cacheWriteTokens = group.sumOf { it.cacheWriteTokens },
                status = "RECOVERED", toolCalls = if (model == steps.values.first().model) tools else 0)
        } else parsed
        val billed = ProviderAccounts.priced(usage)
        UsageScope.complete(pendingId, billed)
        val totalCost = if (billed === usage) cost else billed.sumOf { it.costUsd ?: 0.0 }
        return AgentRun(exit, timedOut.get(), totalCost, ClaudeAccountScope.tag(session), failure, billed, actualModel, errors.toString().takeLast(1000).takeIf { exit != 0 })
    }
}

object FailureKind {
    fun classify(text: String): String = when {
        Regex("(?i)(no conversation found|session (?:not found|does not exist)|could not find.*session)").containsMatchIn(text) -> "SESSION_MISSING"
        Regex("(?i)(unauthorized|authentication|invalid api key|not logged in|login expired|permission denied)").containsMatchIn(text) -> "AUTH"
        Regex("(?i)(rate.?limit|429|overloaded|connection|network|timed? out|503|502)").containsMatchIn(text) -> "NETWORK"
        Regex("(?i)(command not found|no such file|not executable|invalid (?:option|configuration)|exit code 12[67])").containsMatchIn(text) -> "ENVIRONMENT"
        else -> "MODEL"
    }
}
