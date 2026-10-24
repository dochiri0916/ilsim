package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffortLimitTest {
    @Test
    fun `Sonnet과 Opus는 max를 쓸 수 없고 낮춰서 실행한다`() {
        assertFalse(ClaudeModels.allows("claude-sonnet-5-5", "max"))
        assertFalse(ClaudeModels.allows("claude-opus-5-5", "max"))
        assertTrue(ClaudeModels.allows("claude-sonnet-5-5", "xhigh"))
        assertTrue(ClaudeModels.allows("claude-haiku-5-5", "max"))
        assertEquals("xhigh", ClaudeModels.clampEffort("claude-opus-5-5", "max"))
        assertEquals("medium", ClaudeModels.clampEffort("claude-sonnet-5-5", "medium"))
    }

    @Test
    fun `실행 명령에도 max가 들어가지 않는다`() {
        val task = AssignedTask("t", "p", "IMPLEMENTATION", 1, "f", "claude-sonnet-5-5", "max", "prompt")
        val adapted = ProviderAccounts.adapt(task)
        assertEquals("xhigh", adapted.effort)
        val cli = ClaudeCli.command("p", "claude-opus-5-5", "max")
        assertEquals("xhigh", cli[cli.indexOf("--effort") + 1])
    }
}
