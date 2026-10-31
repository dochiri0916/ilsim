package com.dochiri.ilsim.runner.convention

import java.nio.file.Path

internal object DocMutations {
    class Variant(val path: String, val lines: List<String>)

    private const val MAX_TARGETS = 2
    private const val PROBE = "ilsimProbe"
    private val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private val sourceRoot = Regex("""^(.*?src/(?:main|test)/[^/]+)/""")

    fun variants(rule: ConventionRule, file: ConvFile): List<Variant> {
        val params = rule.params
        val java = file.path.endsWith(".java")
        val targets = runCatching { DocProbes.targets(Facts(listOf(file)), params) }.getOrDefault(emptyList()).take(MAX_TARGETS)
        return when (rule.kind) {
            "doc-file-location" -> listOfNotNull(relocate(file, params))
            "doc-name-pattern" -> targets.mapNotNull { rename(it, params) }
            "doc-required-annotation" -> targets.map { strip(it, params.getValue("annotation"), "inherit" in params) }
            "doc-forbidden-annotation" -> forbiddenSpots(file, params, targets).map { annotate(file, it, params.getValue("annotation").first()) }
            "doc-required-code" -> targets.mapNotNull { blank(it, params) }
            "doc-max-size" -> targets.mapNotNull { grow(it, params, java) }
            "doc-max-occurrences" -> targets.mapNotNull { repeatPattern(it, params, java) }
            else -> emptyList()
        }
    }

    fun synthesize(rule: ConventionRule, facts: Facts): ConvFile? {
        if (rule.kind != "doc-file-location") return null
        val names = rule.params.getValue("name").map(::Regex)
        val test = rule.params["scope"]?.firstOrNull() == "test"
        val example = rule.params.getValue("name").mapNotNull { RegexExample.of(it) }.firstOrNull { ex -> identifier.matches(ex) && names.any { it.matches(ex) } } ?: return null
        val root = facts.files.firstOrNull { it.test == test }?.path?.let { sourceRoot.find(it)?.groupValues?.get(1) } ?: if (test) "src/test/kotlin" else "src/main/kotlin"
        val path = outside(root, "$example.kt", rule.params.getValue("location")) ?: return null
        return FactScanner.parse(path, "kt", test, listOf("package ilsimprobe", "", "class $example"))
    }

    private fun outside(root: String, fileName: String, locations: List<String>): String? {
        val places = locations.map(DocProbes::glob)
        return listOf("$root/ilsimprobe/$fileName", "ilsimprobe/$fileName", "$root/$fileName").firstOrNull { p -> places.none { it.matches(Path.of(p)) } }
    }

    private fun relocate(file: ConvFile, params: Map<String, List<String>>): Variant? {
        val names = params.getValue("name").map(::Regex)
        if (names.none { it.matches(DocProbes.baseName(file.path)) }) return null
        val root = sourceRoot.find(file.path)?.groupValues?.get(1) ?: file.path.substringBeforeLast('/', ".")
        return outside(root, file.path.substringAfterLast('/'), params.getValue("location"))?.let { Variant(it, file.lines) }
    }

    private fun rename(t: DocTarget, params: Map<String, List<String>>): Variant? {
        val patterns = params.getValue("pattern").map(::Regex)
        val filters = params["name"].orEmpty().map(::Regex)
        val bases = listOf(t.name) + params["name"].orEmpty().mapNotNull { RegexExample.of(it) }
        val candidates = bases.flatMap { b ->
            listOf(b, "ilsim_$b", "Ilsim_$b", "${b}_probe", "${b}Probe", "probe$b", b.replaceFirstChar { it.lowercaseChar() }, b.replaceFirstChar { it.uppercaseChar() }, "x$b", "${b}X")
        } + listOf("ilsimProbe", "IlsimProbe", "ilsim_probe", "ILSIM_PROBE", "probeCase", "probe_case")
        val name = candidates.distinct().firstOrNull { c -> c != t.name && identifier.matches(c) && (filters.isEmpty() || filters.any { it.matches(c) }) && patterns.none { it.matches(c) } } ?: return null
        val lines = t.file.lines
        return when (params.getValue("target").first()) {
            "file" -> Variant(t.file.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + name + "." + t.file.path.substringAfterLast('.'), lines)
            "class", "test-class" -> {
                val decl = Regex("""\b(class|interface|object|record|enum)\s+${Regex.escape(t.name)}\b""")
                val at = lines.indices.firstOrNull { it >= t.line - 1 && decl.containsMatchIn(lines[it]) } ?: lines.indices.firstOrNull { decl.containsMatchIn(lines[it]) } ?: return null
                Variant(t.file.path, lines.mapIndexed { j, l -> if (j == at) l.replaceFirst(decl, "$1 $name") else l })
            }
            else -> {
                val token = Regex("""(`${Regex.escape(t.name)}`|\b${Regex.escape(t.name)}\b)(\s*\()""")
                val at = listOf(t.line - 1, t.line, t.line - 2).firstOrNull { it in lines.indices && token.containsMatchIn(lines[it]) } ?: return null
                Variant(t.file.path, lines.mapIndexed { j, l -> if (j == at) l.replaceFirst(token, "$name$2") else l })
            }
        }
    }

    private fun strip(t: DocTarget, annotations: List<String>, inherit: Boolean): Variant {
        val remove = Regex("""@(?:[\w.]+\.)?(?:${annotations.joinToString("|") { Regex.escape(it) }})\b(?:\([^)]*\))?\s*""")
        val lines = t.file.lines.toMutableList()
        for (d in listOfNotNull(t, DocProbes.owner(t).takeIf { inherit })) {
            val end = (d.line - 1).coerceIn(0, lines.lastIndex)
            var start = end
            while (start > 0 && lines[start - 1].trim().let { it.startsWith("@") || it.startsWith(")") || it.endsWith(",") || it.endsWith("(") }) start--
            for (i in start..end) lines[i] = lines[i].replace(remove, "")
        }
        return Variant(t.file.path, lines)
    }

    private fun forbiddenSpots(file: ConvFile, params: Map<String, List<String>>, targets: List<DocTarget>): List<Int> =
        if ("target" in params) targets.map { it.line } else (file.types.filter { it.topLevel }.map { it.line }.take(1) + file.annotated.map { it.line }.take(1)).distinct()

    private fun annotate(file: ConvFile, line: Int, annotation: String): Variant {
        val at = (line - 1).coerceIn(0, file.lines.size)
        val indent = file.lines.getOrNull(at)?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
        return Variant(file.path, file.lines.take(at) + "$indent@$annotation" + file.lines.drop(at))
    }

    private fun inner(t: DocTarget): IntRange? {
        val body = t.body ?: return null
        val range = if (t.line <= 1 && body.first == 0 && body.last == t.file.lines.lastIndex) body else (body.first + 1) until body.last
        return range.takeIf { !it.isEmpty() }
    }

    private fun blank(t: DocTarget, params: Map<String, List<String>>): Variant? {
        val range = inner(t) ?: return null
        val patterns = params.getValue("pattern").map(::Regex)
        val view = DocProbes.text(t.file, params)
        val lines = t.file.lines.toMutableList()
        var changed = false
        for (i in range) if (patterns.any { it.containsMatchIn(view.getOrElse(i) { "" }) }) { lines[i] = ""; changed = true }
        return if (changed) Variant(t.file.path, lines) else null
    }

    private fun insertAt(t: DocTarget): Int? {
        val body = t.body ?: return null
        return if (t.line <= 1 && body.first == 0) body.last.coerceAtLeast(0) else body.first + 1
    }

    private fun grow(t: DocTarget, params: Map<String, List<String>>, java: Boolean): Variant? {
        val at = insertAt(t) ?: return null
        val max = params.getValue("max").first().toInt()
        val added = if (params.getValue("measure").first() == "nesting") {
            val depth = max + 1
            (0 until depth).map { "        ".plus("    ".repeat(it)) + "if (true) {" } + "        ${"    ".repeat(depth)}${if (java) "int $PROBE = 1;" else "val $PROBE = 1"}" + (0 until depth).reversed().map { "        ".plus("    ".repeat(it)) + "}" }
        } else List(max + 1) { if (java) "        int $PROBE$it = $it;" else "        val $PROBE$it = $it" }
        return Variant(t.file.path, t.file.lines.take(at) + added + t.file.lines.drop(at))
    }

    private fun repeatPattern(t: DocTarget, params: Map<String, List<String>>, java: Boolean): Variant? {
        val at = insertAt(t) ?: return null
        val example = params.getValue("pattern").mapNotNull { RegexExample.of(it) }.firstOrNull() ?: return null
        val max = params.getValue("max").first().toInt()
        val line = when (params["text"]?.firstOrNull()) {
            "raw" -> "        $example"
            "string" -> if (java) "        String $PROBE = \"$example\";" else "        val $PROBE = \"$example\""
            else -> "        $example"
        }
        return Variant(t.file.path, t.file.lines.take(at) + List(max + 1) { line } + t.file.lines.drop(at))
    }
}
