package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.Shell
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TaskPolicy(val maxSubmissions: Int? = null, val maxClaudeCalls: Int? = null, val maxCostUsd: Double? = null) {
    fun exceeded(task: ActiveTask): String? = listOfNotNull(
        maxSubmissions?.takeIf { task.submissions >= it }?.let { "이 단계에서 실패한 제출 ${task.submissions}회(상한 ${it}회)" },
        maxClaudeCalls?.takeIf { task.claudeCalls >= it }?.let { "Claude 호출 ${task.claudeCalls}회(상한 ${it}회)" },
        maxCostUsd?.takeIf { task.costUsd >= it }?.let { "AI 비용 $${"%.2f".format(task.costUsd)}(상한 $${"%.2f".format(it)})" },
    ).joinToString(", ").ifEmpty { null }

    companion object {
        const val FILE = ".ilsim-policy.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(root: Path): TaskPolicy {
            val result = Shell.git(root, "show", "HEAD:./$FILE")
            if (result.exit != 0) return TaskPolicy()
            return runCatching { json.decodeFromString<TaskPolicy>(result.output) }
                .getOrElse { throw IllegalStateException("$FILE 을 읽지 못했습니다(${it.message?.lineSequence()?.firstOrNull()}). 사용자에게 알리고 고치게 하세요") }
        }
    }
}
