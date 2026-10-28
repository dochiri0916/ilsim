package com.dochiri.ilsim.runner.jev

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndecidedCheckTest {
    private val mapper = ObjectMapper()

    private fun jev(answer: (String) -> Double): Jev {
        val transport = object : JevTransport {
            override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
                val answers = mapper.createObjectNode()
                val ids = body.path("state").path("open").mapIndexed { i, q -> "u$i" to q.path("id").asText() }.toMap()
                body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", answer(ids.getValue(id))) }
                return CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev").set("answers", answers))
            }
            override fun close() {}
        }
        return Jev(TypeSafeJevClient.builder().apiKey("k").transport(transport).build())
    }

    private val spec = """
        # 인사

        ## 요구사항
        - REQ-1: greetAll(names)를 추가한다.

        ## 미결정 사항
        - Q1. 반환 형태는 목록인가, 합친 문자열인가?
        - **Q2.** 빈 목록이면 무엇을 돌려주는가?
        - [Q3] null 이면 어떻게 하는가?
        - Q4. 기존 greet 도 고치는가?
    """.trimIndent()

    @Test
    fun `미결정 질문에 문서의 번호를 ID로 쓰고 없거나 겹치면 순서로 붙이며 사용자가 답한 질문은 뺀다`() {
        val questions = UndecidedCheck.questions(spec)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4"), questions.map { it.id })
        assertEquals("Q1. 반환 형태는 목록인가, 합친 문자열인가?", questions[0].label)
        val positional = UndecidedCheck.questions("## 미결정 사항\n- 빈 목록이면?\n- Q1. null 이면?\n")
        assertEquals(listOf("Q1", "Q2"), positional.map { it.id })
        assertEquals(listOf("Q1. 빈 목록이면?", "Q2. Q1. null 이면?"), positional.map { it.label })
        assertEquals(listOf("Q2", "Q4"), UndecidedCheck.open(spec, listOf(UndecidedCheck.Decision("q1", "목록"), UndecidedCheck.Decision("Q3", "예외"), UndecidedCheck.Decision(null, "범위 허락"))).map { it.id })
        assertTrue(UndecidedCheck.questions("# 제목\n\n## 미결정 사항\n- 없음\n").isEmpty())
    }

    @Test
    fun `남은 미결정 질문만 JEV에 묻고 0점6 이상은 추측해 정함, 0점4 초과는 애매로 본다`() {
        val decisions = listOf(UndecidedCheck.Decision("Q1", "목록"))
        val open = UndecidedCheck.open(spec, decisions)
        val asked = mutableListOf<String>()
        val result = UndecidedCheck.judge(jev { id -> asked += id; when (id) { "Q2" -> 0.9; "Q3" -> 0.5; else -> 0.1 } }, spec, "IMPLEMENTATION", open, decisions, "+ if (names.isEmpty()) return List.of();")!!
        assertEquals(listOf("Q2", "Q3", "Q4"), asked.sorted())
        assertEquals(listOf("Q2"), result.decided.map { it.id })
        assertEquals(listOf("Q3"), result.uncertain.map { it.id })
        assertFalse(result.truncated)
        assertEquals(emptyList(), UndecidedCheck.judge(Jev(null), spec, "IMPLEMENTATION", emptyList(), decisions, "+ x")!!.decided)
        assertNull(UndecidedCheck.judge(Jev(null), spec, "IMPLEMENTATION", open, decisions, "+ x"), "JEV가 꺼졌는데 물을 질문이 있으면 판정 없음")
        assertTrue(UndecidedCheck.judge(jev { 0.1 }, spec, "TEST_CODE", open, decisions, "+ assert\n".repeat(30_000))!!.truncated)
    }
}
