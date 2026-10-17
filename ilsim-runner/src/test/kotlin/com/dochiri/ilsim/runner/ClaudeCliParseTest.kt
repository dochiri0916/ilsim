package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeCliParseTest {
    @Test
    fun `json 결과에서 답과 비용을 읽는다`() {
        val a = ClaudeCli.parse("""{"type":"result","result":"{\"ok\":true}","total_cost_usd":0.0123}""")
        assertEquals("""{"ok":true}""", a.text)
        assertEquals(0.0123, a.costUsd)
    }

    @Test
    fun `json 이 아니면 출력 전체를 답으로 보고 비용은 없다`() {
        val a = ClaudeCli.parse("그냥 텍스트 답")
        assertEquals("그냥 텍스트 답", a.text)
        assertNull(a.costUsd)
    }

    @Test
    fun `비용이 음수거나 없으면 null`() {
        assertNull(ClaudeCli.parse("""{"result":"x","total_cost_usd":-1}""").costUsd)
        assertNull(ClaudeCli.parse("""{"result":"x"}""").costUsd)
    }
}
