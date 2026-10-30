package com.dochiri.ilsim.runner.testing

import com.dochiri.ilsim.runner.Shell
import com.dochiri.ilsim.runner.convention.FactScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerificationBypassTest {
    private data class Case(val name: String, val gate: String, val path: String, val expect: String, val body: List<String>, val listed: Boolean = false)

    private val corpus = Path.of("corpus/gate-bypass")

    private fun cases(): List<Case> = corpus.listDirectoryEntries("*.case").sortedBy { it.name }.map { file ->
        val (head, body) = file.readText().split("\n---\n", limit = 2)
        val fields = head.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        Case(file.name, fields.getValue("gate"), fields.getValue("path"), fields.getValue("expect"), body.trimEnd().lines(), fields["scope"] == "listed")
    }

    private fun found(case: Case): Set<String> = when (case.gate) {
        "bypass" -> VerificationBypass.scan(setOf(case.path), mapOf(case.path to case.body.mapIndexed { i, t -> VerificationBypass.AddedLine(i + 1, t) }), scoped = { case.listed }).map { it.ruleId }.toSet()
        "test-integrity" -> TestIntegrity.scan(listOf(FactScanner.parse(case.path, Path.of(case.path).extension, true, case.body)!!)).map { it.ruleId }.toSet()
        else -> error("알 수 없는 gate: ${case.gate} (${case.name})")
    }

    @Test
    fun `회귀 세트의 모든 사례를 기대대로 막거나 통과시킨다`() {
        val all = cases()
        assertTrue(all.size >= 10, "회귀 세트가 비어 있습니다")
        val wrong = all.mapNotNull { c ->
            val hits = found(c)
            val ok = if (c.expect == "PASS") hits.isEmpty() else c.expect in hits
            if (ok) null else "${c.name}: 기대 ${c.expect}, 실제 ${hits.ifEmpty { setOf("PASS") }}"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `영향 범위에 없는 설정·빌드 파일 변경은 내용과 관계없이 막는다`() {
        val configs = listOf("build.gradle.kts", ".detekt.yml", "codecov.yml", ".github/workflows/ci.yml", "jest.config.js", "conftest.py", "Makefile")
        val code = listOf("src/main/kotlin/com/x/A.kt", "src/test/kotlin/com/x/ATest.kt", "docs/guide.md", "src/main/resources/seed/words.txt", "src/test/resources/fixture.json", "app/main.py")
        assertEquals(configs, configs.filter(VerificationBypass::config))
        assertEquals(emptyList(), code.filter(VerificationBypass::config))

        val added = mapOf("codecov.yml" to listOf(VerificationBypass.AddedLine(1, "coverage:")))
        assertEquals(listOf("BYPASS-004"), VerificationBypass.scan(setOf("codecov.yml"), added, scoped = { false }).map { it.ruleId })
        assertEquals(emptyList(), VerificationBypass.scan(setOf("codecov.yml"), added, scoped = { true }).map { it.ruleId })
        assertEquals(emptyList(), VerificationBypass.scan(setOf("codecov.yml"), added, allowed = { true }, scoped = { false }).map { it.ruleId })
    }

    @Test
    fun `작업 트리에서 새로 넣은 줄만 보고 사용자가 허락한 파일은 넘긴다`() {
        val root = Files.createTempDirectory("ilsim-bypass").toRealPath()
        try {
            val file = "src/main/kotlin/com/x/A.kt"
            root.resolve(file).also { it.parent.createDirectories() }.writeText("package com.x\n\n@Suppress(\"OLD\")\nclass A\n")
            Shell.gitOk(root, "init", "-q", "-b", "main")
            Shell.gitOk(root, "add", "-A")
            Shell.gitOk(root, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init")
            assertEquals(emptyList(), VerificationBypass.scan(root, setOf(file)), "기존 억제는 이번 변경이 만든 것이 아니다")

            root.resolve(file).writeText("package com.x\n\n@Suppress(\"OLD\")\nclass A {\n    @SuppressWarnings(\"x\")\n    fun f() = 1\n}\n")
            root.resolve("detekt.yml").writeText("style:\n  active: false\n")
            val hits = VerificationBypass.scan(root, setOf(file, "detekt.yml"))
            assertEquals(listOf("BYPASS-002" to "detekt.yml", "BYPASS-001" to file), hits.map { it.ruleId to it.file })
            assertEquals(5, hits.last().line)
            assertEquals(listOf("BYPASS-001"), VerificationBypass.scan(root, setOf(file, "detekt.yml"), allowed = { it == "detekt.yml" }).map { it.ruleId })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
