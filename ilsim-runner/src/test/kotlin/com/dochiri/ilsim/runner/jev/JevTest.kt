package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.document.DocPurpose
import com.dochiri.ilsim.runner.document.DocumentFile
import com.dochiri.ilsim.runner.profile.StackDetector
import com.dochiri.ilsim.runner.risk.RiskOpinion
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskScanner
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.JevException
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JevTest {
    private val mapper = ObjectMapper()

    /** 요청한 질문 id 마다 [probability] 로 정한 확률을 TypeSafe 응답 형식으로 돌려주는 가짜 전송. */
    private class FakeTransport(val mapper: ObjectMapper, val fail: Boolean = false, val probability: (String) -> Double) : JevTransport {
        val requests = mutableListOf<JsonNode>()
        override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
            requests.add(body)
            if (fail) return CompletableFuture.failedFuture(JevException(JevException.Kind.SERVER, "boom", 500))
            val answers = mapper.createObjectNode()
            body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", probability(id)) }
            return CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev-latest").set("answers", answers))
        }
        override fun close() {}
    }

    private fun jev(transport: FakeTransport) = Jev(TypeSafeJevClient.builder().apiKey("k").transport(transport).build())

    @Test
    fun `키가 없으면 비활성이고 호출이 실패하면 null 이다`() {
        assertTrue(!Jev(null).enabled)
        assertNull(Jev(null).probabilities("s", emptyMap()))
        val q = io.github.gudcks0305.jev.NoulQuestion.of("a", "?")
        assertNull(jev(FakeTransport(mapper, fail = true) { 0.5 }).probability("s", q))
        assertEquals(0.7, jev(FakeTransport(mapper) { 0.7 }).probability("s", q))
    }

    private val docs = listOf(
        DocumentFile("docs/ARCHITECTURE.md", "# 아키텍처\n계층 구조와 의존 방향\n"),
        DocumentFile("docs/design-notes.md", "# Design\n회의 메모\n"),
        DocumentFile("README.md", "# 소개\n"),
    )

    @Test
    fun `JEV 가 관련 있다고 본 문서만 점수 순으로 고른다`() {
        val transport = FakeTransport(mapper) { id -> if (id == "d0") 0.93 else 0.2 }
        val refs = JevDocumentSelector(jev(transport)).select(DocPurpose.ARCHITECTURE, docs)
        assertEquals(listOf("docs/ARCHITECTURE.md"), refs.map { it.path })
        assertTrue(refs.single().reason.startsWith("JEV 관련도"))
        assertEquals(1, transport.requests.size, "후보 문서 전체를 한 번의 요청으로 묻는다")
    }

    @Test
    fun `JEV 를 쓸 수 없으면 정적 선택으로 되돌아간다`() {
        val heuristic = com.dochiri.ilsim.runner.document.HeuristicDocumentSelector().select(DocPurpose.ARCHITECTURE, docs).map { it.path }
        assertEquals(heuristic, JevDocumentSelector(Jev(null)).select(DocPurpose.ARCHITECTURE, docs).map { it.path })
        assertEquals(heuristic, JevDocumentSelector(jev(FakeTransport(mapper, fail = true) { 0.5 })).select(DocPurpose.ARCHITECTURE, docs).map { it.path })
    }

    private fun project(): Path {
        val root = Files.createTempDirectory("ilsim-jev")
        listOf("A", "B", "C").forEach {
            root.resolve("src/main/kotlin/com/x/$it.kt").also { f -> f.parent.createDirectories() }
                .writeText("package com.x\n\n@Service\nclass $it {\n    private var n = 0\n}\n")
        }
        return root
    }

    @Test
    fun `위험 후보는 JEV 가 확실한 것만 정하고 애매한 것만 Claude 가 본다`() {
        val root = project()
        val findings = RiskScanner.scan(root, StackDetector.detect(root))!!.sortedBy { it.file }
        assertEquals(3, findings.size)
        val probabilities = mapOf(findings[0].file to 0.05, findings[1].file to 0.95, findings[2].file to 0.5)
        val screen = { v: com.dochiri.ilsim.runner.architecture.Violation -> probabilities[v.file] }
        var claudeCalls = 0
        val result = RiskReviewer.review(root, findings, emptyMap(), "sonnet", false, screen) { prompt ->
            claudeCalls++
            assertTrue(prompt.contains(findings[2].file) && !prompt.contains(findings[0].file) && !prompt.contains(findings[1].file), "애매한 후보만 보낸다")
            """{"findings":[{"id":0,"verdict":"UNSURE","reason":"모름"}]}"""
        }
        assertEquals(1, claudeCalls)
        val byFile = findings.associate { it.file to result[RiskReviewer.fingerprint(root, it)] }
        assertEquals(RiskOpinion::class, byFile.getValue(findings[0].file)!!::class)
        assertEquals("FALSE" to "jev", byFile.getValue(findings[0].file)!!.let { it.verdict to it.reviewer })
        assertEquals("TRUE" to "jev", byFile.getValue(findings[1].file)!!.let { it.verdict to it.reviewer })
        assertEquals("UNSURE", byFile.getValue(findings[2].file)!!.verdict)
    }

    @Test
    fun `위험 판정은 코드 발췌를 담아 보내고 호출 상한을 지킨다`() {
        val root = project()
        val findings = RiskScanner.scan(root, StackDetector.detect(root))!!
        val transport = FakeTransport(mapper) { 0.5 }
        val screen = JevRiskScreen.of(jev(transport), root, maxCalls = 2)
        assertEquals(0.5, screen(findings[0]))
        assertEquals(0.5, screen(findings[1]))
        assertNull(screen(findings[2]), "상한을 넘으면 null 이라 Claude 가 본다")
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0].path("state").toString().contains("private var n"))
        assertNull(JevRiskScreen.of(Jev(null), root)(findings[0]))
    }
}
