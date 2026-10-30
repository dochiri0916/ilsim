package com.dochiri.ilsim.runner.testing

import com.dochiri.ilsim.runner.Gate
import com.dochiri.ilsim.runner.Shell
import com.dochiri.ilsim.runner.architecture.Violation
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

object VerificationBypass {
    data class AddedLine(val line: Int, val text: String)

    private const val QUALIFIER = """(?:\w+\.)*"""
    private val sourceExtensions = setOf("java", "kt", "groovy", "scala", "ts", "tsx", "js", "jsx", "py")
    private val suppressions = listOf(
        Regex("""@(file:)?${QUALIFIER}Suppress\b""") to "@Suppress",
        Regex("""@${QUALIFIER}SuppressWarnings\b""") to "@SuppressWarnings",
        Regex("""@${QUALIFIER}SuppressFBWarnings\b""") to "@SuppressFBWarnings",
        Regex("""@${QUALIFIER}ArchIgnore\b""") to "@ArchIgnore",
        Regex("""@${QUALIFIER}Generated\b""") to "@Generated(커버리지 제외)",
        Regex("""//\s*noinspection\b""") to "noinspection",
        Regex("""\bNOSONAR\b""") to "NOSONAR",
        Regex("""ktlint-disable|detekt:\s*disable""") to "ktlint/detekt 끄기",
        Regex("""CHECKSTYLE\s*[:.]?\s*OFF""", RegexOption.IGNORE_CASE) to "CHECKSTYLE:OFF",
        Regex("""eslint-disable|@ts-ignore|@ts-nocheck|@ts-expect-error""") to "eslint/ts 끄기",
        Regex("""#\s*(noqa|type:\s*ignore|pylint:\s*disable)""") to "noqa/pylint 끄기",
    )
    private val configFiles = listOf(
        Regex("""(^|/)detekt[^/]*\.ya?ml$"""), Regex("""(^|/)(checkstyle|pmd|spotbugs|findbugs)[^/]*\.xml$"""),
        Regex("""(^|/)\.editorconfig$"""), Regex("""(^|/)lint\.xml$"""), Regex("""(^|/)sonar-project\.properties$"""),
        Regex("""(^|/)archunit[^/]*\.properties$"""), Regex("""(^|/)archunit_store/"""),
        Regex("""(^|/)\.eslintrc[^/]*$"""), Regex("""(^|/)eslint\.config\.[^/]+$"""), Regex("""(^|/)(ruff|\.flake8|\.pylintrc|mypy\.ini)[^/]*$"""),
        Regex("""(^|/)\.ilsim-policy\.json$"""),
    )
    private val buildFiles = Regex("""(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|pom\.xml|package\.json|pyproject\.toml|[^/]+\.gradle(\.kts)?)$""")
    private val weakening = listOf(
        Regex("""\b(is)?[iI]gnoreFailures\s*[=(:]\s*true""") to "검사 실패를 무시(ignoreFailures)",
        Regex("""\bfailOn(Violation|Error|Warning)s?\s*[=(:]\s*false""") to "위반 시 실패 끄기",
        Regex("""<(skip|skipTests|testFailureIgnore|maven\.test\.skip)>\s*true|<(failOnViolation|failsOnError|failOnError)>\s*false""") to "Maven 검사 건너뛰기",
        Regex("""(-x|--exclude-task)\s+:?\S*(test|check|detekt|ktlint|checkstyle|pmd|spotbugs|jacoco|pitest)""", RegexOption.IGNORE_CASE) to "검사 작업 제외(-x)",
        Regex("""\b(skipTests|maven\.test\.skip)\b""") to "테스트 건너뛰기",
        Regex("""--passWithNoTests|--no-verify""") to "검사 건너뛰기 옵션",
    )

    private val disabling = Regex("""\b(is)?[eE]nabled\s*[=(:]\s*false|\bonlyIf\s*\{\s*false""")
    private val checkTask = Regex("""\b(test|check|detekt|ktlint|checkstyle|pmd|spotbugs|jacoco|pitest|lint|archTest|integrationTest)\w*""", RegexOption.IGNORE_CASE)

    fun scan(root: Path, changed: Set<String>, allowed: (String) -> Boolean = { false }): List<Violation> =
        scan(changed, added(root, changed), allowed)

    internal fun scan(changed: Set<String>, added: Map<String, List<AddedLine>>, allowed: (String) -> Boolean = { false }): List<Violation> {
        val out = mutableListOf<Violation>()
        for (path in changed.sorted()) {
            if (allowed(path)) continue
            if (configFiles.any { it.containsMatchIn(path) }) {
                out += Violation("BYPASS-002", "BLOCK", path, added[path]?.firstOrNull()?.line ?: 1, "검사 설정 파일 `${path.substringAfterLast('/')}` 을 바꿨습니다. 검사 기준은 사용자만 바꿉니다")
                continue
            }
            val lines = added[path].orEmpty()
            if (buildFiles.containsMatchIn(path)) {
                lines.forEachIndexed { i, l ->
                    val reason = weakening.firstOrNull { it.first.containsMatchIn(l.text) }?.second
                        ?: "검사 작업 끄기(enabled = false, onlyIf { false })".takeIf { disabling.containsMatchIn(l.text) && lines.subList(maxOf(0, i - 3), i + 1).any { p -> checkTask.containsMatchIn(p.text.replace(disabling, "")) } }
                    reason?.let { out += Violation("BYPASS-003", "BLOCK", path, l.line, "빌드 설정에서 검증을 약하게 하는 변경입니다: $it") }
                }
                continue
            }
            if (Gate.isTestPath(path) || path.substringAfterLast('.') !in sourceExtensions) continue
            lines.forEach { l -> suppressions.firstOrNull { it.first.containsMatchIn(l.text) }?.let { out += Violation("BYPASS-001", "BLOCK", path, l.line, "검사를 끄는 ${it.second} 를 새로 넣었습니다. 경고를 숨기지 말고 원인을 고치세요") } }
        }
        return out
    }

    private fun added(root: Path, changed: Set<String>): Map<String, List<AddedLine>> {
        val out = mutableMapOf<String, MutableList<AddedLine>>()
        val diff = Shell.git(root, "diff", "HEAD", "--unified=0", "--no-color", "--no-ext-diff").takeIf { it.exit == 0 }?.output.orEmpty()
        var file: String? = null
        var next = 0
        for (line in diff.lines()) when {
            line.startsWith("+++ ") -> file = line.removePrefix("+++ ").removePrefix("b/").takeIf { it != "/dev/null" }
            line.startsWith("@@") -> next = Regex("""\+(\d+)""").find(line)?.groupValues?.get(1)?.toInt() ?: 0
            line.startsWith("+") && file != null -> { out.getOrPut(file!!) { mutableListOf() } += AddedLine(next, line.substring(1)); next++ }
        }
        changed.filter { it !in out }.forEach { path ->
            val f = root.resolve(path)
            if (f.exists() && f.isRegularFile() && Shell.git(root, "cat-file", "-e", "HEAD:$path").exit != 0) {
                out[path] = runCatching { f.readLines() }.getOrDefault(emptyList()).mapIndexed { i, t -> AddedLine(i + 1, t) }.toMutableList()
            }
        }
        return out
    }
}
