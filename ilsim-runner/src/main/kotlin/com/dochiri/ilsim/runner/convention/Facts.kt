package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.profile.ModuleInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines

internal class Decl(val kind: String, val name: String, val line: Int, val annotations: List<String>, val topLevel: Boolean)

internal class ConvFile(
    val path: String,
    val lang: String,
    val test: Boolean,
    val pkg: String,
    val lines: List<String>,
    val types: List<Decl>,
    val annotated: List<Decl>,
) {
    var module: String = ""

    val view: CodeView by lazy { CodeText.scrub(lines) }

    val uncommented: List<String> by lazy { CodeText.scrub(lines, keepStrings = true).code }
}

internal class Facts(val files: List<ConvFile>, val dependencies: Map<String, List<String>> = emptyMap()) {
    val main = files.filter { !it.test }
    val tests = files.filter { it.test }
}

internal object FactScanner {
    private val pkgRegex = Regex("""^\s*package\s+([\w.`]+)""")
    private val typeRegex = Regex("""^(\s*)(?:[a-z]+\s+)*?(?:class|interface|object|record)\s+(\w+)""")
    private val ktFun = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(`[^`]+`|\w+)\s*\(""")
    private val javaMethod = Regex("""(\w+)\s*\(""")
    private val field = Regex("""(?:\bva[lr]\s+(\w+)|(\w+)\s*(?:=|;|$))""")
    private val sourceExt = setOf("kt", "java")

    fun scan(root: Path, modules: List<ModuleInfo>): Facts {
        val out = mutableListOf<ConvFile>()
        val seen = mutableSetOf<Path>()
        for (m in modules) {
            val roots = m.sourceRoots.filter { it.contains("src/main") }.flatMap { listOf(it to false, it.replaceFirst("src/main", "src/test") to true) } +
                m.sourceRoots.filterNot { it.contains("src/main") }.map { it to true }
            for ((r, test) in roots.distinctBy { it.first }) {
                val dir = root.resolve(r)
                if (!dir.isDirectory()) continue
                Files.walk(dir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.extension in sourceExt && seen.add(it) }
                        .forEach { f -> parse(root, f, test)?.also { it.module = m.name }?.let(out::add) }
                }
            }
        }
        return Facts(out, modules.associate { it.name to it.projectDependencies })
    }

    internal fun parse(root: Path, file: Path, test: Boolean): ConvFile? =
        parse(root.relativize(file).toString(), file.extension, test, file.readLines())

    internal fun parse(path: String, ext: String, test: Boolean, lines: List<String>): ConvFile? {
        val pkg = lines.firstNotNullOfOrNull { pkgRegex.find(it)?.groupValues?.get(1)?.replace("`", "") } ?: return null
        val types = mutableListOf<Decl>()
        val annotated = mutableListOf<Decl>()
        var pending = mutableListOf<String>()
        var inBlockComment = false
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            if (inBlockComment) { if ("*/" in t) inBlockComment = false; i++; continue }
            if (t.startsWith("/*")) { if ("*/" !in t) inBlockComment = true; i++; continue }
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*")) { i++; continue }

            var rest = t
            var consumed = 0
            val names = mutableListOf<String>()
            while (rest.startsWith("@") && !rest.startsWith("@interface")) {
                val (name, after, extra) = readAnnotation(rest, lines, i + consumed)
                names += name; rest = after.trim(); consumed += extra
            }
            pending.addAll(names)

            if (rest.isEmpty()) { i += 1 + consumed; continue }
            val declLine = i + consumed + 1
            val type = typeRegex.find(rest)
            if (type != null && !rest.startsWith("return ")) {
                val d = Decl("type", type.groupValues[2], declLine, pending.toList(), topLevel = raw.length == raw.trimStart().length)
                types += d
                if (pending.isNotEmpty()) annotated += d
            } else if (pending.isNotEmpty()) {
                val kind: String
                val name: String
                val kt = ktFun.find(rest)
                if (kt != null) { kind = "func"; name = kt.groupValues[1].trim('`') }
                else if ("(" in rest.substringBefore("=") && ext == "java") {
                    kind = "func"; name = javaMethod.find(rest)?.groupValues?.get(1) ?: "?"
                } else {
                    kind = "field"; name = field.find(rest)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: "?"
                }
                annotated += Decl(kind, name, declLine, pending.toList(), topLevel = false)
            }
            pending = mutableListOf()
            i += 1 + consumed
        }
        return ConvFile(path, if (ext == "kt") "kotlin" else "java", test, pkg, lines, types, annotated)
    }

    private fun readAnnotation(text: String, lines: List<String>, index: Int): Triple<String, String, Int> {
        val m = Regex("""^@(?:\w+:)?([\w.]+)""").find(text) ?: return Triple("?", "", 0)
        var rest = text.substring(m.value.length)
        var extra = 0
        if (rest.startsWith("(")) {
            var depth = 0
            var pos = 0
            var current = rest
            while (true) {
                while (pos < current.length) {
                    when (current[pos]) { '(' -> depth++; ')' -> depth-- }
                    pos++
                    if (depth == 0) break
                }
                if (depth == 0 || index + extra + 1 >= lines.size) break
                extra++
                current = "$current ${lines[index + extra].trim()}"
            }
            rest = current.substring(minOf(pos, current.length))
        }
        return Triple(m.groupValues[1].substringAfterLast('.'), rest, extra)
    }
}
