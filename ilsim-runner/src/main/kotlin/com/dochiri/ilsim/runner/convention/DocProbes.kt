package com.dochiri.ilsim.runner.convention

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.math.abs

internal class DocTarget(val file: ConvFile, val name: String, val line: Int, val annotations: List<String>, val body: IntRange?, val nesting: Int?) {
    val forms: List<String>
        get() = if (file.lines.getOrNull(line - 1)?.contains("`$name`") == true) listOf(name, "`$name`") else listOf(name)
}

internal abstract class DocProbe(kind: String, val targets: Set<String>, val required: Set<String>, val optional: Set<String>) : Probe(kind, minSamples = 1) {
    override fun candidates(f: Facts): List<Candidate> = emptyList()
}

internal object DocProbes {
    private val scopes = setOf("main", "test", "all")
    private val texts = setOf("code", "string", "raw")
    private val commentMarkers = listOf("//", "/\\*", "\\*/", "#", "<!--")
    private val measures = setOf("lines", "nesting")
    private val testAnnotations = setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")
    private val regexKeys = listOf("pattern", "import", "name", "value")
    private val globKeys = listOf("path", "location")
    private val annotationKeys = setOf("annotation", "when")

    val all: List<DocProbe> = listOf(
        ForbiddenImport, RequiredAnnotation, ForbiddenAnnotation, NamePattern, ForbiddenCode, RequiredCode, FileLocation, MaxSize, MaxOccurrences,
    )

    fun byKind(kind: String): DocProbe? = all.firstOrNull { it.kind == kind }

    fun normalize(kind: String, raw: Map<String, List<String>>): Map<String, List<String>> {
        val probe = byKind(kind) ?: throw IllegalArgumentException("알 수 없는 규칙 종류: $kind")
        val params = raw.mapValues { (key, values) ->
            values.map { it.trim() }.filter { it.isNotEmpty() }.map { if (key in annotationKeys) it.removePrefix("@").substringAfterLast('.') else it }
        }.filterValues { it.isNotEmpty() }.filterNot { (key, values) ->
            (key == "text" && values == listOf("code")) || (key == "target" && probe.targets.isEmpty() && values == listOf("file"))
        }
        val unknown = params.keys - (setOf("scope", "path") + probe.required + probe.optional)
        require(unknown.isEmpty()) { "$kind 에 쓸 수 없는 값: ${unknown.joinToString()}" }
        val missing = (probe.required + "scope") - params.keys
        require(missing.isEmpty()) { "$kind 에 필요한 값이 없습니다: ${missing.joinToString()}" }
        require(params.getValue("scope").singleOrNull() in scopes) { "scope 는 main, test, all 중 하나입니다" }
        params["target"]?.let { require(it.singleOrNull() in probe.targets) { "$kind 의 target 은 ${probe.targets.joinToString()} 중 하나입니다" } }
        if (probe.targets.isNotEmpty() && ("when" in params || "name" in params)) require("target" in params) { "when·name 을 쓰려면 target 이 필요합니다" }
        params["text"]?.let { require(it.singleOrNull() in texts) { "text 는 code, string, raw 중 하나입니다" } }
        params["measure"]?.let { require(it.singleOrNull() in measures) { "measure 는 lines 또는 nesting 입니다" } }
        params["max"]?.let { require(it.singleOrNull()?.toIntOrNull()?.let { n -> n >= 0 } == true) { "max 는 0 이상 정수 하나입니다" } }
        if (params["measure"]?.single() == "nesting") require(params["target"]?.single() != "file") { "nesting 은 메서드에만 씁니다" }
        params["inherit"]?.let {
            require(it.singleOrNull() == "class") { "inherit 는 class 하나만 씁니다" }
            require(params["target"]?.single() in setOf("method", "test-method")) { "inherit 는 method·test-method 대상에만 씁니다" }
        }
        regexKeys.forEach { key -> params[key]?.forEach { Regex(it) } }
        globKeys.forEach { key -> params[key]?.forEach { glob(it) } }
        return params.toSortedMap()
    }

    fun glob(pattern: String): PathMatcher {
        val matchers = listOfNotNull(pattern, pattern.removePrefix("**/").takeIf { it != pattern })
            .map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        return PathMatcher { path -> matchers.any { it.matches(path) } }
    }

    fun baseName(path: String) = path.substringAfterLast('/').substringBeforeLast('.')

    fun files(f: Facts, params: Map<String, List<String>>): List<ConvFile> {
        val scoped = when (params["scope"]?.firstOrNull()) { "main" -> f.main; "test" -> f.tests; else -> f.files }
        val globs = params["path"].orEmpty().map(::glob)
        return if (globs.isEmpty()) scoped else scoped.filter { file -> globs.any { it.matches(Path.of(file.path)) } }
    }

    fun text(file: ConvFile, params: Map<String, List<String>>): List<String> = when (params["text"]?.firstOrNull()) {
        "raw" -> if (aboutComments(params)) file.lines else file.uncommented
        "string" -> file.uncommented
        else -> file.view.code
    }

    private fun aboutComments(params: Map<String, List<String>>) =
        params["pattern"].orEmpty().any { p -> commentMarkers.any { it in p } }

    fun targets(f: Facts, params: Map<String, List<String>>): List<DocTarget> {
        val kind = params["target"]?.firstOrNull() ?: "file"
        val names = params["name"].orEmpty().map(::Regex)
        val marks = params["when"].orEmpty().toSet()
        return files(f, params).flatMap { targetsOf(it, kind) }
            .filter { t -> (names.isEmpty() || names.any { r -> t.forms.any(r::matches) }) && (marks.isEmpty() || t.annotations.any { it in marks }) }
    }

    private fun targetsOf(file: ConvFile, kind: String): List<DocTarget> = when (kind) {
        "file" -> listOf(DocTarget(file, baseName(file.path), 1, emptyList(), file.lines.indices, null))
        "class" -> classes(file)
        "test-class" -> if (file.test) classes(file) else emptyList()
        "method" -> methods(file)
        "test-method" -> if (file.test) methods(file).filter { m -> m.annotations.any { it in testAnnotations } } else emptyList()
        else -> emptyList()
    }

    private fun classes(file: ConvFile) = file.types.filter { it.topLevel }.map { DocTarget(file, it.name, it.line, it.annotations, null, null) }

    fun owner(t: DocTarget): DocTarget? =
        t.file.types.filter { it.topLevel && it.line <= t.line }.maxByOrNull { it.line }?.let { DocTarget(t.file, it.name, it.line, it.annotations, null, null) }

    private fun methods(file: ConvFile): List<DocTarget> {
        val decls = file.annotated.filter { it.kind == "func" }
        val spans = CodeText.functions(file).map { s ->
            val decl = decls.firstOrNull { it.name == s.name && abs(it.line - (s.start + 1)) <= 1 }
            DocTarget(file, s.name, s.start + 1, decl?.annotations.orEmpty(), s.start..s.end, s.nesting)
        }
        val bodiless = decls.filter { d -> spans.none { it.name == d.name && abs(it.line - d.line) <= 1 } }
            .map { DocTarget(file, it.name, it.line, it.annotations, null, null) }
        return spans + bodiless
    }

    fun annotations(names: List<String>) = names.joinToString(" 또는 ") { "@$it" }

    fun annotationArguments(t: DocTarget, names: List<String>): List<String> {
        val end = t.line.coerceAtMost(t.file.lines.size)
        var start = end - 1
        while (start > 0 && end - start < MAX_ANNOTATION_LINES && annotationLine.containsMatchIn(t.file.lines[start - 1])) start--
        val text = t.file.lines.subList(start.coerceAtLeast(0), end).joinToString("\n")
        return names.flatMap { name ->
            Regex("""@(?:[\w.]+\.)?${Regex.escape(name)}\s*\(""").findAll(text).map { m ->
                var depth = 1
                var i = m.range.last + 1
                while (i < text.length && depth > 0) { if (text[i] == '(') depth++ else if (text[i] == ')') depth--; i++ }
                text.substring(m.range.last + 1, (i - 1).coerceAtLeast(m.range.last + 1))
            }.toList()
        }
    }

    private const val MAX_ANNOTATION_LINES = 8
    private val annotationLine = Regex("""^\s*(@|["')]|.*[,(]\s*$)""")
}

internal object ForbiddenImport : DocProbe("doc-forbidden-import", emptySet(), setOf("import"), emptySet()) {
    private val importLine = Regex("""^\s*import\s+(?:static\s+)?([\w.*`]+)""")

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val patterns = params.getValue("import").map(::Regex)
        return DocProbes.files(f, params).flatMap { file ->
            file.view.code.withIndex().mapNotNull { (i, line) ->
                importLine.find(line)?.groupValues?.get(1)?.replace("`", "")?.let { name ->
                    Sample(file.path, i + 1, patterns.none { it.containsMatchIn(name) }, "금지된 import $name")
                }
            }
        }
    }
}

internal object RequiredAnnotation : DocProbe(
    "doc-required-annotation", setOf("class", "test-class", "method", "test-method"), setOf("annotation", "target"), setOf("when", "name", "value", "inherit"),
) {
    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val required = params.getValue("annotation")
        val values = params["value"].orEmpty().map(::Regex)
        val inherit = params["inherit"]?.firstOrNull() == "class"
        return DocProbes.targets(f, params).map { target ->
            val t = if (inherit && target.annotations.none { it in required }) DocProbes.owner(target)?.takeIf { o -> o.annotations.any { it in required } } ?: target else target
            if (t.annotations.none { it in required }) return@map Sample(target.file.path, target.line, false, "${target.name} 에 ${DocProbes.annotations(required)} 가 없습니다${if (inherit) "(클래스에도 없음)" else ""}")
            val arguments = if (values.isEmpty()) emptyList() else DocProbes.annotationArguments(t, required)
            val ok = values.isEmpty() || (arguments.isNotEmpty() && arguments.all { a -> values.any { it.containsMatchIn(a) } })
            Sample(target.file.path, target.line, ok, "${target.name} 의 ${DocProbes.annotations(required)} 값이 정해진 형식과 다릅니다")
        }
    }
}

internal object ForbiddenAnnotation : DocProbe(
    "doc-forbidden-annotation", setOf("class", "test-class", "method", "test-method"), setOf("annotation"), setOf("target", "when", "name"),
) {
    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val forbidden = params.getValue("annotation").toSet()
        val decls = if ("target" in params) DocProbes.targets(f, params).map { Triple(it.file, it.line, it.name to it.annotations) }
        else DocProbes.files(f, params).flatMap { file -> file.annotated.map { Triple(file, it.line, it.name to it.annotations) } }
        return decls.map { (file, line, decl) ->
            val hits = decl.second.filter { it in forbidden }
            Sample(file.path, line, hits.isEmpty(), "${decl.first} 에 금지된 ${DocProbes.annotations(hits)} 가 있습니다")
        }
    }
}

internal object NamePattern : DocProbe(
    "doc-name-pattern", setOf("file", "class", "test-class", "method", "test-method"), setOf("pattern", "target"), setOf("when", "name"),
) {
    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val patterns = params.getValue("pattern").map(::Regex)
        return DocProbes.targets(f, params).map { t -> Sample(t.file.path, t.line, patterns.any { p -> t.forms.any(p::matches) }, "이름 `${t.name}` 이 정해진 형식과 다릅니다") }
    }
}

internal object ForbiddenCode : DocProbe("doc-forbidden-code", emptySet(), setOf("pattern"), setOf("text")) {
    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val patterns = params.getValue("pattern").map(::Regex)
        return DocProbes.files(f, params).flatMap { file ->
            DocProbes.text(file, params).withIndex().filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }
                .map { (i, line) -> Sample(file.path, i + 1, false, "금지된 코드: ${line.trim().take(80)}") }
                .ifEmpty { listOf(Sample(file.path, 1, true, "")) }
        }
    }
}

internal object RequiredCode : DocProbe(
    "doc-required-code", setOf("file", "method", "test-method"), setOf("pattern", "target"), setOf("when", "name", "text"),
) {
    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val patterns = params.getValue("pattern").map(::Regex)
        return DocProbes.targets(f, params).mapNotNull { t ->
            val body = t.body ?: return@mapNotNull null
            val text = DocProbes.text(t.file, params).slice(body).joinToString("\n")
            var from = 0
            val ok = patterns.all { p -> p.find(text, from)?.also { from = it.range.last + 1 } != null }
            Sample(t.file.path, t.line, ok, "${t.name} 에 필요한 내용이 없거나 순서가 다릅니다")
        }
    }
}

internal object FileLocation : DocProbe("doc-file-location", emptySet(), setOf("name", "location"), emptySet()) {
    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val names = params.getValue("name").map(::Regex)
        val places = params.getValue("location").map(DocProbes::glob)
        return DocProbes.files(f, params).filter { file -> names.any { it.matches(DocProbes.baseName(file.path)) } }
            .map { file -> Sample(file.path, 1, places.any { it.matches(Path.of(file.path)) }, "${file.path} 는 ${params.getValue("location").joinToString(" 또는 ")} 에 있어야 합니다") }
    }
}

internal object MaxSize : DocProbe("doc-max-size", setOf("file", "method", "test-method"), setOf("measure", "max", "target"), setOf("when", "name")) {
    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val max = params.getValue("max").first().toInt()
        val nesting = params.getValue("measure").first() == "nesting"
        return DocProbes.targets(f, params).mapNotNull { t ->
            val value = (if (nesting) t.nesting else t.body?.let { it.last - it.first + 1 }) ?: return@mapNotNull null
            Sample(t.file.path, t.line, value <= max, "${t.name} 의 ${if (nesting) "중첩" else "줄 수"} $value 이(가) 상한 $max 을(를) 넘습니다")
        }
    }
}

internal object MaxOccurrences : DocProbe(
    "doc-max-occurrences", setOf("file", "method", "test-method"), setOf("pattern", "max", "target"), setOf("when", "name", "text"),
) {
    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val patterns = params.getValue("pattern").map(::Regex)
        val max = params.getValue("max").first().toInt()
        return DocProbes.targets(f, params).mapNotNull { t ->
            val body = t.body ?: return@mapNotNull null
            val count = DocProbes.text(t.file, params).slice(body).sumOf { line -> patterns.sumOf { it.findAll(line).count() } }
            Sample(t.file.path, t.line, count <= max, "${t.name} 에 ${count}번 있습니다(상한 $max)")
        }
    }
}
