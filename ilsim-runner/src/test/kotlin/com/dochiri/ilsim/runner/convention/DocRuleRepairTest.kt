package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.architecture.Evidence
import com.dochiri.ilsim.runner.architecture.RuleReview
import com.dochiri.ilsim.runner.brief.RulesBrief
import com.dochiri.ilsim.runner.document.DocSection
import com.dochiri.ilsim.runner.jev.Jev
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocRuleRepairTest {
    private val mapper = ObjectMapper()

    private fun jev(answer: (String, JsonNode) -> Double): Jev {
        val transport = object : JevTransport {
            override fun post(uri: URI, headers: Map<String, String>, body: JsonNode): CompletableFuture<JsonNode> {
                val answers = mapper.createObjectNode()
                body.path("questions").fieldNames().forEach { id -> answers.putObject(id).put("type", "noul").put("noul", answer(id, body)) }
                return CompletableFuture.completedFuture(mapper.createObjectNode().put("model", "jev").set("answers", answers))
            }
            override fun close() {}
        }
        return Jev(TypeSafeJevClient.builder().apiKey("k").transport(transport).build())
    }

    private val sources = mapOf(
        "src/main/kotlin/com/x/web/OrderController.kt" to """
            package com.x.web

            @PreAuthorize("hasRole('A')")
            @RestController
            class OrderController {
                @GetMapping
                fun list(): List<String> = emptyList()
            }
        """,
        "src/main/kotlin/com/x/web/ItemController.kt" to """
            package com.x.web

            @PreAuthorize("hasRole('B')")
            @RestController
            class ItemController {
                @GetMapping
                fun items(): List<String> = emptyList()
            }
        """,
        "src/main/kotlin/com/x/service/OrderService.kt" to """
            package com.x.service

            @Service
            class OrderService {
                fun place(): Int {
                    return 1
                }
            }
        """,
        "src/test/kotlin/com/x/OrderTest.kt" to """
            package com.x

            class OrderTest {
                @Test
                fun `주문을 만든다`() {
                    // given
                    val a = 1
                    // when
                    val b = a + 1
                    // then
                    assert(b == 2)
                }
            }
        """,
    )

    private fun facts(): Facts = Facts(sources.map { (path, text) -> FactScanner.parse(path, path.substringAfterLast('.'), "src/test/" in path, text.trimIndent().lines())!! })

    private fun rule(id: String, kind: String, params: Map<String, List<String>>, approved: Boolean = true, decidedBy: String? = "ai-review") =
        ConventionRule(id, kind, id, DocProbes.normalize(kind, params), approved = approved, decidedBy = decidedBy)

    @Test
    fun `자체 시험은 위치·이름·선언 규칙마다 검사기가 보는 대상을 바꿔 위반을 심고, 대상이 없으면 이유를 남긴다`() {
        val rules = listOf(
            rule("loc", "doc-file-location", mapOf("scope" to listOf("main"), "name" to listOf(".*Controller"), "location" to listOf("**/web/**"))),
            rule("facade", "doc-file-location", mapOf("scope" to listOf("main"), "name" to listOf(".*Facade"), "location" to listOf("**/facade/**"))),
            rule("service-name", "doc-name-pattern", mapOf("scope" to listOf("main"), "target" to listOf("class"), "when" to listOf("Service"), "pattern" to listOf(".*Service"))),
            rule("test-file", "doc-name-pattern", mapOf("scope" to listOf("test"), "target" to listOf("file"), "pattern" to listOf(".*Test"))),
            rule("korean-test", "doc-name-pattern", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "pattern" to listOf("`[가-힣 ]+`"))),
            rule("gwt", "doc-required-code", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "text" to listOf("raw"), "pattern" to listOf("""//\s*given""", """//\s*when""", """//\s*then"""))),
            rule("lines", "doc-max-size", mapOf("scope" to listOf("main"), "target" to listOf("method"), "measure" to listOf("lines"), "max" to listOf("20"))),
            rule("nesting", "doc-max-size", mapOf("scope" to listOf("main"), "target" to listOf("method"), "measure" to listOf("nesting"), "max" to listOf("2"))),
            rule("asserts", "doc-max-occurrences", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "pattern" to listOf("""assert\("""), "max" to listOf("1"))),
            rule("permission", "doc-required-annotation", mapOf("scope" to listOf("main"), "target" to listOf("method"), "when" to listOf("GetMapping"), "annotation" to listOf("PreAuthorize"), "inherit" to listOf("class"))),
            rule("forbid-service", "doc-forbidden-annotation", mapOf("scope" to listOf("main"), "target" to listOf("method"), "annotation" to listOf("Transactional"))),
            rule("nested", "doc-required-annotation", mapOf("scope" to listOf("test"), "target" to listOf("test-class"), "when" to listOf("Nested"), "annotation" to listOf("DisplayName"))),
        )

        val r = ConventionSelfTest.run(facts(), rules)

        assertEquals(rules.map { it.id } - "nested", r.caught, "못 잡음 ${r.missed} 위반 못 만듦 ${r.untested} ${r.reasons}")
        assertEquals(listOf("nested"), r.untested)
        assertContains(r.reasons.getValue("nested"), "대상")
        assertEquals(mapOf("nested" to r.reasons.getValue("nested")), r.unverified)
    }

    @Test
    fun `클래스 표시를 허용하는 규칙은 메서드나 그 클래스에 붙어 있으면 통과하고 메서드 대상에만 쓴다`() {
        val inherit = rule("p", "doc-required-annotation", mapOf("scope" to listOf("main"), "target" to listOf("method"), "when" to listOf("GetMapping"), "annotation" to listOf("PreAuthorize"), "inherit" to listOf("class")))
        val strict = rule("s", "doc-required-annotation", mapOf("scope" to listOf("main"), "target" to listOf("method"), "when" to listOf("GetMapping"), "annotation" to listOf("PreAuthorize")))

        assertTrue(RequiredAnnotation.samples(facts(), inherit.params).all { it.ok })
        assertEquals(2, RequiredAnnotation.samples(facts(), strict.params).count { !it.ok }, "inherit 없이 메서드마다 요구하면 클래스 표시는 위반이다")
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-required-annotation", mapOf("scope" to listOf("main"), "target" to listOf("class"), "annotation" to listOf("A"), "inherit" to listOf("class"))) }
    }

    @Test
    fun `함께 지킬 수 없는 규칙은 금지 규칙을 남기고 필요 규칙을 끄며 사용자가 정한 규칙은 그대로 둔다`() {
        val gwt = rule("doc:gwt", "doc-required-code", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "text" to listOf("raw"), "pattern" to listOf("""//\s*given""", """//\s*when""", """//\s*then""")))
        val ban = rule("doc:ban", "doc-forbidden-code", mapOf("scope" to listOf("all"), "text" to listOf("raw"), "pattern" to listOf("//")))
        val sleep = rule("doc:sleep", "doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("""Thread\.sleep""")))

        val (auto, conflicts) = DocRules.resolve(listOf(gwt, ban, sleep))
        assertEquals(listOf(false, true, true), auto.map { it.approved }, "AI가 정한 규칙끼리는 금지 규칙을 남긴다")
        assertEquals(listOf(DocRules.RuleConflict("doc:ban", "doc:gwt", conflicts.single().reason)), conflicts)
        assertContains(conflicts.single().reason, "금지 규칙을 남기고 필요 규칙은 따르지 않는다")

        val (user, kept) = DocRules.resolve(listOf(gwt.copy(decidedBy = "user"), ban, sleep))
        assertEquals(listOf(true, false, true), user.map { it.approved }, "사용자가 승인한 필요 규칙이 있으면 AI가 정한 금지 규칙을 끈다")
        assertEquals("doc:ban", kept.single().dropped)
        val (both, noted) = DocRules.resolve(listOf(gwt.copy(decidedBy = "user"), ban.copy(decidedBy = "user")))
        assertEquals(listOf(true, true), both.map { it.approved })
        assertEquals("", noted.single().dropped)

        val mainComment = rule("doc:why", "doc-required-code", mapOf("scope" to listOf("main"), "target" to listOf("method"), "text" to listOf("raw"), "pattern" to listOf("""//\s*why""")))
        val noComments = ConventionRule("no-comments", "no-comments", "주석을 쓰지 않는다", approved = true)
        assertEquals(listOf("doc:why" to "no-comments"), DocRules.conflicts(listOf(mainComment), listOf(noComments)), "컨벤션 규칙과의 충돌도 찾는다")
        assertEquals(emptyList(), DocRules.conflicts(listOf(gwt), listOf(noComments)), "주석 금지 컨벤션은 프로덕트 코드만 보므로 테스트 주석 규칙과 충돌하지 않는다")

        val model = DocRules.DocRuleModel(auto, conflicts = conflicts)
        assertContains(DocRules.report(model), "자동 끔: 규칙 충돌, doc:ban를 남김")
        assertContains(RulesBrief.build(null, null, documented = model)!!, "### 함께 지킬 수 없어 한쪽만 따르는 문서 규칙")
    }

    @Test
    fun `기존 위반이 많고 다시 확인해도 애매한 규칙은 반례를 주고 그 문장만 다시 옮겨 원문과 맞으면 바꾼다`() {
        val root = Files.createTempDirectory("ilsim-doc-repair")
        sources.forEach { (path, text) -> root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent()) }
        val facts = facts()
        val quote = "권한은 컨트롤러 클래스나 메서드에 @PreAuthorize 로 표시한다."
        val section = DocSection("docs/api.md", 3, "API > 권한", "## 권한\n$quote\n")
        val strict = DocRules.evidence(ConventionRule("doc:strict", "doc-required-annotation", "GET API 메서드마다 @PreAuthorize 를 붙인다",
            DocProbes.normalize("doc-required-annotation", mapOf("scope" to listOf("main"), "target" to listOf("method"), "when" to listOf("GetMapping"), "annotation" to listOf("PreAuthorize"))),
            review = RuleReview("KEEP", "JEV 원문 일치 0.55"), origin = DocOrigin("docs/api.md", 3, "API > 권한", quote, "h")), facts)
        assertEquals(Evidence(0, 2, strict.evidence.examples), strict.evidence)
        val extracted = DocRules.Extracted(listOf(strict), listOf(DocCoverage("docs/api.md", 3, "API > 권한", "h", listOf("doc:strict"))), emptyList())
        val prompts = mutableListOf<String>()
        val fixed = """{"sections":[{"id":"s0","rules":[{"kind":"doc-required-annotation","params":{"scope":["main"],"target":["method"],"when":["GetMapping"],"annotation":["PreAuthorize"],"inherit":["class"]},"description":"GET API 메서드나 그 클래스에 @PreAuthorize 를 붙인다","quote":"$quote"}],"remaining":[]}]}"""

        val repaired = DocRules.repair(jev { _, _ -> 0.9 }, root, facts, listOf(section), extracted, mapOf("doc:strict" to 0.48), emptyList()) { prompts += it; fixed }

        assertContains(prompts.single(), "다시 옮기기")
        assertContains(prompts.single(), "@PreAuthorize(\"hasRole('A')\")", message = "반례에 타입 머리를 함께 준다")
        val replaced = repaired.rules.single()
        assertEquals(listOf("class"), replaced.params["inherit"])
        assertEquals(Evidence(2, 2), replaced.evidence.copy(examples = emptyList()))
        assertContains(replaced.review!!.reason, "반례로 다시 옮김")
        assertEquals(listOf(replaced.id), repaired.documents.single().rules, "구간 기록도 새 규칙으로 바꿔 다음 분석에서 이어받는다")
        assertEquals(listOf("doc:strict"), repaired.attempted)
        assertTrue(DocRules.carryOver(null, repaired).rules.single().approved)

        var calls = 0
        val failed = DocRules.repair(jev { _, _ -> 0.9 }, root, facts, listOf(section), extracted, mapOf("doc:strict" to 0.48), emptyList()) { calls++; """{"sections":[{"id":"s0","rules":[],"remaining":["$quote"]}]}""" }
        assertEquals(listOf("doc:strict"), failed.rules.map { it.id })
        assertTrue(failed.notes.single().contains("얻지 못했습니다"))
        DocRules.repair(jev { _, _ -> 0.9 }, root, facts, listOf(section), extracted, mapOf("doc:strict" to 0.48), failed.attempted) { calls++; "" }
        assertEquals(1, calls, "한 번 시도한 규칙은 다시 Claude를 부르지 않는다")
        DocRules.repair(jev { _, _ -> 0.9 }, root, facts, listOf(section), extracted, mapOf("doc:strict" to 0.7), emptyList()) { calls++; "" }
        assertEquals(1, calls, "다시 확인에서 원문을 어긴다고 본 규칙은 다시 옮기지 않는다")
    }
}
