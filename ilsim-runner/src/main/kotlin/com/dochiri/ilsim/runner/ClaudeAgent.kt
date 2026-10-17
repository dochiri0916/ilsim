package com.dochiri.ilsim.runner

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Claude 한 번의 실행에 허용하는 최대 시간. */
const val CLAUDE_TIMEOUT_MINUTES = 30L

/** [costUsd]는 Claude 가 마지막 `result` 줄에 남긴 비용. 끝까지 못 돌았거나 줄이 없으면 null. */
data class AgentRun(val exit: Int, val timedOut: Boolean = false, val costUsd: Double? = null, val sessionId: String? = null)

object ClaudeCost {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /** stream-json 한 줄이 `result` 이벤트면 total_cost_usd 를 돌려준다. */
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

    /** stream-json 한 줄에서 세션 id 를 읽는다. 마지막 `result` 줄의 값이 이어 갈 때 쓰는 id 다. */
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

/** 워크트리에서 코드를 쓰는 Claude 실행. 출력 한 줄마다 [onLine]을 부른다. 테스트에서는 파일을 직접 바꾸는 가짜로 대신한다. */
fun interface ClaudeAgent {
    fun run(worktree: Path, task: AssignedTask, prompt: String, onLine: (String) -> Unit): AgentRun
}

object ProcessClaudeAgent : ClaudeAgent {
    override fun run(worktree: Path, task: AssignedTask, prompt: String, onLine: (String) -> Unit): AgentRun {
        val claude = ProcessBuilder(
            listOf("claude", "-p", prompt) + (task.resumeSessionId?.let { listOf("--resume", it) } ?: emptyList()) + listOf(
            "--model", task.model,
            "--effort", task.effort,
            "--output-format", "stream-json", "--verbose",
            "--permission-mode", "acceptEdits",
            "--allowedTools", Gate.allowedTools(task.stage),
            ),
        ).directory(worktree.toFile()).redirectErrorStream(true).start()
        claude.outputStream.close()
        // 멈추거나 끝없이 도는 실행이 작업 전체를 붙잡지 않도록 시간 상한을 둔다.
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread {
            if (!claude.waitFor(CLAUDE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                timedOut.set(true)
                claude.destroyForcibly()
            }
        }.apply { isDaemon = true; start() }
        var cost: Double? = null
        var session: String? = null
        claude.inputStream.bufferedReader().forEachLine { line ->
            ClaudeCost.parse(line)?.let { cost = it }
            ClaudeSession.parse(line)?.let { session = it }
            onLine(line)
        }
        val exit = claude.waitFor()
        watchdog.interrupt()
        return AgentRun(exit, timedOut.get(), cost, session)
    }
}
