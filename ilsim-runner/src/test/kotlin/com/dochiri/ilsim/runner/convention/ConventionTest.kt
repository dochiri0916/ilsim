package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConventionTest {

    private fun project(files: Map<String, String>): Path {
        val root = Files.createTempDirectory("ilsim-conv")
        files.forEach { (path, text) -> root.resolve(path).also { it.parent.createDirectories() }.writeText(text) }
        return root
    }

    private fun controller(n: String, suffix: String = "Controller") = "src/main/kotlin/com/x/web/$n$suffix.kt" to """
        package com.x.web

        import org.springframework.web.bind.annotation.RestController

        @RestController
        class $n$suffix {
            fun get() = "ok"
        }
    """.trimIndent() + "\n"

    private fun service(n: String) = "src/main/kotlin/com/x/service/${n}Service.kt" to """
        package com.x.service

        @Service
        @Transactional(
            readOnly = true,
        )
        class ${n}Service(private val repo: Any) {
            @Transactional
            fun run() = repo
        }
    """.trimIndent() + "\n"

    private fun base(): Map<String, String> =
        (1..6).associate { controller("C$it") } + (1..6).associate { service("S$it") }

    private fun mine(files: Map<String, String>): ConventionModel {
        val root = project(files)
        return ConventionMiner.mine(root, StackDetector.detect(root))
    }

    @Test
    fun `어노테이션과 여러 줄 어노테이션 인자를 읽는다`() {
        val file = project(base()).resolve("src/main/kotlin/com/x/service/S1Service.kt")
        val c = FactScanner.parse("S1Service.kt", "kt", false, file.toFile().readLines())!!
        assertEquals(listOf("Service", "Transactional"), c.types.single().annotations)
        assertEquals("run", c.annotated.single { it.kind == "func" }.name)
        assertEquals(listOf("Transactional"), c.annotated.single { it.kind == "func" }.annotations)
        assertTrue(c.types.single().topLevel)
    }

    @Test
    fun `이 프로젝트의 관행에서 규칙을 만들고 일관되지 않은 항목은 알린다`() {
        val m = mine(base())
        val ids = m.rules.map { it.id }
        assertTrue("annotation-suffix:RestController" in ids)
        assertEquals(listOf("Controller"), m.rules.first { it.id == "annotation-suffix:RestController" }.params["suffix"])
        assertTrue("indent-style" in ids)
        assertTrue("annotation-confinement:Transactional" in ids)
        assertEquals("REVIEW", m.rules.first { it.id == "annotation-confinement:Transactional" }.severity)
        assertTrue(m.rules.none { it.approved || it.rejected })

        val mixed = mine(base() + (1..4).associate { controller("D$it", "Handler") })
        assertNull(mixed.rules.firstOrNull { it.id == "annotation-suffix:RestController" })
        assertTrue(mixed.notes.any { it.contains("RestController") })
    }

    @Test
    fun `같은 코드가 아니면 규칙이 달라진다`() {
        val tabbed = base().mapValues { (_, v) -> v.replace("    ", "\t") }
        val m = mine(tabbed)
        assertEquals(listOf("tabs"), m.rules.first { it.id == "indent-style" }.params["style"])
    }

    @Test
    fun `위반을 찾고 변경한 파일로 범위를 좁히고 승인 모드를 지킨다`() {
        val model = mine(base())
        val dbg = "src/main/kotlin/com/x/web/Dbg.kt" to "package com.x.web\n\nclass Dbg {\n    fun f() { println(\"x\"); val a: String? = null; a!! }\n}\n"
        val root = project(base() + controller("Bad", "Endpoint") + dbg)
        val profile = StackDetector.detect(root)
        val all = ConventionChecker.check(root, profile, model)
        assertTrue(all.any { it.ruleId == "annotation-suffix:RestController" && it.file.endsWith("BadEndpoint.kt") })
        assertTrue(all.any { it.ruleId == "no-debug-output" && it.file.endsWith("Dbg.kt") })
        assertTrue(all.any { it.ruleId == "no-force-unwrap" })

        val onlyDbg = ConventionChecker.check(root, profile, model, onlyFiles = setOf(dbg.first))
        assertTrue(onlyDbg.isNotEmpty() && onlyDbg.all { it.file.endsWith("Dbg.kt") })

        assertEquals(emptyList(), ConventionChecker.check(root, profile, model, approvedOnly = true))
        val approved = ConventionApproval.approve(model, listOf("no-debug-output"))
        assertEquals(setOf("no-debug-output"), ConventionChecker.check(root, profile, approved, approvedOnly = true).map { it.ruleId }.toSet())
        val rejected = ConventionApproval.reject(model, listOf("no-debug-output"))
        assertTrue(ConventionChecker.check(root, profile, rejected).none { it.ruleId == "no-debug-output" })
        assertFailsWith<IllegalArgumentException> { ConventionApproval.approve(model, listOf("nope")) }
    }

    @Test
    fun `AI 의견은 주석으로만 붙고 같은 요약은 다시 검토하지 않는다`() {
        val m = mine(base()).let { it.copy(rules = it.rules.filter { r -> r.id == "indent-style" }) }
        var calls = 0
        val ask = { _: String -> calls++; """{"rules":[{"id":"indent-style","verdict":"KEEP","reason":"일관"}],"missing":["m"],"applicability":"ok"}""" }
        val r = ConventionReviewer.review(m, "sonnet", ask = ask)
        ConventionReviewer.review(r, "sonnet", ask = ask)
        assertEquals(1, calls)
        assertNotNull(r.rules.first { it.id == "indent-style" }.review)
        assertEquals(listOf("indent-style"), ConventionApproval.keepIds(r))
        assertTrue(r.rules.none { it.approved })
        assertTrue(!ConventionReviewer.summary(m).contains(".kt"))
    }

    @Test
    fun `작은 프로젝트에서도 계층 배치와 접미사와 함께 쓰는 어노테이션을 규칙으로 만든다`() {
        val entity = { n: String, extra: String -> "src/main/java/com/x/domain/$n.java" to """
            package com.x.domain;

            @Entity
            $extra
            public class $n {
            }
        """.trimIndent() + "\n" }
        val files = mapOf(
            entity("Order", "@Getter"), entity("Item", "@Getter"),
            "src/main/java/com/x/service/OrderService.java" to "package com.x.service;\n\n@Service\npublic class OrderService {\n}\n",
        )
        val m = mine(files)
        val byId = m.rules.associateBy { it.id }
        assertEquals(listOf("service"), byId.getValue("stereotype-package:Service").params["segment"])
        assertEquals(listOf("Service"), byId.getValue("package-suffix:service").params["suffix"])
        assertEquals(listOf("domain"), byId.getValue("stereotype-package:Entity").params["segment"])
        assertEquals(listOf("Getter"), byId.getValue("annotation-companion:Entity+Getter").params["companion"])
        assertTrue(byId.values.none { it.severity == "BLOCK" && it.evidence.total < ConventionMiner.MIN_BLOCK_SAMPLES })
        val broken = files + ("src/main/java/com/x/web/OrderService.java" to "package com.x.web;\n\n@Service\npublic class OrderService {\n}\n")
        val root = project(broken)
        val facts = FactScanner.scan(root, StackDetector.detect(root).modules)
        val bad = Probes.StereotypePackage.samples(facts, byId.getValue("stereotype-package:Service").params).filter { !it.ok }
        assertEquals(1, bad.size)
    }

    @Test
    fun `패키지 접미사는 CamelCase 마지막 단어가 아니라 공통 접미사로 뽑는다`() {
        assertEquals("UseCase", Probes.commonSuffix(listOf("RegisterMemberUseCase", "LoginUseCase", "CancelOrderUseCase"))?.first)
        assertEquals("Exception", Probes.commonSuffix(listOf("InvalidIdException", "NotFoundException", "DuplicateNameException"))?.first)
        assertNull(Probes.commonSuffix(listOf("OrderRepository", "CartService", "ItemMapper")))
    }

    @Test
    fun `역할 어노테이션이 아닌 어노테이션의 이름 접미사 규칙은 어노테이션 이름과 무관하면 만들지 않는다`() {
        val entity = { n: String -> "src/main/java/com/x/persistence/${n}Entity.java" to
            "package com.x.persistence;\n\n@Getter\npublic class ${n}Entity {\n}\n" }
        val advice = { n: String -> "src/main/java/com/x/web/${n}Handler.java" to
            "package com.x.web;\n\n@Order(1)\n@RestControllerAdvice\npublic class ${n}Handler {\n}\n" }
        val ids = mine(mapOf(entity("A"), entity("B"), entity("C"), advice("A"), advice("B"), advice("C"))).rules.map { it.id }
        assertTrue("annotation-suffix:Getter" !in ids)
        assertTrue("annotation-suffix:Order" !in ids)
        assertTrue("annotation-suffix:RestControllerAdvice" in ids)
    }

    @Test
    fun `클래스 형태 관행을 읽는다 final 클래스와 파라미터와 값 객체 생성자와 도메인 필드`() {
        val vo = { n: String -> "src/main/java/com/x/domain/vo/$n.java" to """
            package com.x.domain.vo;

            public final class $n {

                private final String value;

                private $n(final String value) {
                    this.value = value;
                }

                public static $n of(final String value) {
                    return new $n(value);
                }
            }
        """.trimIndent() + "\n" }
        val m = mine((1..6).associate { vo("V$it") })
        val byId = m.rules.associateBy { it.id }
        assertTrue("class-final" in byId)
        assertTrue("parameter-final" in byId)
        assertTrue("vo-private-constructor" in byId)
        assertTrue("domain-final-fields" in byId)
        val loose = vo("V9").let { (p, t) -> p to t.replace("final String value;", "String value;") }
        val root = project((1..6).associate { vo("V$it") } + loose)
        val facts = FactScanner.scan(root, StackDetector.detect(root).modules)
        assertEquals(1, DomainFinalFields.samples(facts, emptyMap()).count { !it.ok })
    }

    @Test
    fun `패키지의 공통 메서드와 조회 서비스의 읽기 전용 트랜잭션을 관행으로 읽고 private 헬퍼는 제외한다`() {
        val req = { n: String -> "src/main/java/com/x/request/$n.java" to
            "package com.x.request;\n\npublic record $n(String a) {\n    public Object toCommand() {\n        return a;\n    }\n}\n" }
        val svc = { n: String -> "src/main/java/com/x/service/${n}Service.java" to
            "package com.x.service;\n\n@Service\npublic class ${n}Service {\n\n    @Transactional(readOnly = true)\n    public Object get$n() {\n        return find$n();\n    }\n\n    private Object find$n() {\n        return null;\n    }\n}\n" }
        val files = (1..6).associate { req("R$it") } + (1..6).associate { svc("S$it") }
        val ids = mine(files).rules.map { it.id }
        assertTrue("package-method:request.toCommand" in ids)
        assertTrue("readonly-transactions" in ids)
    }

    @Test
    fun `들여쓰기 단위는 블록 안쪽 들여쓰기로만 정하고 record 인자 줄은 세지 않는다`() {
        val record = listOf("package x;", "", "public record A(", "        String a, int b) {}")
        assertNull(Probes.IndentStyle.styleOf(record))
        val block = listOf("class B {", "    void f() {", "        int a;", "    }", "}")
        assertEquals("spaces:4", Probes.IndentStyle.styleOf(block))
    }

    @Test
    fun `어노테이션 한정은 계층 이하 패키지로 일반화해 다른 슬라이스의 같은 계층도 허용한다`() {
        val web = { slice: String, n: String -> "src/main/java/com/x/$slice/adapter/in/web/$n.java" to
            "package com.x.$slice.adapter.in.web;\n\n@RestController\npublic class $n {\n    @RequestParam String q;\n}\n" }
        val files = mapOf(web("catalog", "AController"), web("catalog", "BController"), web("catalog", "CController"))
        val rule = mine(files).rules.first { it.id == "annotation-confinement:RequestParam" }
        assertEquals(listOf("adapter.in.web"), rule.params["packages"])
        val root = project(files + web("order", "DController"))
        val facts = FactScanner.scan(root, StackDetector.detect(root).modules)
        assertTrue(Probes.AnnotationConfinement.samples(facts, rule.params).all { it.ok })
    }
}
