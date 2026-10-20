package com.dochiri.ilsim.runner

import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ShellResult(val exit: Int, val output: String)

object Shell {
    const val RUNNER_ENV = "ILSIM_RUNNER"

    fun process(dir: Path, cmd: List<String>): ProcessBuilder =
        ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).also {
            it.environment()[RUNNER_ENV] = "1"
            if (cmd.firstOrNull() == "claude") {
                cmd.zipWithNext().firstOrNull { (k, v) -> k == "--model" && ClaudeModels.isLocked(v) }?.let { error("잠긴 모델은 실행하지 않습니다: ${it.second}") }
                ClaudeAccountScope.configure(it.environment())
            }
        }

    fun start(builder: ProcessBuilder): Process = StopSignal.track(builder.start())

    fun run(dir: Path, vararg cmd: String, timeoutMinutes: Long = 10, maxChars: Int = 4000, stdin: String? = null): ShellResult {
        val p = start(process(dir, cmd.toList()))
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        val writer = if (stdin == null) { p.outputStream.close(); null } else Thread {
            runCatching { p.outputStream.bufferedWriter().use { it.write(stdin) } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            reader.join(2000)
            return ShellResult(-1, "시간 초과(${timeoutMinutes}분)\n" + out.toString().takeLast(maxChars))
        }
        reader.join()
        writer?.join(2000)
        return ShellResult(p.exitValue(), out.toString().takeLast(maxChars))
    }

    fun git(dir: Path, vararg args: String): ShellResult = run(dir, "git", *args, timeoutMinutes = 2, maxChars = 200_000)

    fun gitOk(dir: Path, vararg args: String): String {
        val r = git(dir, *args)
        check(r.exit == 0) { "git ${args.joinToString(" ")} 실패: ${r.output}" }
        return r.output
    }
}
