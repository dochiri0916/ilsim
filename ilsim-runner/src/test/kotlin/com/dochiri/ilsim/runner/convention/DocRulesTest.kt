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
import kotlin.test.assertFalse
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
    fun `필수 애너테이션 규칙은 값 형식까지 확인하고 앞 메서드의 애너테이션과 섞지 않는다`() {
        val tests = Facts(listOf(FactScanner.parse("src/test/java/demo/GreeterTest.java", "java", true, """
            package demo;

            class GreeterTest {
                @Test
                @DisplayName("작별 인사는 마침표로 끝난다")
                void goodbye() { assertThat(1).isOne(); }
                @Test
                @DisplayName("blank name throws")
                void blank() { assertThat(1).isOne(); }
                @Test
                @DisplayName(
                    "여러 줄 설명"
                )
                void multi() { assertThat(1).isOne(); }
            }
        """.trimIndent().lines())!!))
        val params = DocProbes.normalize("doc-required-annotation", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "annotation" to listOf("DisplayName"), "value" to listOf("[가-힣]")))
        val failed = DocProbes.byKind("doc-required-annotation")!!.samples(tests, params).filter { !it.ok }
        assertEquals(listOf("blank 의 @DisplayName 값이 정해진 형식과 다릅니다"), failed.map { it.message })
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-required-annotation", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "annotation" to listOf("A"), "value" to listOf("[가-"))) }
    }

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
    fun `코틀린 백틱 이름과 저장소 루트의 경로를 문서 규칙대로 판정한다`() {
        val kotlin = Facts(listOf(FactScanner.parse("src/test/kotlin/com/x/NameTest.kt", "kt", true, """
            package com.x

            import org.junit.jupiter.api.Test

            class NameTest {
                @Test
                fun `주문을 만든다`() {
                }

                @Test
                fun createsOrder() {
                }
            }
        """.trimIndent().lines())!!))
        val named = DocProbes.normalize("doc-name-pattern", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "pattern" to listOf("^`.*[가-힣].*`$")))
        assertEquals(listOf("이름 `createsOrder` 이 정해진 형식과 다릅니다"), DocProbes.byKind("doc-name-pattern")!!.samples(kotlin, named).filter { !it.ok }.map { it.message })
        val located = DocProbes.normalize("doc-file-location", mapOf("scope" to listOf("test"), "name" to listOf(".*Test"), "location" to listOf("**/src/test/kotlin/**")))
        assertTrue(DocProbes.byKind("doc-file-location")!!.samples(kotlin, located).all { it.ok })
        assertEquals(null, DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("all"), "pattern" to listOf("!!"), "text" to listOf("code")))["text"])
        assertEquals(null, DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("main"), "pattern" to listOf("x"), "target" to listOf("file")))["target"])
        assertFailsWith<IllegalArgumentException> { DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("all"), "pattern" to listOf("x"), "target" to listOf("test-method"))) }
    }

    @Test
    fun `규칙 구간만 Claude가 옮기고 JEV가 원문과 다르다고 본 규칙과 지어낸 인용은 뺀다`() {
        val prompts = mutableListOf<String>()
        val extracted = DocRules.extract(listOf(intro, writing), facts, null, documentJev) { prompts += it; answer }

        assertEquals(1, prompts.size)
        assertTrue("테스트 작성 방법을 소개한다" !in prompts.single() && "JUnit Assertions 는 쓰지 않는다" in prompts.single())
        assertContains(prompts.single(), "- src/main/kotlin/com/x/domain (com.x.domain, 파일 1개): Order")
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
    fun `Java·Kotlin 소스와 빌드 파일이 모두 없는 프로젝트에는 문서 지침 검증기를 만들지 않는다`() {
        assertFalse(DocRules.applicable("npm", Facts(emptyList())))
        assertFalse(DocRules.applicable("unknown", Facts(emptyList())))
        assertTrue(DocRules.applicable("gradle-kotlin-dsl", Facts(emptyList())))
        assertTrue(DocRules.applicable("maven", Facts(emptyList())))
        assertTrue(DocRules.applicable("unknown", facts))
    }

    @Test
    fun `원문 일치가 애매한 후보만 따로 다시 묻고 두 값을 평균한다`() {
        val batchJev = jev { _, body -> if (body.path("state").path("candidates").size() > 1) 0.3 else 0.9 }
        val proposals = listOf(
            DocRules.Proposal(writing, "doc-forbidden-import", mapOf("scope" to listOf("test"), "import" to listOf("^org\\.junit")), "JUnit 을 import 하지 않는다", "JUnit `Assertions`는 import 하지 않는다"),
            DocRules.Proposal(writing, "doc-required-annotation", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "annotation" to listOf("DisplayName")), "@DisplayName 을 붙인다", "모든 테스트 메서드에 @DisplayName 을 붙인다."),
        )
        val faith = DocRules.verify(batchJev, proposals)!!
        assertEquals(listOf(0.6, 0.6), proposals.map { "%.2f".format(faith.getValue(it)).toDouble() })
        assertEquals(listOf(0.9, 0.9), proposals.map { DocRules.verify(batchJev, proposals, single = true)!!.getValue(it) })
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
    fun `승인 전 문서 규칙의 새 위반은 막지 않고 검토 근거로 남기며 거절한 규칙은 보지 않는다`() {
        val home = Files.createTempDirectory("ilsim-doc-proposed-home")
        val repo = Files.createTempDirectory("ilsim-doc-proposed-repo").toRealPath()
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
        val sleep = ConventionRule("doc:sleep", "doc-forbidden-code", "테스트에서 Thread.sleep 을 쓰지 않는다", DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("""Thread\.sleep"""))),
            origin = DocOrigin("docs/testing.md", 7, "테스트", "q", "h"))
        val other = ConventionRule("doc:fun", "doc-forbidden-code", "테스트에서 fun b 를 쓰지 않는다", DocProbes.normalize("doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("""fun b"""))),
            rejected = true, origin = DocOrigin("docs/testing.md", 9, "테스트", "q", "h"))
        DocRuleStore(store).save("p", DocRules.DocRuleModel(listOf(sleep, other)))
        val logs = mutableListOf<String>()
        val gates = StageGates(store, ArchitectureStore(store), ConventionStore(store), { _, _ -> "" }, logs::add)
        val validation = linkedMapOf<String, String>()

        val problems = gates.quickCheck(AssignedTask("t", "p", "IMPLEMENTATION", 1, "f", "m", prompt = ""), repo, validation)

        assertEquals("PROPOSED", validation["doc-rules"])
        assertTrue(problems.none { it.startsWith("이 프로젝트 문서에 적힌 규칙 위반") }, problems.toString())
        assertEquals(1, gates.proposedRuleHits.size, gates.proposedRuleHits.toString())
        assertContains(gates.proposedRuleHits.single(), "SleepTest.kt:9 ")
        assertContains(gates.proposedRuleHits.single(), "<doc:sleep>")
        assertTrue(logs.any { "승인 전 문서 규칙에 걸림" in it }, logs.toString())
    }

    @Test
    fun `승인한 문서 규칙은 단계 지시 규칙 요약에 출처와 함께 나온다`() {
        val rule = ConventionRule("doc:a", "doc-forbidden-code", "테스트에서 Thread.sleep 을 쓰지 않는다", mapOf("scope" to listOf("test"), "pattern" to listOf("x")),
            approved = true, origin = DocOrigin("docs/testing.md", 7, "테스트", "q", "h"))
        val brief = RulesBrief.build(null, null, documented = DocRules.DocRuleModel(listOf(rule)))!!

        assertContains(brief, "### 이 프로젝트 문서에 적힌 규칙")
        assertContains(brief, "테스트에서 Thread.sleep 을 쓰지 않는다 (docs/testing.md:7)")
    }

    @Test
    fun `JEV 가 원문과 맞다고 본 문서 규칙은 자동 승인하고 기존 코드 대부분이 어기면 잘못 옮긴 것으로 보고 거절한다`() {
        fun rule(id: String, verdict: String?, compliant: Int, total: Int) = ConventionRule(
            "doc:$id", "doc-forbidden-code", id, mapOf("scope" to listOf("main")), severity = "BLOCK",
            evidence = com.dochiri.ilsim.runner.architecture.Evidence(compliant, total),
            review = verdict?.let { com.dochiri.ilsim.runner.architecture.RuleReview(it, "JEV") },
        )
        val extracted = DocRules.Extracted(listOf(rule("ok", "KEEP", 183, 188), rule("bad", "KEEP", 23, 89), rule("none", null, 1, 1), rule("user", "KEEP", 0, 10)), emptyList(), emptyList())
        val previous = DocRules.DocRuleModel(listOf(rule("user", "KEEP", 0, 10).copy(approved = true, decidedBy = "user"), rule("ok", "KEEP", 0, 1).copy(rejected = true, decidedBy = "ai-review")))
        val model = DocRules.carryOver(previous, extracted)
        assertEquals(listOf(true, false, false, true), model.rules.map { it.approved })
        assertEquals(listOf(false, true, false, false), model.rules.map { it.rejected })
        assertEquals(listOf("ai-review", "ai-review", null, "user"), model.rules.map { it.decidedBy })
        assertContains(DocRules.report(model), "자동 거절")
    }

    @Test
    fun `문자열 내용에 대한 규칙은 주석을 빼고 보고 주석 표시가 있는 규칙만 원래 줄을 본다`() {
        val facts = Facts(listOf(FactScanner.parse("src/main/kotlin/com/x/StockRepository.kt", "kt", false, """
            package com.x

            /**
             * DELETE 와 FOR UPDATE 는 쓰지 않는다.
             */
            interface StockRepository {
                @Query("SELECT s FROM Stock s FOR UPDATE")
                fun lock(): Stock // given
            }
        """.trimIndent().lines())!!))
        fun hits(params: Map<String, List<String>>) =
            DocProbes.byKind("doc-forbidden-code")!!.samples(facts, DocProbes.normalize("doc-forbidden-code", params)).filter { !it.ok }.map { it.line }
        assertEquals(listOf(7), hits(mapOf("scope" to listOf("main"), "pattern" to listOf("FOR UPDATE", "\\bDELETE\\b"), "text" to listOf("raw"))), "주석 속 DELETE·FOR UPDATE 는 위반이 아니다")
        assertEquals(listOf(7), hits(mapOf("scope" to listOf("main"), "pattern" to listOf("FOR UPDATE"), "text" to listOf("string"))))
        assertEquals(emptyList(), hits(mapOf("scope" to listOf("main"), "pattern" to listOf("FOR UPDATE"))), "기본은 문자열 내용도 뺀다")
        assertEquals(listOf(8), hits(mapOf("scope" to listOf("main"), "pattern" to listOf("//\\s*given"), "text" to listOf("raw"))))
    }

    @Test
    fun `코드로 적힌 금지 문장은 AI 없이 정적 규칙으로 옮기고 조건이 붙은 문장은 남긴다`() {
        val section = DocSection("docs/testing.md", 1, "테스트", """
            - 테스트에서 `Thread.sleep()`을 쓰지 않는다.
            - 테스트를 건너뛰려고 `@Disabled`를 붙이지 않는다.
            - 동시성 테스트에서만 `@Transactional`을 쓰지 않는다.
            - 조회 테스트는 나와야 할 행과 나오면 안 되는 행을 함께 확인한다.
            배경 설명 문장이다
        """.trimIndent())
        val result = StaticDocRules.extract(section)
        assertEquals(listOf("doc-forbidden-code", "doc-forbidden-annotation"), result.proposals.map { it.kind })
        assertEquals(listOf("test"), result.proposals.first().params["scope"])
        assertEquals(listOf("Disabled"), result.proposals.last().params["annotation"])
        assertEquals(2, result.uncovered.size, "조건이 붙은 금지와 의미 규칙은 AI에 넘긴다")
        val facts = Facts(listOf(FactScanner.parse("src/test/kotlin/com/x/ATest.kt", "kt", true, "package com.x\n\nclass ATest {\n    fun a() { Thread.sleep(10) }\n    // Thread.sleep 은 쓰지 않는다\n}\n".lines())!!))
        val probe = DocProbes.byKind("doc-forbidden-code")!!
        assertEquals(listOf(4), probe.samples(facts, result.proposals.first().params).filter { !it.ok }.map { it.line })
    }

    @Test
    fun `표 행에 코드로 적힌 금지는 정적 규칙으로 옮기고 머리글과 코드가 아닌 표기는 남긴다`() {
        val section = DocSection("docs/testing.md", 1, "통합 테스트", """
            | 항목 | 규칙 |
            |---|---|
            | 설정 | 테스트마다 `@TestPropertySource`, `@MockitoBean` 같은 설정 변경 금지. 컨텍스트를 새로 만들어 느려짐 |
            | 쓰지 않는 문장 | `SELECT ... FOR UPDATE`, DELETE |
            | 고객사 | 쿼리에 직접 씀(Hibernate `@Filter` 미사용) |
        """.trimIndent())
        val result = StaticDocRules.extract(section)
        assertEquals(listOf(listOf("TestPropertySource", "MockitoBean"), listOf("Filter")), result.proposals.map { it.params["annotation"] })
        assertEquals(listOf("test", "test"), result.proposals.map { it.params.getValue("scope").single() })
        assertTrue(result.uncovered.none { "항목" in it })
    }

    @Test
    fun `같은 문장에서 같은 종류로 뽑힌 규칙은 하나로 합치고 사용자가 정한 규칙을 남긴다`() {
        val twice = """
            {"sections":[{"id":"s0","rules":[
              {"kind":"doc-forbidden-import","params":{"scope":["test"],"import":["^org\\.junit\\.jupiter\\.api\\.Assertions"]},"description":"JUnit Assertions 를 import 하지 않는다","quote":"JUnit Assertions 는 쓰지 않는다"},
              {"kind":"doc-forbidden-import","params":{"scope":["test"],"import":["^org\\.junit\\.jupiter\\.api\\.Assertions\\b"]},"description":"JUnit Assertions 금지","quote":"JUnit Assertions 는 쓰지 않는다."}
            ],"remaining":[]}]}
        """.trimIndent()
        val first = DocRules.extract(listOf(writing), facts, null, documentJev) { twice }
        assertEquals(1, first.rules.size)
        assertEquals(first.rules.map { it.id }, first.documents.single().rules)
        assertTrue(first.notes.any { "하나로 합쳤습니다" in it })

        val decided = first.rules.single().copy(id = "doc:decided", params = mapOf("scope" to listOf("test"), "import" to listOf("^org\\.junit")), approved = true, decidedBy = "user")
        val again = DocRules.extract(listOf(writing), facts, DocRules.DocRuleModel(listOf(decided)), documentJev) { twice }
        assertEquals(listOf("doc:decided"), again.rules.map { it.id })
        assertEquals(listOf("doc:decided"), again.documents.single().rules)
    }

    @Test
    fun `자체 시험은 규칙 범위의 파일에 위반을 심어 검사기가 잡는지 보고 정규식에서 위반 예시를 만든다`() {
        assertEquals("Thread.sleep(", RegexExample.of("""(?<![\w.])\QThread.sleep\E\s*\("""))
        assertEquals("$.error.message", RegexExample.of("""\$\.error\.message"""))
        assertEquals("customerId==1", RegexExample.of("""customerId\s*==\s*\d+L?"""))
        assertEquals("//given", RegexExample.of("""//\s*(given|when|then)\b"""))
        assertEquals(null, RegexExample.of("""[A-Z]{3}"""))
        val test = FactScanner.parse("src/test/kotlin/com/x/ATest.kt", "kt", true, "package com.x\n\nclass ATest {\n    @Test\n    @DisplayName(\"한다\")\n    fun a() { }\n}\n".lines())!!
        val facts = Facts(listOf(test))
        fun rule(id: String, kind: String, params: Map<String, List<String>>) = ConventionRule(id, kind, id, DocProbes.normalize(kind, params))
        val r = ConventionSelfTest.run(facts, listOf(
            rule("sleep", "doc-forbidden-code", mapOf("scope" to listOf("test"), "pattern" to listOf("""Thread\.sleep"""))),
            rule("display", "doc-required-annotation", mapOf("scope" to listOf("test"), "target" to listOf("test-method"), "annotation" to listOf("DisplayName"))),
            rule("junit", "doc-forbidden-import", mapOf("scope" to listOf("test"), "import" to listOf("""^org\.junit\.jupiter\.api\.Assertions"""))),
        ))
        assertEquals(setOf("sleep", "display", "junit"), r.caught.toSet())
    }
}
