package com.dochiri.ilsim.runner.testing

import com.dochiri.ilsim.runner.Shell
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name
import kotlin.io.path.readText

data class MutationResult(val killed: Int, val total: Int, val survivors: List<String>, val outside: Int = 0) {
    val score: Double get() = if (total == 0) 0.0 else killed.toDouble() / total
}

object MutationReport {
    const val THRESHOLD = 0.6

    private val killedStatuses = setOf("KILLED", "TIMED_OUT", "MEMORY_ERROR")

    private val noiseText = Regex("""slf4j|\bLogger\b|\bLog\b|java/io/PrintStream|kotlin/io/ConsoleKt|kotlin/jvm/internal/Intrinsics|Intrinsics\.""")
    private val noiseMethods = setOf("toString", "hashCode")

    private val hunk = Regex("""^@@ -\S+ \+(\d+)(?:,(\d+))? @@""")

    fun changedLines(root: Path, sources: Set<String>): Map<String, Set<Int>?> = sources.groupBy { it.substringAfterLast('/') }.mapValues { (_, paths) ->
        val sets: List<Set<Int>?> = paths.map { path -> lines(root, path) }
        if (sets.any { it == null }) null else sets.flatMap { it.orEmpty() }.toSet()
    }

    private fun lines(root: Path, path: String): Set<Int>? {
        if (Shell.git(root, "ls-files", "--error-unmatch", path).exit != 0) return null
        val diff = Shell.git(root, "diff", "-U0", "HEAD", "--", path).takeIf { it.exit == 0 }?.output ?: return emptySet()
        return diff.lines().flatMap { line ->
            val m = hunk.find(line) ?: return@flatMap emptyList<Int>()
            val start = m.groupValues[1].toInt()
            (start until start + m.groupValues[2].ifEmpty { "1" }.toInt()).toList()
        }.toSet()
    }

    fun evaluate(root: Path, since: Instant, changedSources: Set<String>, lines: Map<String, Set<Int>?> = emptyMap()): MutationResult? {
        val names = changedSources.map { it.substringAfterLast('/') }.toSet()
        if (names.isEmpty()) return null
        val best = linkedMapOf<String, Pair<Boolean, String>>()
        val outside = mutableSetOf<String>()
        Files.walk(root, 10).use { s ->
            s.filter { Files.isRegularFile(it) && it.name == "mutations.xml" && !it.getLastModifiedTime().toInstant().isBefore(since) }.forEach { p ->
                Regex("""<mutation\s+([^>]*)>(.*?)</mutation>""", RegexOption.DOT_MATCHES_ALL).findAll(p.readText()).forEach { m ->
                    val status = Regex("""status='?"?(\w+)""").find(m.groupValues[1])?.groupValues?.get(1) ?: return@forEach
                    val body = m.groupValues[2]
                    val file = tag(body, "sourceFile")
                    if (file !in names || status == "NON_VIABLE") return@forEach
                    if (tag(body, "mutatedMethod") in noiseMethods || noiseText.containsMatchIn(tag(body, "description") + " " + tag(body, "mutatedClass"))) return@forEach
                    val key = listOf("mutatedClass", "mutatedMethod", "methodDescription", "lineNumber", "mutator", "indexes", "description").joinToString("|") { tag(body, it) }
                    val allowed = lines[file]
                    if (allowed != null && tag(body, "lineNumber").toIntOrNull() !in allowed) { outside += key; return@forEach }
                    val killed = status in killedStatuses
                    if (best[key]?.first != true) best[key] = killed to "$file:${tag(body, "lineNumber")} ${tag(body, "description")} ($status)"
                }
            }
        }
        return if (best.isEmpty() && outside.isEmpty()) null else MutationResult(best.values.count { it.first }, best.size, best.values.filterNot { it.first }.map { it.second }, outside.size)
    }

    private fun tag(body: String, name: String) = Regex("""<$name>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim().orEmpty()
}
