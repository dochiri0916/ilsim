package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeSessionTest {
    @Test
    fun `stream-json 줄에서 세션 id 를 읽는다`() {
        assertEquals("abc-1", ClaudeSession.parse("""{"type":"system","subtype":"init","session_id":"abc-1"}"""))
        assertEquals("abc-1", ClaudeSession.parse("""{"type":"result","total_cost_usd":0.1,"session_id":"abc-1"}"""))
    }

    @Test
    fun `세션 id 가 없거나 깨진 줄은 null`() {
        assertNull(ClaudeSession.parse("""{"type":"assistant"}"""))
        assertNull(ClaudeSession.parse("""{"session_id":""}"""))
        assertNull(ClaudeSession.parse("깨진 줄 session_id"))
    }
}
