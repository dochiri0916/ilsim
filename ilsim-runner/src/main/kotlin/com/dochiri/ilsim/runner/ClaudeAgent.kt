package com.dochiri.ilsim.runner

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Claude 한 번의 실행에 허용하는 최대 시간. */
const val CLAUDE_TIMEOUT_MINUTES = 30L

data class AgentRun(val exit: Int, val timedOut: Boolean = false)

/** 워크트리에서 코드를 쓰는 Claude 실행. 출력 한 줄마다 [onLine]을 부른다. 테스트에서는 파일을 직접 바꾸는 가짜로 대신한다. */
fun interface ClaudeAgent {
    fun run(worktree: Path, task: AssignedTask, prompt: String, onLine: (String) -> Unit): AgentRun
}

object ProcessClaudeAgent : ClaudeAgent {
    override fun run(worktree: Path, task: AssignedTask, prompt: String, onLine: (String) -> Unit): AgentRun {
        val claude = ProcessBuilder(
            "claude", "-p", prompt,
            "--model", task.model,
            "--effort", task.effort,
            "--output-format", "stream-json", "--verbose",
            "--permission-mode", "acceptEdits",
            "--allowedTools", Gate.allowedTools(task.stage),
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
        claude.inputStream.bufferedReader().forEachLine(onLine)
        val exit = claude.waitFor()
        watchdog.interrupt()
        return AgentRun(exit, timedOut.get())
    }
}
