package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CostTallyTest {
    @Test
    fun `호출이 없으면 보여 줄 것이 없다`() {
        assertNull(CostTally().summary())
    }

    @Test
    fun `호출 비용을 더해 답은 그대로 돌려주고 요약한다`() {
        val tally = CostTally { _, model -> ClaudeCli.Answer("답-$model", 0.0125) }
        assertEquals("답-m", tally.ask("p", "m"))
        tally.ask("p", "m")
        assertEquals(0.025, tally.usd, 1e-9)
        assertEquals("Claude 비용(AI 검토): $0.0250", tally.summary())
    }

    @Test
    fun `비용을 모르는 호출이 있으면 이상 으로 표시한다`() {
        val answers = ArrayDeque(listOf(ClaudeCli.Answer("a", 0.01), ClaudeCli.Answer("b", null)))
        val tally = CostTally { _, _ -> answers.removeFirst() }
        tally.ask("p", "m"); tally.ask("p", "m")
        assertEquals("Claude 비용(AI 검토): $0.0100 이상 (비용을 알 수 없는 호출 1건)", tally.summary())
    }
}
