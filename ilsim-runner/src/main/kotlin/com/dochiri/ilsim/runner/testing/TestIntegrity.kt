package com.dochiri.ilsim.runner.testing

import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.convention.ConvFile
import com.dochiri.ilsim.runner.convention.FactScanner
import com.dochiri.ilsim.runner.profile.ProjectProfile
import com.dochiri.ilsim.runner.risk.Member
import com.dochiri.ilsim.runner.risk.StructureParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * 테스트가 실제로 검증하는지를 본다. 테스트를 끄거나, 단언을 빼거나, 항상 통과하게 만들어 게이트를 우회하는 변경을 찾는다.
 * 정적 검사([scan])는 이번 작업에서 바뀐 테스트 파일에만 적용하고, 실행 결과([skipped])는 바뀐 테스트 클래스의 건너뛴 케이스를 본다.
 */
object TestIntegrity {
    private val skipAnnotations = setOf(
        "Disabled", "Ignore", "DisabledIf", "DisabledOnOs", "DisabledOnJre", "DisabledForJreRange",
        "DisabledIfSystemProperty", "DisabledIfEnvironmentVariable", "DisabledInNativeImage",
    )
    private val testAnnotations = setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")
    private val assertion = Regex(
        """assert|verify|expect|should|\bfail\s*\(|\bcheck\s*\(|\brequire\s*\(|Assertions|andExpect|isEqualTo|isTrue|isFalse|\bthen\s*\(|satisfies|\bawait""",
        RegexOption.IGNORE_CASE,
    )
    private val trivial = Regex("""assertTrue\s*\(\s*true\s*\)|assertFalse\s*\(\s*false\s*\)|assertThat\s*\(\s*true\s*\)|assertEquals\s*\(\s*([\w."]+)\s*,\s*\1\s*\)""")
    private val swallowing = Regex("""\bcatch\s*\([^)]*(Exception|Throwable|AssertionError)[^)]*\)\s*\{""")
    private val commentedTest = Regex("""^\s*//\s*@(Test|ParameterizedTest)\b""")
    private val assumeFalse = Regex("""\bassume(True)?\s*\(\s*false\s*\)|assumeFalse\s*\(\s*true\s*\)""")

    fun scan(root: Path, profile: ProjectProfile, changed: Set<String>): List<Violation> =
        scan(FactScanner.scan(root, profile.modules).tests.filter { it.path in changed })

    internal fun scan(files: List<ConvFile>): List<Violation> = files.flatMap { f ->
        runCatching { fileViolations(f) }.getOrDefault(emptyList())
    }.sortedWith(compareBy({ it.file }, { it.line }, { it.ruleId }))

    private fun fileViolations(f: ConvFile): List<Violation> {
        val out = mutableListOf<Violation>()
        fun add(rule: String, line: Int, msg: String) { out += Violation(rule, "BLOCK", f.path, line, msg) }

        f.types.filter { t -> t.annotations.any { it in skipAnnotations } }.forEach { add("TEST-001", it.line, "테스트 클래스 `${it.name}` 이 비활성화되어 있습니다") }
        f.lines.forEachIndexed { i, l ->
            if (commentedTest.containsMatchIn(l)) add("TEST-001", i + 1, "주석 처리된 테스트입니다. 지우거나 되살리세요")
            if (Regex("""@Test\s*\([^)]*enabled\s*=\s*false""").containsMatchIn(l)) add("TEST-001", i + 1, "enabled = false 로 꺼진 테스트입니다")
        }
        for (c in StructureParser.classes(f)) for (m in c.funcs.filter { m -> m.annotations.any { it in testAnnotations } }) {
            if (m.annotations.any { it in skipAnnotations }) add("TEST-001", m.line, "테스트 `${m.name}` 이 비활성화되어 있습니다")
            if (assumeFalse.containsMatchIn(m.body)) add("TEST-001", m.line, "테스트 `${m.name}` 이 항상 건너뛰도록 assume 합니다")
            if (!assertion.containsMatchIn(m.body)) add("TEST-002", m.line, "테스트 `${m.name}` 에 단언이 없어 무엇도 검증하지 않습니다")
            trivial.find(m.body)?.let { add("TEST-003", m.lineOf(it.range.first), "테스트 `${m.name}` 의 단언 `${it.value.replace(Regex("\\s+"), "")}` 은 항상 통과합니다") }
            swallowedCatch(m)?.let { add("TEST-004", m.lineOf(it), "테스트 `${m.name}` 이 예외를 잡고도 실패시키지 않아 오류가 숨겨집니다") }
        }
        return out
    }

    private fun swallowedCatch(m: Member): Int? = swallowing.findAll(m.body).firstNotNullOfOrNull { hit ->
        var depth = 0
        var end = m.body.length
        for (k in hit.range.last until m.body.length) {
            if (m.body[k] == '{') depth++ else if (m.body[k] == '}') { depth--; if (depth == 0) { end = k; break } }
        }
        val block = m.body.substring(hit.range.last + 1, end)
        if (Regex("""\b(throw|fail|assert|AssertionError)""").containsMatchIn(block)) null else hit.range.first
    }

    /**
     * 테스트 실행 결과 XML 에서 [changedTests] 의 클래스가 건너뛴 케이스를 찾는다. [since] 이후에 만들어진 결과만 본다.
     * 결과 위치는 Gradle(`test-results`)과 Maven(`surefire-reports`, `failsafe-reports`)의 관례를 따른다.
     */
    fun skipped(root: Path, since: Instant, changedTests: Set<String>): List<String> {
        val classes = changedTests.map { it.substringAfterLast('/').substringBeforeLast('.') }.toSet()
        if (classes.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        Files.walk(root, 10).use { s ->
            s.filter { p ->
                Files.isRegularFile(p) && p.extension == "xml" && p.parent != null &&
                    p.toString().let { "test-results" in it || "surefire-reports" in it || "failsafe-reports" in it } &&
                    !p.getLastModifiedTime().toInstant().isBefore(since)
            }.forEach { p ->
                Regex("""<testcase\s+([^>]*?)/?>\s*(<skipped)?""").findAll(p.readText()).forEach { m ->
                    if (m.groupValues[2].isEmpty()) return@forEach
                    val attrs = m.groupValues[1]
                    val cls = Regex("""classname="([^"]*)"""").find(attrs)?.groupValues?.get(1).orEmpty()
                    val name = Regex("""\bname="([^"]*)"""").find(attrs)?.groupValues?.get(1).orEmpty()
                    if (cls.substringAfterLast('.').substringBefore('$') in classes) out += "$cls.$name"
                }
            }
        }
        return out.distinct().sorted()
    }
}
