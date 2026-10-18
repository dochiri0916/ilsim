package com.dochiri.ilsim.runner.testing

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import org.w3c.dom.Element

/**
 * 테스트 명령의 출력 대신 JUnit XML 리포트에서 실패한 테스트와 그 이유를 뽑는다.
 * Gradle 출력 끝부분은 종료 로그뿐이라 무엇이 왜 실패했는지 알 수 없기 때문이다.
 */
object TestFailures {
    data class Failure(val test: String, val message: String, val location: String?)

    /** [since] 이후에 쓰인 리포트의 실패·오류 테스트(최대 [limit]건). 리포트가 없으면 빈 목록. */
    fun collect(root: Path, since: Instant, limit: Int = 10): List<Failure> {
        val out = mutableListOf<Failure>()
        Files.walk(root, 10).use { s ->
            s.filter { p ->
                Files.isRegularFile(p) && p.extension == "xml" &&
                    p.toString().let { "test-results" in it || "surefire-reports" in it || "failsafe-reports" in it } &&
                    !p.getLastModifiedTime().toInstant().isBefore(since)
            }.forEach { p -> out += parse(p) }
        }
        return out.distinctBy { it.test }.take(limit)
    }

    private fun parse(file: Path): List<Failure> = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val cases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase")
        (0 until cases.length).mapNotNull { i ->
            val case = cases.item(i) as Element
            val problem = listOf("failure", "error").firstNotNullOfOrNull { tag ->
                case.getElementsByTagName(tag).item(0) as? Element
            } ?: return@mapNotNull null
            val cls = case.getAttribute("classname")
            val stack = problem.textContent.orEmpty()
            val message = problem.getAttribute("message").ifBlank { stack.lineSequence().firstOrNull().orEmpty() }
            val simple = cls.substringAfterLast('.')
            val location = stack.lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("at ") && simple.isNotEmpty() && "$simple." in it }
                ?.substringAfter('(')?.substringBefore(')')
            Failure("$simple.${case.getAttribute("name")}", message.trim().take(500), location)
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** 실패 목록을 사람이 읽고 Claude 가 고칠 수 있는 형태로 적는다. */
    fun render(failures: List<Failure>): String = failures.joinToString("\n") { f ->
        "- ${f.test}${f.location?.let { " ($it)" } ?: ""}: ${f.message.lines().first()}" +
            f.message.lines().drop(1).take(5).joinToString("") { "\n    $it" }
    }

    /** 리포트가 없을 때 쓸 출력 요약. JVM 경고·Spring 로그·Gradle 안내 같은 잡음 줄을 뺀다. */
    fun trimOutput(output: String, maxLines: Int = 60): String = output.lineSequence()
        .filterNot { line ->
            NOISE.any { it.containsMatchIn(line) }
        }
        .filter { it.isNotBlank() }
        .toList().takeLast(maxLines).joinToString("\n")

    private val NOISE = listOf(
        Regex("""^\d{4}-\d{2}-\d{2}T[\d:.+]+\s+(INFO|DEBUG|TRACE)\b"""),
        Regex("""^OpenJDK .*warning"""),
        Regex("""^\* (Try|Get more help)"""),
        Regex("""^> (Run with|Get more help)"""),
        Regex("""^BUILD FAILED"""),
    )
}
