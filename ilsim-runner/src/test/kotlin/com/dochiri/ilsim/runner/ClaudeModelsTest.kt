package com.dochiri.ilsim.runner

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ClaudeModelsTest {

    @Test
    fun `Fable 모델로는 Claude 를 실행하지 않는다`() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        assertFailsWith<IllegalStateException> { Shell.process(tmp, listOf("claude", "-p", "--model", "claude-fable-5-1")) }
        Shell.process(tmp, listOf("claude", "-p", "--model", ClaudeModels.SONNET))
    }
}
