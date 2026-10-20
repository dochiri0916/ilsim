package com.dochiri.ilsim.runner.testing

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import org.w3c.dom.Element

object TestFailures {
    data class Failure(val test: String, val message: String, val location: String?)

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

    fun render(failures: List<Failure>): String = failures.joinToString("\n") { f ->
        "- ${f.test}${f.location?.let { " ($it)" } ?: ""}: ${f.message.lines().first()}" +
            f.message.lines().drop(1).take(5).joinToString("") { "\n    $it" }
    }

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
