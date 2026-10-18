package com.dochiri.ilsim.runner

import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ShellResult(val exit: Int, val output: String)

object Shell {
    /**
     * Runner 가 띄운 프로세스라는 표시. 일심 저장소 자체를 작업할 때 저장소의 Claude Code 훅(인수인계)이
     * 단계 작업에 끼어들지 않도록 훅이 이 값을 보고 건너뛴다.
     */
    const val RUNNER_ENV = "ILSIM_RUNNER"

    fun process(dir: Path, cmd: List<String>): ProcessBuilder =
        ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).also { it.environment()[RUNNER_ENV] = "1" }

    /** 프로세스를 띄우고 이 스레드의 [StopSignal] 에 등록해 중지 요청 때 함께 끝나게 한다. */
    fun start(builder: ProcessBuilder): Process = StopSignal.track(builder.start())

    /** 명령을 실행하고 종료 코드와 출력(끝부분 최대 [maxChars]자)을 돌려준다. 시간 초과 시 exit=-1. */
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
