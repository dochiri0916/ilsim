package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.document.DocSection
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.JevException
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidelineDocumentScoreTest {
    private val mapper = ObjectMapper()

    private class Transport(val mapper: ObjectMapper, val fail: Boolean = false, val probability: (String, JsonNode) -> Double) : JevTransport {
        val requests = java.util.Collections.synchronizedList(mutableListOf<JsonNode>())
        override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
            requests.add(body)
            if (fail) return CompletableFuture.failedFuture(JevException(JevException.Kind.SERVER, "boom", 500))
            val answers = mapper.createObjectNode()
            body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", probability(id, body)) }
            return CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev-latest").set("answers", answers))
        }
        override fun close() {}
    }

    private fun jev(transport: Transport) = Jev(TypeSafeJevClient.builder().apiKey("k").transport(transport).build())

    private fun g(path: String, relevance: Double, line: Int = 1) = Guideline(path, line, "h$line", "t$line", relevance)

    private fun score(scores: List<Guidelines.DocumentScore>, path: String) = scores.single { it.path == path }

    private val referenceSections = listOf(
        DocSection("AGENTS.md", 1, "작업 원칙", "tool 작업은 먼저 [참고서](tool/docs/ref.md)를 읽는다. [기록](docs/log.md)은 읽지 않는다."),
        DocSection("docs/log.md", 1, "기록", "기록"),
        DocSection("tool/docs/ref.md", 1, "참고", "명령 규칙"),
    )

    private fun referenceTransport() = Transport(mapper) { id, body ->
        val text = body.toString()
        when {
            id[0] in "km" && "\"path\":\"AGENTS.md\"" in text -> 0.9
            id[0] in "km" -> 0.1
            id == "q0" -> 0.9
            id == "q1" -> 0.1
            id == "n0" -> 0.1
            id == "n1" -> 0.9
            else -> 0.9
        }
    }

    @Test
    fun `TC-1 문서 최종 관련도는 구간 최고값이고 선택 수는 거름 기준을 넘는 구간 수다`() {
        val scores = Guidelines.documentScores(listOf(g("a.md", 0.1, 1), g("a.md", 0.8, 2), g("a.md", 0.3, 3)), mapOf("a.md" to 0.9))
        val a = score(scores, "a.md")
        assertEquals(0.8, a.relevance)
        assertEquals(2, a.selected)
        assertFalse(a.reinstated)
        assertEquals("  + 0.80 구간 2개 a.md", Guidelines.documentLine(a))
    }

    @Test
    fun `TC-2 거름에서 빠진 문서도 선택 0개로 출력하고 참조 표시는 없다`() {
        val scores = Guidelines.documentScores(listOf(g("a.md", 0.9), g("b.md", 0.1, 1), g("b.md", 0.1, 2)), mapOf("a.md" to 0.9, "b.md" to 0.1))
        val b = score(scores, "b.md")
        assertEquals(0, b.selected)
        assertFalse(b.reinstated)
        assertEquals("  - 0.10 구간 0개 b.md", Guidelines.documentLine(b))
    }

    @Test
    fun `TC-3 관련도가 정확히 0점2인 구간뿐이면 선택 0개다`() {
        val a = score(Guidelines.documentScores(listOf(g("a.md", 0.2, 1), g("a.md", 0.2, 2)), mapOf("a.md" to 0.9)), "a.md")
        assertEquals(0.2, a.relevance)
        assertEquals(0, a.selected)
        assertEquals("  - 0.20 구간 0개 a.md", Guidelines.documentLine(a))
    }

    @Test
    fun `TC-4 문서 선택 수 합은 relevant 구간 수와 같다`() {
        val scored = listOf(g("a.md", 0.9, 1), g("a.md", 0.5, 2), g("b.md", 0.3), g("c.md", 0.1), g("d.md", 0.21))
        val scores = Guidelines.documentScores(scored, scored.associate { it.path to 0.9 })
        assertEquals(Guidelines.relevant(scored).size, scores.sumOf { it.selected })
    }

    @Test
    fun `TC-5 구간이 있는 문서마다 한 줄씩 나온다`() {
        val scored = listOf(g("a.md", 0.9, 1), g("a.md", 0.5, 2), g("b.md", 0.3), g("c.md", 0.1))
        val scores = Guidelines.documentScores(scored, emptyMap())
        assertEquals(listOf("a.md", "b.md", "c.md"), scores.map { it.path }.sorted())
    }

    @Test
    fun `TC-6 최종 관련도는 소수 둘째 자리로 출력한다`() {
        val a = score(Guidelines.documentScores(listOf(g("a.md", 0.856)), mapOf("a.md" to 0.9)), "a.md")
        assertEquals("  + 0.86 구간 1개 a.md", Guidelines.documentLine(a))
    }

    @Test
    fun `TC-7 문서는 최종 관련도 내림차순이다`() {
        val scores = Guidelines.documentScores(listOf(g("a.md", 0.3), g("b.md", 0.9), g("c.md", 0.6)), emptyMap())
        assertEquals(listOf("b.md", "c.md", "a.md"), scores.map { it.path })
    }

    @Test
    fun `TC-8 관련도가 같으면 경로 오름차순이고 순서가 같다`() {
        val scored = listOf(g("b.md", 0.5), g("a.md", 0.5))
        assertEquals(listOf("a.md", "b.md"), Guidelines.documentScores(scored, emptyMap()).map { it.path })
        assertEquals(Guidelines.documentScores(scored, emptyMap()), Guidelines.documentScores(scored, emptyMap()))
    }

    @Test
    fun `TC-9 거름에서 빠졌다가 참조로 다시 들어온 문서에 참조 표시가 붙는다`() {
        val selection = Guidelines.selection(jev(referenceTransport()), "tool 기능 추가", referenceSections)!!
        val scores = Guidelines.documentScores(selection.guidelines, selection.filtered)
        val ref = score(scores, "tool/docs/ref.md")
        assertEquals(1, ref.selected)
        assertEquals(0.9, ref.relevance)
        assertTrue(ref.reinstated)
        assertEquals("  + 0.90 구간 1개 tool/docs/ref.md 참조", Guidelines.documentLine(ref))
        val agents = score(scores, "AGENTS.md")
        assertFalse(agents.reinstated)
        assertFalse(Guidelines.documentLine(agents).endsWith("참조"))
    }

    @Test
    fun `TC-10 참조가 읽지 말라고 한 문서는 0점이고 참조 표시가 없다`() {
        val selection = Guidelines.selection(jev(referenceTransport()), "tool 기능 추가", referenceSections)!!
        val log = score(Guidelines.documentScores(selection.guidelines, selection.filtered), "docs/log.md")
        assertEquals(0.0, log.relevance)
        assertEquals(0, log.selected)
        assertFalse(log.reinstated)
        assertEquals("  - 0.00 구간 0개 docs/log.md", Guidelines.documentLine(log))
    }

    @Test
    fun `TC-11 거름 점수가 정확히 0점2인 문서가 선택되면 참조 표시가 붙는다`() {
        val a = score(Guidelines.documentScores(listOf(g("a.md", 0.7)), mapOf("a.md" to 0.2)), "a.md")
        assertTrue(a.reinstated)
        assertTrue(Guidelines.documentLine(a).endsWith(" 참조"))
    }

    @Test
    fun `TC-12 거름 점수 0점21로 통과한 문서는 참조 표시가 없다`() {
        val a = score(Guidelines.documentScores(listOf(g("a.md", 0.21)), mapOf("a.md" to 0.21)), "a.md")
        assertEquals(1, a.selected)
        assertFalse(a.reinstated)
    }

    @Test
    fun `TC-13 거름에서 빠지고 선택되지 않은 문서는 참조 표시가 없다`() {
        val a = score(Guidelines.documentScores(listOf(g("a.md", 0.1)), mapOf("a.md" to 0.1)), "a.md")
        assertEquals(0, a.selected)
        assertFalse(a.reinstated)
    }

    @Test
    fun `TC-14 거름 점수가 없는 문서는 통과로 보고 참조 표시가 없다`() {
        val a = score(Guidelines.documentScores(listOf(g("a.md", 0.7)), emptyMap()), "a.md")
        assertEquals(1, a.selected)
        assertFalse(a.reinstated)
    }

    @Test
    fun `TC-15 문서 거름은 select 한 번 안에서 문서마다 한 번만 묻고 그 답을 거름 점수로 쓴다`() {
        val sections = listOf(DocSection("a.md", 1, "규칙", "규칙"), DocSection("b.md", 1, "규칙", "규칙"))
        val transport = Transport(mapper) { id, body ->
            val a = "\"path\":\"a.md\"" in body.path("state").toString()
            when (id) {
                "k0" -> if (a) 0.7 else 0.6
                "m0" -> if (a) 0.9 else 0.8
                else -> 0.9
            }
        }
        val selection = Guidelines.selection(jev(transport), "기능 추가", sections)!!
        assertEquals(2, transport.requests.count { it.path("questions").has("k0") })
        assertEquals(mapOf("a.md" to 0.7, "b.md" to 0.6), selection.filtered)
    }

    @Test
    fun `TC-16 참조 표시에 쓰는 거름 점수는 참조 반영 전 값이다`() {
        val selection = Guidelines.selection(jev(referenceTransport()), "tool 기능 추가", referenceSections)!!
        assertEquals(0.1, selection.filtered["tool/docs/ref.md"])
        assertEquals(0.9, selection.filtered["AGENTS.md"])
    }

    @Test
    fun `TC-17 TC-21 guidelines 명령은 문서 거름을 따로 묻지 않고 문서 거름 줄을 출력하지 않는다`() {
        val main = Files.readString(Path.of("src/main/kotlin/com/dochiri/ilsim/runner/Main.kt"))
        val body = main.substringAfter("private fun guidelines(").substringBefore("\nprivate fun ")
        assertFalse("Guidelines.documents(" in body)
        assertFalse("문서 거름" in body)
        assertTrue("Guidelines.selection(" in body)
        assertTrue("Guidelines.documentLine" in body)
        assertTrue("지시에 넣는 분량" in body)
        assertTrue("Guidelines.check(" in body)
    }

    @Test
    fun `TC-18 JEV 전송이 실패하면 null 이다`() {
        assertNull(Guidelines.selection(jev(Transport(mapper, fail = true) { _, _ -> 0.9 }), "기능 추가", referenceSections))
    }

    @Test
    fun `TC-19 TC-20 기존 select 는 같은 구간 점수와 요청 수를 유지하고 selection 과 같은 구간 점수를 준다`() {
        val sections = listOf("배경", "컨벤션", "다른 기능").map { DocSection("a.md", 1, it, it) } + DocSection("other/b.md", 1, "다른 모듈", "다른 모듈 규칙")
        val answers = mapOf("k0" to 0.9, "m0" to 0.9, "r0" to 0.1, "s0" to 0.9, "r1" to 0.9, "s1" to 0.8, "r2" to 0.9, "s2" to 0.3)
        fun transport() = Transport(mapper) { id, body -> if (id == "m0" && "other/b.md" in body.toString()) 0.1 else answers.getValue(id) }
        val plain = transport()
        val scored = Guidelines.select(jev(plain), "작별 인사 추가", sections)!!
        assertEquals(listOf(0.1, 0.8, 0.3, 0.1), scored.map { it.relevance })
        assertEquals(3, plain.requests.size)
        val withDocuments = transport()
        val selection = Guidelines.selection(jev(withDocuments), "작별 인사 추가", sections)!!
        assertEquals(scored, selection.guidelines)
        assertEquals(3, withDocuments.requests.size)
    }
}
