package com.dochiri.ilsim.runner.jev

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JevGuidelineEvalTest {
    private val corpus = Path.of("corpus/jev-guideline")

    private fun jev(answer: (String) -> Double): Jev {
        val mapper = ObjectMapper()
        val transport = object : JevTransport {
            override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
                val answers = mapper.createObjectNode()
                body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", answer(id)) }
                return CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev").set("answers", answers))
            }
            override fun close() {}
        }
        return Jev(TypeSafeJevClient.builder().apiKey("k").transport(transport).build())
    }

    @Test
    fun `평가 세트의 모든 구간에 정답이 있고 변경·참조·문서 규칙 사례가 실제 구간을 가리킨다`() {
        val projects = JevGuidelineEval.load(corpus)

        assertEquals(listOf("ilsim", "sandbox", "shop", "webapp"), projects.map { it.name })
        assertEquals(emptyList(), projects.flatMap(JevGuidelineEval::problems))
        assertEquals(63, projects.sumOf { it.corpus.diffs.size })
        assertEquals(21, projects.sumOf { it.corpus.rules.size })
        assertTrue(projects.all { p -> p.corpus.diffs.all { p.diffs.getValue(it.id).startsWith("diff --git") } })
    }

    @Test
    fun `운영 질문으로 질문별 관측을 모으고 기준별 놓침과 오경보를 센다`() {
        val projects = JevGuidelineEval.load(corpus)
        val observations = JevGuidelineEval.run(projects, 1) { jev { id -> if (id.startsWith("v") || id.startsWith("n")) 0.1 else 0.9 } }
        val byQuestion = observations.groupBy { it.question }

        assertEquals(63, byQuestion.getValue("diff").size)
        assertEquals(21, byQuestion.getValue("f").size)
        assertEquals(9, byQuestion.getValue("q").size)
        assertTrue(listOf("w", "k", "m", "r", "s", "sel", "v", "v-e2e", "gate").all { byQuestion[it].orEmpty().isNotEmpty() })
        assertTrue(observations.all { it.p != null })
        val diff = JevGuidelineEval.questions.single { it.id == "diff" }
        val score = JevGuidelineEval.score(byQuestion.getValue("diff"), diff.threshold, diff.strict)
        assertEquals(byQuestion.getValue("diff").count { it.expected }, score.fn)
        assertEquals(0, score.fp)
        val report = JevGuidelineEval.report(projects, observations)
        assertContains(report, "sel 최종 선택(관련도)")
        assertContains(report, "diff 변경 차단")
    }

    @Test
    fun `제안 기준은 놓침을 막는 질문이면 놓침 0인 가장 높은 기준, 오경보를 막는 질문이면 오경보 0인 가장 낮은 기준이다`() {
        fun o(expected: Boolean, p: Double) = Observation("x", "p", "i$p", expected, p, 1)
        val obs = listOf(o(true, 0.9), o(true, 0.45), o(false, 0.3), o(false, 0.7))
        val recall = JevGuidelineEval.Question("x", "", 0.2, true, JevGuidelineEval.Goal.RECALL)
        val precision = JevGuidelineEval.Question("x", "", 0.8, false, JevGuidelineEval.Goal.PRECISION)

        assertEquals(0.40, JevGuidelineEval.suggest(obs, recall)!!, 1e-9)
        assertEquals(0.75, JevGuidelineEval.suggest(obs, precision)!!, 1e-9)
        val s = JevGuidelineEval.score(obs, 0.5, false)
        assertEquals(listOf(1, 1, 1, 1), listOf(s.tp, s.fp, s.fn, s.tn))
    }
}
