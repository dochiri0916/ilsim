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

object TestIntegrity {
    private val skipAnnotations = setOf(
        "Disabled", "Ignore", "DisabledIf", "DisabledOnOs", "DisabledOnJre", "DisabledForJreRange",
        "DisabledIfSystemProperty", "DisabledIfEnvironmentVariable", "DisabledInNativeImage",
        "EnabledIf", "EnabledOnOs", "EnabledOnJre", "EnabledForJreRange", "EnabledIfSystemProperty", "EnabledIfSystemProperties",
        "EnabledIfEnvironmentVariable", "EnabledIfEnvironmentVariables", "EnabledInNativeImage",
    )
    private val testAnnotations = setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")
    private val assertion = Regex(
        """assert|\b(co)?verify(Sequence|Order|All|NoMoreInteractions|ZeroInteractions|NoInteractions)?\s*[({]|confirmVerified|\bexpect\s*\(|expectThat|expectThrows|\bshould[A-Z]\w*|\.should\b|\bfail\s*\(|\bcheck\s*\(|\brequire\s*\(|Assertions|andExpect|isEqualTo|isTrue|isFalse|\bthen\s*\(|satisfies|\bawait""",
    )
    private val trivial = Regex(
        """assertTrue\s*\(\s*true\s*[,)]|assertFalse\s*\(\s*false\s*[,)]|\bassert\s*\(\s*true\s*[,)]|assertThat\s*\(\s*true\s*\)|assertEquals\s*\(\s*([\w."]+)\s*,\s*\1\s*[,)]""" +
            """|assert(True|That)?\s*\(\s*([\w."]+)\s*==\s*\3\s*[,)]|assertThat\s*\(\s*(\w+)\s*\)\s*\.isEqualTo\s*\(\s*\4\s*\)""",
    )
    private val commentedTest = Regex("""^\s*//\s*@(Test|ParameterizedTest)\b""")
    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val assumeFalse = Regex("""\bassume(True|That)?\s*\(\s*(false|!\s*true)\s*\)|assumeFalse\s*\(\s*(true|!\s*false)\s*\)""")
    private val earlyReturn = Regex("""\bif\s*\(\s*(true|!\s*false)\s*\)\s*(\{\s*)?return\b|^\s*return\s*;?\s*\n\s*[^}\s]""", RegexOption.MULTILINE)
    private val aliasImport = Regex("""^\s*import\s+[\w.]+\.(\w+)\s+as\s+(\w+)""")
    private val throwableAlias = Regex("""^\s*typealias\s+(\w+)\s*=\s*(?:[\w.]+\.)?(Throwable|Exception|RuntimeException|Error|AssertionError)\b""")

    fun scan(root: Path, profile: ProjectProfile, changed: Set<String>): List<Violation> =
        scan(FactScanner.scan(root, profile.modules).tests.filter { it.path in changed })

    internal fun scan(files: List<ConvFile>): List<Violation> = files.flatMap { f ->
        runCatching { fileViolations(f) }.getOrDefault(emptyList())
    }.sortedWith(compareBy({ it.file }, { it.line }, { it.ruleId }))

    private fun fileViolations(f: ConvFile): List<Violation> {
        val out = mutableListOf<Violation>()
        fun add(rule: String, line: Int, msg: String) { out += Violation(rule, "BLOCK", f.path, line, msg) }
        val aliases = f.lines.mapNotNull { aliasImport.find(it) }.associate { it.groupValues[2] to it.groupValues[1] }
        fun names(list: List<String>) = list.map { aliases[it] ?: it }
        val throwables = setOf("Exception", "Throwable", "Error", "AssertionError") + f.lines.mapNotNull { throwableAlias.find(it)?.groupValues?.get(1) }

        f.types.filter { t -> names(t.annotations).any { it in skipAnnotations } }.forEach { add("TEST-001", it.line, "테스트 클래스 `${it.name}` 이 비활성화되어 있습니다") }
        f.lines.forEachIndexed { i, l ->
            if (commentedTest.containsMatchIn(l)) add("TEST-001", i + 1, "주석 처리된 테스트입니다. 지우거나 되살리세요")
            if (Regex("""@Test\s*\([^)]*enabled\s*=\s*false""").containsMatchIn(l)) add("TEST-001", i + 1, "enabled = false 로 꺼진 테스트입니다")
        }
        val text = f.lines.joinToString("\n")
        blockComment.findAll(text).filter { Regex("""@(Test|ParameterizedTest)\b[\s\S]*?\b(fun|void|def)\b""").containsMatchIn(it.value) }
            .forEach { add("TEST-001", text.take(it.range.first).count { c -> c == '\n' } + 1, "블록 주석으로 감춘 테스트입니다. 지우거나 되살리세요") }
        for (c in StructureParser.classes(f)) for (m in c.funcs.filter { m -> names(m.annotations).any { it in testAnnotations } }) {
            val annotated = names(m.annotations)
            val expected = (maxOf(0, m.line - 4) until m.line).any { Regex("""@Test\s*\(\s*expected\s*=""").containsMatchIn(f.lines.getOrElse(it) { "" }) }
            if (annotated.any { it in skipAnnotations }) add("TEST-001", m.line, "테스트 `${m.name}` 이 비활성화되어 있습니다")
            if (assumeFalse.containsMatchIn(m.body)) add("TEST-001", m.line, "테스트 `${m.name}` 이 항상 건너뛰도록 assume 합니다")
            earlyReturn.find(m.body)?.let { add("TEST-001", m.lineOf(it.range.first), "테스트 `${m.name}` 이 검증 전에 항상 return 합니다") }
            if (!expected && !assertion.containsMatchIn(m.body)) add("TEST-002", m.line, "테스트 `${m.name}` 에 단언이 없어 무엇도 검증하지 않습니다")
            (trivial.find(m.body) ?: freshNotNull(m.body))?.let { add("TEST-003", m.lineOf(it.range.first), "테스트 `${m.name}` 의 단언 `${it.value.replace(Regex("\\s+"), "")}` 은 항상 통과합니다") }
            swallowedCatch(m, throwables)?.let { add("TEST-004", m.lineOf(it), "테스트 `${m.name}` 이 예외를 잡고도 실패시키지 않아 오류가 숨겨집니다") }
        }
        return out
    }

    private fun freshNotNull(body: String): MatchResult? = Regex("""assertNotNull\s*\(\s*(\w+)\s*\)|assertThat\s*\(\s*(\w+)\s*\)\s*\.isNotNull\s*\(""").findAll(body).firstOrNull { m ->
        val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
        Regex("""\b(val|var|final\s+\w+|\w+)\s+$name\s*=\s*(new\s+)?[A-Z]\w*\s*\(""").containsMatchIn(body)
    }

    private fun swallowedCatch(m: Member, throwables: Set<String>): Int? {
        Regex("""^\s*runCatching\s*\{""", RegexOption.MULTILINE).findAll(m.body).firstOrNull { hit ->
            val block = m.body.substring(hit.range.last + 1, closing(m.body, hit.range.last))
            val after = m.body.substring(closing(m.body, hit.range.last)).trimStart()
            assertion.containsMatchIn(block) && !after.startsWith(".getOrThrow") && !after.startsWith(".onFailure")
        }?.let { return it.range.first }
        val swallowing = Regex("""\bcatch\s*\(\s*(\w+)\s*:?\s*(?:final\s+)?(?:[\w.]+\.)?(\w+)?[^)]*\)\s*\{""")
        return swallowing.findAll(m.body).firstNotNullOfOrNull { hit ->
            val kotlin = ':' in hit.value
            val type = if (kotlin) hit.groupValues[2] else hit.groupValues[1]
            val variable = if (kotlin) hit.groupValues[1] else hit.groupValues[2]
            if (throwables.none { type == it || type.endsWith(it) } && throwables.none { Regex("""\b$it\b""").containsMatchIn(hit.value) }) return@firstNotNullOfOrNull null
            val end = closing(m.body, hit.range.last)
            val block = m.body.substring(hit.range.last + 1, end)
            val captured = variable.isNotEmpty() && Regex("""\w+\s*=\s*$variable\b""").containsMatchIn(block) && assertion.containsMatchIn(m.body.substring(end))
            if (captured || Regex("""\b(throw|fail|assert|AssertionError)""").containsMatchIn(block)) null else hit.range.first
        }
    }

    private fun closing(body: String, open: Int): Int {
        var depth = 0
        for (k in open until body.length) {
            if (body[k] == '{') depth++ else if (body[k] == '}') { depth--; if (depth == 0) return k }
        }
        return body.length
    }

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
