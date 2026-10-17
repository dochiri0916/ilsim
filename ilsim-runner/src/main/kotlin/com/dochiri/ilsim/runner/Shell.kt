package com.dochiri.ilsim.runner

import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ShellResult(val exit: Int, val output: String)

object Shell {
    /** 명령을 실행하고 종료 코드와 출력(끝부분 최대 [maxChars]자)을 돌려준다. 시간 초과 시 exit=-1. */
    fun run(dir: Path, vararg cmd: String, timeoutMinutes: Long = 10, maxChars: Int = 4000): ShellResult {
        val p = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        if (!p.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            reader.join(2000)
            return ShellResult(-1, "시간 초과(${timeoutMinutes}분)\n" + out.toString().takeLast(maxChars))
        }
        reader.join()
        return ShellResult(p.exitValue(), out.toString().takeLast(maxChars))
    }

    fun git(dir: Path, vararg args: String): ShellResult = run(dir, "git", *args, timeoutMinutes = 2, maxChars = 200_000)

    fun gitOk(dir: Path, vararg args: String): String {
        val r = git(dir, *args)
        check(r.exit == 0) { "git ${args.joinToString(" ")} 실패: ${r.output}" }
        return r.output
    }
}
