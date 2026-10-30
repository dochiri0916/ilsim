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
    private val sourceExtensions = setOf("java", "kt", "groovy", "scala", "ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs", "vue", "py")
    private val annotations = mapOf(
        "Suppress" to "@Suppress", "SuppressWarnings" to "@SuppressWarnings", "SuppressFBWarnings" to "@SuppressFBWarnings",
        "SuppressLint" to "@SuppressLint", "ArchIgnore" to "@ArchIgnore",
    )
    private val generated = Regex("""@\s*(?:file:)?$QUALIFIER(\w*Generated\w*)\b""")
    private val comments = listOf(
        Regex("""//\s*noinspection\b""") to "noinspection",
        Regex("""\bNOSONAR\b""") to "NOSONAR",
        Regex("""\bNOPMD\b""") to "NOPMD",
        Regex("""ktlint-disable|detekt:\s*disable""") to "ktlint/detekt 끄기",
        Regex("""CHECKSTYLE\s*[:.]?\s*OFF""", RegexOption.IGNORE_CASE) to "CHECKSTYLE:OFF",
        Regex("""eslint-disable|@ts-ignore|@ts-nocheck|@ts-expect-error""") to "eslint/ts 끄기",
        Regex("""(istanbul|c8|v8)\s+ignore""") to "커버리지 제외 주석",
        Regex("""#\s*(ruff:\s*)?(noqa|type:\s*ignore|pylint:\s*disable|pragma:\s*no\s*(cover|branch)|nosec)""") to "noqa/pylint/커버리지 제외",
    )
    private val aliasImport = Regex("""^\s*import\s+[\w.]+\.(\w+)\s+as\s+(\w+)""")
    private val configFiles = listOf(
        Regex("""(^|/)detekt[^/]*\.ya?ml$"""), Regex("""(^|/)(checkstyle|pmd|spotbugs|findbugs|detekt|codenarc|ktlint|lint)/"""),
        Regex("""(^|/)(checkstyle|pmd|spotbugs|findbugs|codenarc)[^/]*\.xml$"""), Regex("""(^|/)[^/]*(suppressions?|baseline|exclude-filter|excludes?)[^/]*\.(xml|ya?ml|json|txt)$""", RegexOption.IGNORE_CASE),
        Regex("""(^|/)\.editorconfig$"""), Regex("""(^|/)lint\.xml$"""), Regex("""(^|/)sonar-project\.properties$"""),
        Regex("""(^|/)archunit[^/]*\.properties$"""), Regex("""(^|/)archunit_store/"""),
        Regex("""(^|/)\.eslintrc[^/]*$"""), Regex("""(^|/)\.?eslint\.config\.[^/]+$"""), Regex("""(^|/)\.(eslint|prettier|stylelint)ignore$"""),
        Regex("""(^|/)\.?(ruff|flake8|pylintrc|mypy|coveragerc|pyrightconfig)[^/]*$"""),
        Regex("""(^|/)\.ilsim-policy\.json$"""),
    )
    private val sectionConfig = Regex("""(^|/)(setup\.cfg|tox\.ini|pyproject\.toml|tsconfig[^/]*\.json|jsconfig\.json)$""")
    private val sectionWeakening = Regex("""^\s*\[(flake8|mypy|pylint|pycodestyle|pydocstyle|isort|coverage|tool\.(ruff|mypy|pylint|coverage|pyright))\b|"(strict\w*|noImplicit\w+|noUnused\w+|noFallthrough\w+|noUnchecked\w+|alwaysStrict)"\s*:\s*false|"skipLibCheck"\s*:\s*true""")
    private val buildFiles = Regex("""(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|pom\.xml|package\.json|pyproject\.toml|[^/]+\.gradle(\.kts)?)$|(^|/)\.mvn/|(^|/)(buildSrc|build-logic|gradle/plugins)/""")
    private val weakening = listOf(
        Regex("""\b(is)?[iI]gnoreFailures\s*(\.set\s*\(|[=(:]|\s)\s*true""") to "검사 실패를 무시(ignoreFailures)",
        Regex("""\bfailOn(Violation|Error|Warning)s?\s*(\.set\s*\(|[=(:]|\s)\s*false""") to "위반 시 실패 끄기",
        Regex("""<([\w.-]*\.)?(skip|skipTests|skipITs|testFailureIgnore|failure\.ignore|test\.skip)>\s*true\s*<|<(failOnViolation|failsOnError|failOnError|failOnWarning)>\s*false\s*<""") to "Maven 검사 건너뛰기",
        Regex("""(-x|--exclude-task)\s+:?\S*(test|check|detekt|ktlint|checkstyle|pmd|spotbugs|jacoco|pitest)""", RegexOption.IGNORE_CASE) to "검사 작업 제외(-x)",
        Regex("""excludedTaskNames""") to "검사 작업 제외(excludedTaskNames)",
        Regex("""-D(skipTests|skipITs|maven\.test\.skip|maven\.test\.failure\.ignore)(?!=false)\b|\b(skipTests|maven\.test\.skip)\s*[=:]\s*true""") to "테스트 건너뛰기",
        Regex("""--passWithNoTests|--no-verify""") to "검사 건너뛰기 옵션",
        Regex("""\|\|\s*(true|exit\s+0|:)\b|;\s*exit\s+0\b""") to "실패해도 성공으로 끝내기(|| true)",
        Regex("""exclude(TestsMatching)?\s*\(\s*["']\*\*/\*["']|excludeTestsMatching\s*\(\s*["']\*["']""") to "모든 테스트 제외",
        Regex("""\bminimum\s*(\.set\s*\(|[=:(]|\s)\s*["']?0(\.0*)?["']?(\.toBigDecimal\(\)|\b)|<minimum>\s*0(\.0*)?\s*<""") to "커버리지 하한 0",
    )

    private val disabling = Regex("""[eE]nabled\s*(\.set\s*\(|[=(:])\s*false|\bsetEnabled\s*\(\s*false|\bonlyIf\s*\{\s*false""")
    private val checkTask = Regex("""\b(test|check|detekt|ktlint|checkstyle|pmd|spotbugs|jacoco|pitest|lint|archTest|integrationTest)\w*""", RegexOption.IGNORE_CASE)

    fun scan(root: Path, changed: Set<String>, allowed: (String) -> Boolean = { false }): List<Violation> =
        scan(changed, added(root, changed), allowed)

    internal fun scan(changed: Set<String>, added: Map<String, List<AddedLine>>, allowed: (String) -> Boolean = { false }): List<Violation> {
        val out = mutableListOf<Violation>()
        for (path in changed.sorted()) {
            if (allowed(path)) continue
            val lines = added[path].orEmpty()
            if (configFiles.any { it.containsMatchIn(path) } || sectionConfig.containsMatchIn(path) && lines.any { sectionWeakening.containsMatchIn(it.text) }) {
                out += Violation("BYPASS-002", "BLOCK", path, (lines.firstOrNull { sectionWeakening.containsMatchIn(it.text) } ?: lines.firstOrNull())?.line ?: 1, "검사 설정 `${path.substringAfterLast('/')}` 을 바꿨습니다. 검사 기준은 사용자만 바꿉니다")
                continue
            }
            if (buildFiles.containsMatchIn(path)) {
                out += weakened(path, lines)
                continue
            }
            if (Gate.isTestPath(path) && "src/main/" !in path || path.substringAfterLast('.') !in sourceExtensions) continue
            out += suppressed(path, lines)
        }
        return out
    }

    private fun suppressed(path: String, lines: List<AddedLine>): List<Violation> {
        val names = annotations + lines.mapNotNull { aliasImport.find(it.text) }.filter { it.groupValues[1] in annotations }.associate { it.groupValues[2] to annotations.getValue(it.groupValues[1]) }
        val annotation = Regex("""@\s*(?:file:)?$QUALIFIER(${names.keys.joinToString("|")})\b""")
        return lines.mapNotNull { l ->
            val reason = annotation.find(l.text)?.let { names[it.groupValues[1]] }
                ?: generated.find(l.text)?.let { "@${it.groupValues[1]}(커버리지 제외)" }
                ?: comments.firstOrNull { it.first.containsMatchIn(l.text) }?.second
            reason?.let { Violation("BYPASS-001", "BLOCK", path, l.line, "검사를 끄는 $it 를 새로 넣었습니다. 경고를 숨기지 말고 원인을 고치세요") }
        }
    }

    private fun weakened(path: String, lines: List<AddedLine>): List<Violation> {
        val text = lines.joinToString("\n") { it.text }
        val starts = lines.runningFold(0) { at, l -> at + l.text.length + 1 }
        fun lineAt(offset: Int) = lines[(starts.indexOfLast { it <= offset }).coerceIn(0, lines.lastIndex)].line
        val found = weakening.flatMap { (regex, reason) -> regex.findAll(text).map { lineAt(it.range.first) to reason } }
        val disabled = lines.mapIndexedNotNull { i, l ->
            if (!disabling.containsMatchIn(l.text)) return@mapIndexedNotNull null
            val scope = listOfNotNull(l.text.replace(disabling, ""), enclosing(lines, i))
            (l.line to "검사 작업 끄기(enabled = false, onlyIf { false })").takeIf { scope.any { checkTask.containsMatchIn(it) } }
        }
        return (found + disabled).distinctBy { it.first }.sortedBy { it.first }.map { (line, reason) -> Violation("BYPASS-003", "BLOCK", path, line, "빌드 설정에서 검증을 약하게 하는 변경입니다: $reason") }
    }

    private fun enclosing(lines: List<AddedLine>, index: Int): String? {
        var depth = 0
        for (j in index - 1 downTo 0) {
            val t = lines[j].text
            depth += t.count { it == '}' } - t.count { it == '{' }
            if (depth < 0) return t
        }
        return null
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
