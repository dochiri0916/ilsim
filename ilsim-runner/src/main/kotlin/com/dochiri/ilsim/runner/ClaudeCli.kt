package com.dochiri.ilsim.runner

import java.nio.file.Files

/** 도구 없이 한 번 질문하고 답을 받는다. 프로젝트 파일에 접근하지 않도록 빈 임시 디렉터리에서 실행한다. */
object ClaudeCli {
    fun ask(prompt: String, model: String): String {
        val dir = Files.createTempDirectory("ilsim-ask")
        try {
            val r = Shell.run(dir, "claude", "-p", prompt, "--model", model, "--effort", ClaudeModels.EFFORT, timeoutMinutes = 5, maxChars = 200_000)
            check(r.exit == 0) { "claude 실행 실패(${r.exit}): ${r.output.takeLast(500)}" }
            return r.output
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
