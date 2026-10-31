package com.dochiri.ilsim.runner.testing

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.readText

data class MutationResult(val killed: Int, val total: Int, val survivors: List<String>) {
    val score: Double get() = if (total == 0) 0.0 else killed.toDouble() / total
}

object MutationReport {
    const val THRESHOLD = 0.6

    private val killedStatuses = setOf("KILLED", "TIMED_OUT", "MEMORY_ERROR")

    private val noiseText = Regex("""slf4j|\bLogger\b|\bLog\b|java/io/PrintStream|kotlin/io/ConsoleKt|kotlin/jvm/internal/Intrinsics|Intrinsics\.""")
    private val noiseMethods = setOf("toString", "hashCode")

    fun evaluate(root: Path, since: Instant, changedSources: Set<String>): MutationResult? {
        val names = changedSources.map { it.substringAfterLast('/') }.toSet()
        if (names.isEmpty()) return null
        val best = linkedMapOf<String, Pair<Boolean, String>>()
        Files.walk(root, 10).use { s ->
            s.filter { Files.isRegularFile(it) && it.name == "mutations.xml" && !it.getLastModifiedTime().toInstant().isBefore(since) }.forEach { p ->
                Regex("""<mutation\s+([^>]*)>(.*?)</mutation>""", RegexOption.DOT_MATCHES_ALL).findAll(p.readText()).forEach { m ->
                    val status = Regex("""status='?"?(\w+)""").find(m.groupValues[1])?.groupValues?.get(1) ?: return@forEach
                    val body = m.groupValues[2]
                    val file = tag(body, "sourceFile")
                    if (file !in names || status == "NON_VIABLE") return@forEach
                    if (tag(body, "mutatedMethod") in noiseMethods || noiseText.containsMatchIn(tag(body, "description") + " " + tag(body, "mutatedClass"))) return@forEach
                    val key = listOf("mutatedClass", "mutatedMethod", "methodDescription", "lineNumber", "mutator", "indexes", "description").joinToString("|") { tag(body, it) }
                    val killed = status in killedStatuses
                    if (best[key]?.first != true) best[key] = killed to "$file:${tag(body, "lineNumber")} ${tag(body, "description")} ($status)"
                }
            }
        }
        return if (best.isEmpty()) null else MutationResult(best.values.count { it.first }, best.size, best.values.filterNot { it.first }.map { it.second })
    }

    private fun tag(body: String, name: String) = Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim().orEmpty()
}
