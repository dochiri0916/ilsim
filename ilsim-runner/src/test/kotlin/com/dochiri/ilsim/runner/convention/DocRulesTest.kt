package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.AssignedTask
import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.ProjectEntry
import com.dochiri.ilsim.runner.RunnerConfig
import com.dochiri.ilsim.runner.Shell
import com.dochiri.ilsim.runner.StageGates
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.brief.RulesBrief
import com.dochiri.ilsim.runner.document.DocSection
import com.dochiri.ilsim.runner.jev.Jev
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.gudcks0305.jev.spi.JevTransport
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocRulesTest {
    private val mapper = ObjectMapper()

    private val sources = mapOf(
        "src/test/kotlin/com/x/order/OrderTest.kt" to """
            package com.x.order

            import org.assertj.core.api.Assertions.assertThat
            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Disabled
            import org.junit.jupiter.api.DisplayName
            import org.junit.jupiter.api.Test

            class OrderTest {
                @Test
                @DisplayName("주문을 만든다")
                fun create() {
                    // given
                    val order = Order(1)
                    // when
                    val total = order.total()
                    // then
                    assertThat(total).isEqualTo(1)
                }

                @Disabled
                @Test
                fun cancel() {
                    // when
                    Thread.sleep(10)
                    // given
                    assertEquals(1, 1)
                    assertThat(1).isEqualTo(1)
                    assertThat("Thread.sleep(1)").isNotEmpty()
                }
            }
        """,
        "src/test/kotlin/com/x/OrderFixture.kt" to """
            package com.x

            object OrderFixture
        """,
        "src/test/kotlin/com/x/fixture/UserFixture.kt" to """
            package com.x.fixture

            object UserFixture
        """,
        "src/main/kotlin/com/x/domain/Order.kt" to """
            package com.x.domain

            import org.springframework.stereotype.Component

            class Order(private val amount: Int) {
                fun total(): Int {
                    if (amount > 0) {
                        if (amount > 10) {
                            return amount
                        }
                    }
                    return 0
                }
            }
        """,
    )

    private val facts = Facts(sources.map { (path, text) -> FactScanner.parse(path, path.substringAfterLast('.'), "src/test/" in path, text.trimIndent().lines())!! })

    private fun failures(kind: String, params: Map<String, List<String>>): List<String> =
        DocProbes.byKind(kind)!!.samples(facts, DocProbes.normalize(kind, params)).filter { !it.ok }.map { it.message }

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

    private val writing = DocSection("docs/testing.md", 3, "테스트 > 작성", """
        ## 작성
        - 모든 테스트 메서드에 @DisplayName 을 붙인다.
        - 단언은 AssertJ 만 쓴다. JUnit Assertions 는 쓰지 않는다.
        - 한 테스트는 한 행위만 검증한다.
    """.trimIndent())

    private val intro = DocSection("docs/testing.md", 1, "테스트", "# 테스트\n이 문서는 테스트 작성 방법을 소개한다.")

    private val documentJev = jev { id, body ->
        when (id.first()) {
            'k' -> 0.9
            'r' -> if ("작성" in body.path("state").path("sections").path(id.drop(1).toInt()).path("heading").asText()) 0.9 else 0.1
            'f' -> if ("잘못" in body.path("state").path("candidates").path(id.drop(1).toInt()).path("description").asText()) 0.1 else 0.95
            else -> 0.5
        }
    }

    private val answer = """
        설명 없이 JSON:
        {"sections":[{"id":"s0","rules":[
          {"kind":"doc-required-annotation","params":{"scope":["test"],"target":["test-method"],"annotation":["@org.junit.jupiter.api.DisplayName"]},"description":"모든 테스트 메서드에 @DisplayName 을 붙인다","quote":"모든 테스트 메서드에 @DisplayName 을 붙인다."},
          {"kind":"doc-forbidden-import","params":{"scope":"test","import":["^org\\.junit\\.jupiter\\.api\\.Assertions"]},"description":"JUnit Assertions 를 import 하지 않는다","quote":"JUnit Assertions 는 쓰지 않는다"},
          {"kind":"doc-forbidden-code","params":{"scope":["test"],"pattern":["assertEquals"]},"description":"잘못 옮긴 규칙","quote":"단언은 AssertJ 만 쓴다."},
          {"kind":"doc-forbidden-code","params":{"scope":["test"],"pattern":["x"]},"description":"지어낸 규칙","quote":"문서에 없는 문장"},
          {"kind":"doc-unknown","params":{"scope":["test"]},"description":"모르는 종류","quote":"한 테스트는 한 행위만 검증한다."}
        ],"remaining":["한 테스트는 한 행위만 검증한다."]}]}
    """.trimIndent()

    @Test
    fun `문서 규칙 종류는 코드에서 위반을 찾는다`() {
        assertEquals(listOf("금지된 import org.junit.jupiter.api.Assertions.assertEquals"), failures("doc-forbidden-import", mapOf("scope" to listOf("test"), "import" to listOf("^org\\.junit\\.jupiter\\.api\\.Assertions"))))
        assertEquals(listOf("cancel 에 @DisplayName 가 없습니다"), failures("doc-required-annotation", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "annotation" to listOf("@DisplayName"))))
        assertEquals(1, failures("doc-forbidden-annotation", mapOf("scope" to listOf("test"), "annotation" to listOf("Disabled"))).size)
        assertEquals(listOf("이름 `UserFixture` 이 정해진 형식과 다릅니다"), failures("doc-name-pattern", mapOf("scope" to listOf("test"), "path" to listOf("**/fixture/**"), "target" to listOf("class"), "pattern" to listOf("Order.*"))))
        assertEquals(1, failures("doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("""Thread\.sleep\("""))).size, "문자열 안 글자는 코드가 아니다")
        assertEquals(listOf("cancel 에 필요한 내용이 없거나 순서가 다릅니다"), failures("doc-required-code", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "text" to listOf("raw"), "pattern" to listOf("// given", "// when", "// then"))))
        assertEquals(listOf("src/test/kotlin/com/x/OrderFixture.kt 는 **/fixture/** 에 있어야 합니다"), failures("doc-file-location", mapOf("scope" to listOf("test"), "name" to listOf(".*Fixture"), "location" to listOf("**/fixture/**"))))
        assertEquals(1, failures("doc-max-size", mapOf("scope" to listOf("main"), "target" to listOf("method"), "measure" to listOf("nesting"), "max" to listOf("1"))).size)
        assertEquals(0, failures("doc-max-size", mapOf("scope" to listOf("main"), "target" to listOf("method"), "measure" to listOf("lines"), "max" to listOf("10"))).size)
        assertEquals(listOf("cancel 에 2번 있습니다(상한 1)"), failures("doc-max-occurrences", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "pattern" to listOf("assertThat"), "max" to listOf("1"))))
    }

    @Test
    fun `규칙 값은 검사 전에 정리하고 맞지 않으면 거절한다`() {
        assertEquals(listOf("DisplayName"), DocProbes.normalize("doc-required-annotation", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "annotation" to listOf("@org.junit.jupiter.api.DisplayName")))["annotation"])
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("x"), "exclude" to listOf("y"))) }
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-forbidden-code", mapOf("pattern" to listOf("x"))) }
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("("))) }
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-required-annotation", mapOf("scope" to listOf("test"), "annotation" to listOf("A"), "when" to listOf("Test"))) }
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-max-size", mapOf("scope" to listOf("main"), "target" to listOf("file"), "measure" to listOf("nesting"), "max" to listOf("1"))) }
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-nothing", mapOf("scope" to listOf("main"))) }
    }

    @Test
    fun `규칙 구간만 Claude가 옮기고 JEV가 원문과 다르다고 본 규칙과 지어낸 인용은 뺀다`() {
        val prompts = mutableListOf<String>()
        val extracted = DocRules.extract(listOf(intro, writing), facts, null, documentJev) { prompts += it; answer }

        assertEquals(1, prompts.size)
        assertTrue("테스트 작성 방법을 소개한다" !in prompts.single() && "JUnit Assertions 는 쓰지 않는다" in prompts.single())
        assertEquals(listOf("doc-forbidden-import", "doc-required-annotation"), extracted.rules.map { it.kind }.sorted())
        val annotation = extracted.rules.single { it.kind == "doc-required-annotation" }
        assertTrue(annotation.id.startsWith(DocRules.PREFIX) && annotation.severity == "BLOCK" && !annotation.approved)
        assertEquals(listOf("DisplayName"), annotation.params["annotation"])
        assertEquals("KEEP", annotation.review?.verdict)
        assertEquals("docs/testing.md", annotation.origin?.path)
        assertEquals(1 to 2, annotation.evidence.compliant to annotation.evidence.total)
        val coverage = extracted.documents.single()
        assertEquals(extracted.rules.map { it.id }.toSet(), coverage.rules.toSet())
        assertEquals(setOf("한 테스트는 한 행위만 검증한다.", "단언은 AssertJ 만 쓴다."), coverage.remaining.toSet())
        assertTrue(extracted.notes.any { "원문과 다르다고" in it } && extracted.notes.any { "찾을 수 없는 인용" in it } && extracted.notes.any { "형식이 맞지 않아" in it })
    }

    @Test
    fun `바뀌지 않은 구간은 다시 옮기지 않고 승인 상태와 함께 유지한다`() {
        val first = DocRules.extract(listOf(intro, writing), facts, null, documentJev) { answer }
        val approved = first.rules.map { it.copy(approved = true, decidedBy = "user") }
        val previous = DocRules.DocRuleModel(approved, first.documents)
        val moved = writing.copy(line = 9)

        val again = DocRules.extract(listOf(intro, moved), facts, previous, documentJev) { error("Claude를 다시 부르면 안 된다") }

        assertEquals(approved.map { it.id }.toSet(), again.rules.map { it.id }.toSet())
        assertTrue(again.rules.all { it.approved && it.origin?.line == 9 })
        assertEquals(9, again.documents.single().line)
    }

    @Test
    fun `JEV를 쓸 수 없으면 이전 문서 규칙을 그대로 둔다`() {
        val first = DocRules.extract(listOf(writing), facts, null, documentJev) { answer }
        val previous = DocRules.DocRuleModel(first.rules, first.documents)

        val kept = DocRules.extract(listOf(writing), facts, previous, Jev(null)) { error("호출하면 안 된다") }

        assertEquals(first.rules.map { it.id }, kept.rules.map { it.id })
        assertContains(kept.notes.single(), "JEV")
    }

    @Test
    fun `제출 검사는 문서 규칙의 기존 위반은 두고 이번 변경이 만든 위반만 막는다`() {
        val root = Files.createTempDirectory("ilsim-doc-rule")
        val path = "src/test/kotlin/com/x/SleepTest.kt"
        val before = "package com.x\n\nclass SleepTest {\n    fun a() {\n        Thread.sleep(1)\n    }\n}\n"
        val after = "package com.x\n\nclass SleepTest {\n    fun a() {\n        Thread.sleep(1)\n    }\n\n    fun b() {\n        Thread.sleep(2)\n    }\n}\n"
        root.resolve(path).also { it.parent.createDirectories() }.writeText(after)
        val rule = ConventionRule("doc:sleep", "doc-forbidden-code", "테스트에서 Thread.sleep 을 쓰지 않는다", DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("""Thread\.sleep"""))),
            approved = true, origin = DocOrigin("docs/testing.md", 7, "테스트", "Thread.sleep 을 쓰지 않는다", "h"))
        val model = DocRules.DocRuleModel(listOf(rule))
        val current = Facts(listOf(FactScanner.parse(path, "kt", true, after.lines())!!))
        val violations = ConventionChecker.check(current, DocRules.asConvention(model), approvedOnly = true)

        val introduced = DocRules.introduced(root, model, violations) { if (it == path) before.lines() else null }

        assertEquals(2, violations.size)
        assertEquals(listOf(9), introduced.map { it.line })
        assertContains(introduced.single().message, "테스트에서 Thread.sleep 을 쓰지 않는다")
        assertContains(introduced.single().message, "docs/testing.md:7")
        assertEquals(2, DocRules.introduced(root, model, violations) { null }.size, "새 파일이면 모두 새 위반이다")
    }

    @Test
    fun `제출 검사는 커밋된 코드와 비교해 문서 규칙의 새 위반만 막는다`() {
        val home = Files.createTempDirectory("ilsim-doc-gate-home")
        val repo = Files.createTempDirectory("ilsim-doc-gate-repo").toRealPath()
        Shell.gitOk(repo, "init", "-q", "-b", "main")
        repo.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
        repo.resolve("build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }\n")
        repo.resolve("src/main/kotlin/com/x/A.kt").also { it.parent.createDirectories() }.writeText("package com.x\n\nclass A\n")
        val test = repo.resolve("src/test/kotlin/com/x/SleepTest.kt").also { it.parent.createDirectories() }
        test.writeText("package com.x\n\nclass SleepTest {\n    fun a() {\n        Thread.sleep(1)\n    }\n}\n")
        Shell.gitOk(repo, "add", "-A")
        Shell.gitOk(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base")
        test.writeText("package com.x\n\nclass SleepTest {\n    fun a() {\n        Thread.sleep(1)\n    }\n\n    fun b() {\n        Thread.sleep(2)\n    }\n}\n")
        val store = ConfigStore(home.resolve(".ilsim/runner.json"))
        store.save(RunnerConfig(runnerId = "r1", projects = mapOf("p" to ProjectEntry(repo.toString(), "demo"))))
        val rule = ConventionRule("doc:sleep", "doc-forbidden-code", "테스트에서 Thread.sleep 을 쓰지 않는다", DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("""Thread\.sleep"""))),
            approved = true, origin = DocOrigin("docs/testing.md", 7, "테스트", "q", "h"))
        DocRuleStore(store).save("p", DocRules.DocRuleModel(listOf(rule)))
        val logs = mutableListOf<String>()
        val gates = StageGates(store, ArchitectureStore(store), ConventionStore(store), { _, _ -> "" }, logs::add)
        val validation = linkedMapOf<String, String>()

        val problems = gates.quickCheck(AssignedTask("t", "p", "IMPLEMENTATION", 1, "f", "m", prompt = ""), repo, validation)

        assertEquals("FAILED", validation["doc-rules"])
        assertEquals("UNKNOWN", validation["convention"])
        val documented = problems.single { it.startsWith("이 프로젝트 문서에 적힌 규칙 위반") }
        assertContains(documented, "규칙 위반 1건")
        assertContains(documented, "SleepTest.kt:9 ")
        assertTrue(logs.any { "기존 위반 1건" in it }, logs.toString())
    }

    @Test
    fun `승인한 문서 규칙은 단계 지시 규칙 요약에 출처와 함께 나온다`() {
        val rule = ConventionRule("doc:a", "doc-forbidden-code", "테스트에서 Thread.sleep 을 쓰지 않는다", mapOf("scope" to listOf("test"), "pattern" to listOf("x")),
            approved = true, origin = DocOrigin("docs/testing.md", 7, "테스트", "q", "h"))
        val brief = RulesBrief.build(null, null, documented = DocRules.DocRuleModel(listOf(rule)))!!

        assertContains(brief, "### 이 프로젝트 문서에 적힌 규칙")
        assertContains(brief, "테스트에서 Thread.sleep 을 쓰지 않는다 (docs/testing.md:7)")
    }
}
