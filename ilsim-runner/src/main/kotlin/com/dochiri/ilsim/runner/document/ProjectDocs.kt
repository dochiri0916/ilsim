package com.dochiri.ilsim.runner.document

import com.dochiri.ilsim.runner.ProjectEntry
import com.dochiri.ilsim.runner.Shell
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

data class DocSection(val path: String, val line: Int, val heading: String, val text: String)

object ProjectDocs {
    const val MAX_SECTION = 4000
    const val MAX_FILE_BYTES = 200_000L
    val extensions = setOf("md", "markdown", "adoc", "rst")

    fun files(root: Path, project: ProjectEntry, otherProjects: Collection<Path>, feature: String?): Map<String, Path> {
        val base = real(root)
        val nested = otherProjects.map(::real).filter { it != base && it.startsWith(base) }.map { relative(base, it) }
        val skipped = nested + project.docsExclude.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() } + listOfNotNull(feature?.let { "specs/$it" })
        val tracked = Shell.gitOk(base, "-c", "core.quotepath=false", "ls-files", "--", *extensions.map { "*.$it" }.toTypedArray())
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .associateWith { base.resolve(it) }
        val extra = project.docs.flatMap { added ->
            val path = Path.of(added).let { if (it.isAbsolute) it else base.resolve(it) }.normalize()
            when {
                path.isDirectory() -> Files.walk(path, 8).use { s -> s.filter { it.isRegularFile() && it.extension.lowercase() in extensions }.toList() }
                path.isRegularFile() -> listOf(path)
                else -> emptyList()
            }
        }.associateBy { if (it.startsWith(base)) relative(base, it) else it.toString() }
        return (tracked + extra).filterKeys { key -> skipped.none { key == it || key.startsWith("$it/") } }
            .filterValues { it.exists() && it.isRegularFile() && it.fileSize() <= MAX_FILE_BYTES }
            .toSortedMap()
    }

    val buildFiles = setOf("build.gradle", "build.gradle.kts", "pom.xml", "package.json", "Cargo.toml", "go.mod", "pyproject.toml", "setup.py", "Gemfile", "composer.json")

    fun modules(root: Path): Set<String> =
        Shell.git(real(root), "-c", "core.quotepath=false", "ls-files").output.lines().map { it.trim() }
            .filter { it.count { c -> c == '/' } == 1 && it.substringAfter('/') in buildFiles }
            .map { it.substringBefore('/') }.toSortedSet()

    fun sections(files: Map<String, Path>): List<DocSection> = files.flatMap { (label, path) -> split(label, runCatching { path.readText() }.getOrDefault("")) }

    fun split(label: String, text: String): List<DocSection> {
        val out = mutableListOf<DocSection>()
        val headings = sortedMapOf<Int, String>()
        var body = StringBuilder()
        var start = 1
        var fenced = false
        val adoc = label.lowercase().endsWith(".adoc")
        fun flush() {
            val content = body.toString().trim()
            val hasBody = content.lines().drop(if (start > 1 || headings.isNotEmpty()) 1 else 0).any { it.isNotBlank() }
            if (content.isNotEmpty() && hasBody) chunk(content).forEach { out += DocSection(label, start, headings.values.joinToString(" > "), it) }
            body = StringBuilder()
        }
        text.lines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~") || (adoc && trimmed == "----")) fenced = !fenced
            val heading = if (fenced) null else (if (adoc) ADOC_HEADING else MD_HEADING).matchEntire(line)
            if (heading != null) {
                flush()
                val level = heading.groupValues[1].length
                headings.keys.filter { it >= level }.forEach(headings::remove)
                headings[level] = heading.groupValues[2].trim()
                start = index + 1
            }
            body.appendLine(line)
        }
        flush()
        return out
    }

    private fun chunk(text: String): List<String> {
        if (text.length <= MAX_SECTION) return listOf(text)
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        for (paragraph in text.split(Regex("\n\\s*\n"))) {
            if (current.isNotEmpty() && current.length + paragraph.length + 2 > MAX_SECTION) { chunks += current.toString().trim(); current = StringBuilder() }
            paragraph.chunked(MAX_SECTION).forEach { part ->
                if (current.isNotEmpty() && current.length + part.length + 2 > MAX_SECTION) { chunks += current.toString().trim(); current = StringBuilder() }
                current.append(part).append("\n\n")
            }
        }
        if (current.isNotBlank()) chunks += current.toString().trim()
        return chunks
    }

    private val MD_HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val ADOC_HEADING = Regex("""^(={1,6})\s+(.+)$""")

    private fun relative(base: Path, path: Path): String = base.relativize(path).toString().replace('\\', '/')

    private fun real(path: Path): Path = path.toAbsolutePath().normalize().let { if (it.exists()) it.toRealPath() else it }
}
