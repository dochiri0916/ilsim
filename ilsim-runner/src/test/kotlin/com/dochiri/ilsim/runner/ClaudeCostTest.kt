package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeCostTest {
    @Test
    fun `result 줄에서만 비용을 읽는다`() {
        assertEquals(0.0743232, ClaudeCost.parse("""{"type":"result","subtype":"success","total_cost_usd":0.0743232,"usage":{"input_tokens":12}}"""))
        assertEquals(0.0, ClaudeCost.parse("""{"type":"result","total_cost_usd":0}"""))
    }

    @Test
    fun `다른 이벤트나 깨진 줄이나 이상한 값은 무시한다`() {
        assertNull(ClaudeCost.parse("""{"type":"assistant","total_cost_usd":1.0}"""))
        assertNull(ClaudeCost.parse("""{"type":"result","total_cost_usd":-1}"""))
        assertNull(ClaudeCost.parse("""{"type":"result","total_cost_usd":"abc"}"""))
        assertNull(ClaudeCost.parse("""{"type":"result"}"""))
        assertNull(ClaudeCost.parse("""{깨진 "total_cost_usd" """))
        assertNull(ClaudeCost.parse("그냥 텍스트"))
    }
}
