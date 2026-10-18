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
}
