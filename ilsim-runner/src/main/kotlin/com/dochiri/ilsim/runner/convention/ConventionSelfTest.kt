package com.dochiri.ilsim.runner.convention

internal object ConventionSelfTest {
    private const val MAX_FILES = 3
    private const val PROBE = "ilsimProbe"
    private val method = Regex("""\bfun\s|^\s*(public|protected|private|static|void)\b[^=;]*\(""")
    private val testMethod = Regex("""(\bfun\s+|\bvoid\s+)(`[^`]+`|\w+)(\s*\()""")

    data class Result(val caught: List<String>, val missed: List<String>, val untested: List<String>) {
        val total: Int get() = caught.size + missed.size + untested.size
    }

    fun run(facts: Facts, rules: List<ConventionRule>): Result {
        val caught = mutableListOf<String>()
        val missed = mutableListOf<String>()
        val untested = mutableListOf<String>()
        for (rule in rules.filter { !it.rejected }) {
            val probe = Probes.byKind(rule.kind)
            if (probe == null) { untested += rule.id; continue }
            val baseline = runCatching { probe.samples(facts, rule.params) }.getOrElse { untested += rule.id; continue }
            val failing = baseline.filterNot { it.ok }.map { it.file }.toSet()
            if (failing.isNotEmpty()) { caught += rule.id; continue }
            val sampled = baseline.filter { it.ok }.map { it.file }.distinct()
            val inScope = (if (rule.params.containsKey("scope") || rule.params.containsKey("path")) DocProbes.files(facts, rule.params) else facts.files).map { it.path }
            val tests = facts.files.filter { it.path in sampled }.map { it.test }.toSet()
            val outside = facts.files.filter { it.path !in sampled && it.path !in failing && (tests.isEmpty() || it.test in tests) }.distinctBy { it.pkg }.map { it.path }
            val values = rule.params.values.flatten().filter { it.length >= 2 && it.all { c -> c.isLetterOrDigit() || c == '.' } }
            val related = facts.files.filter { f -> values.any { v -> f.pkg.split('.').contains(v) } }.sortedByDescending { f -> values.any { v -> f.pkg.endsWith(".$v") } }.distinctBy { it.pkg }.map { it.path }
            val chosen = ((sampled + inScope).distinct().filterNot { it in failing }.take(MAX_FILES) + related.take(MAX_FILES) + outside.take(MAX_FILES)).distinct().mapNotNull { p -> facts.files.firstOrNull { it.path == p } }
            if (chosen.isEmpty()) { untested += rule.id; continue }
            val hit = chosen.any { file ->
                mutations(rule, file).any { lines ->
                    val mutated = FactScanner.parse(file.path, file.path.substringAfterLast('.'), file.test, lines)?.also { it.module = file.module } ?: return@any false
                    runCatching { probe.samples(Facts(listOf(mutated), facts.dependencies), rule.params).any { !it.ok } }.getOrDefault(false)
                }
            }
            if (hit) caught += rule.id else missed += rule.id
        }
        return Result(caught, missed, untested)
    }

    private fun mutations(rule: ConventionRule, file: ConvFile): List<List<String>> {
        val java = file.path.endsWith(".java")
        val end = if (java) ";" else ""
        val lines = file.lines
        val annotations = listOf("annotation", "when", "target", "companion").flatMap { rule.params[it].orEmpty() }.map { it.removePrefix("@").substringBefore('(') }.filter { it.isNotBlank() }
        val imports = rule.params["import"].orEmpty().mapNotNull(::importExample)
        val codes = rule.params["pattern"].orEmpty().mapNotNull(RegexExample::of)
        val text = rule.params["text"]?.firstOrNull()
        val typeLine = file.types.firstOrNull { it.topLevel }?.line?.minus(1)
        val methodLine = lines.indices.firstOrNull { i -> (typeLine == null || i > typeLine) && method.containsMatchIn(lines[i]) }
        val out = mutableListOf<List<String>>()
        annotations.forEach { a ->
            typeLine?.let { out += insert(lines, it, "@$a") }
            methodLine?.let { out += insert(lines, it, "    @$a") }
            val removal = Regex("""@$a(\([^)]*\))?\s*""")
            lines.map { it.replace(removal, "") }.takeIf { it != lines }?.let { out += it }
        }
        (imports + "com.ilsimprobe.*").forEach { out += afterPackage(lines, "import $it$end") }
        codes.forEach { code ->
            out += member(lines, when {
                text == "raw" -> listOf("    $code")
                text == "string" -> listOf(if (java) "    String $PROBE = \"$code\";" else "    val $PROBE = \"$code\"")
                java -> listOf("    void $PROBE() { $code; }")
                else -> listOf("    fun $PROBE() { $code }")
            })
        }
        out += member(lines, listOf(if (java) "    void $PROBE() { System.out.println(\"probe\"); }" else "    fun $PROBE() { println(\"probe\") }"))
        out += member(lines, listOf(if (java) "    @Autowired private Object $PROBE;" else "    @Autowired lateinit var $PROBE: Any"))
        out += member(lines, listOf("    // ilsim probe comment"))
        out += member(lines, listOf(if (java) "    public int $PROBE;" else "    var $PROBE: Int = 0"))
        out += member(lines, listOf(if (java) "    public void setIlsimProbe(int v) { }" else "    fun setIlsimProbe(v: Int) { }"))
        lines.map { it.replace(Regex("""\bprivate\s+constructor\b"""), "constructor").replace(Regex("""\bprivate(\s+\w+\s*\()"""), "public$1") }.takeIf { it != lines }?.let { out += it }
        rule.params["method"].orEmpty().forEach { m ->
            lines.map { it.replace(Regex("""\b${Regex.escape(m)}(\s*\()"""), "${m}Probe$1") }.takeIf { it != lines }?.let { out += it }
        }
        out += member(lines, listOf("\t" + if (java) "int $PROBE = 1;" else "val $PROBE = 1"))
        out += member(lines, listOf((if (java) "    int $PROBE" else "    val $PROBE") + "x".repeat(300) + " = 1$end"))
        out += member(lines, nested(java))
        out += member(lines, longFunction(java))
        out += lines + List(3000) { if (java) "    int $PROBE$it = $it;" else "val $PROBE$it = $it" }
        if (!java) out += member(lines, listOf("    val $PROBE: Any = ilsimNull!!"))
        file.types.firstOrNull { it.topLevel }?.let { t ->
            val decl = Regex("""\b(class|interface|object|record|enum)\s+${Regex.escape(t.name)}\b""")
            val at = lines.indices.firstOrNull { it >= t.line - 1 && decl.containsMatchIn(lines[it]) } ?: lines.indices.firstOrNull { decl.containsMatchIn(lines[it]) }
            at?.let { i -> out += lines.mapIndexed { j, l -> if (j == i) l.replaceFirst(decl, "$1 ${t.name}Probe") else l } }
        }
        out += lines.map { if (it.trimStart().startsWith("package ")) it.replace(file.pkg, "${file.pkg}.ilsimprobe") else it }
        out += lines + "class IlsimProbeType {}"
        lines.indexOfFirst { testMethod.containsMatchIn(it) }.takeIf { it >= 0 }?.let { i ->
            out += lines.mapIndexed { j, l -> if (j == i) l.replaceFirst(testMethod, "$1probeCase$3") else l }
            out += lines.mapIndexed { j, l -> if (j == i) l.replaceFirst(testMethod, "$1`프로브 케이스`$3") else l }
            out += lines.mapIndexed { j, l -> if (j == i) l.replaceFirst(testMethod, "$1probe_case$3") else l }
        }
        return out.distinct()
    }

    private fun insert(lines: List<String>, at: Int, line: String) = lines.take(at) + line + lines.drop(at)

    private fun afterPackage(lines: List<String>, line: String): List<String> {
        val pkg = lines.indexOfFirst { it.trimStart().startsWith("package ") }
        return insert(lines, pkg + 1, line)
    }

    private fun member(lines: List<String>, body: List<String>): List<String> {
        val close = lines.indexOfLast { it.trim() == "}" }
        return if (close < 0) lines + body else lines.take(close) + body + lines.drop(close)
    }

    private fun nested(java: Boolean): List<String> {
        val depth = 8
        val open = (0 until depth).map { "    ".repeat(it + 2) + "if (true) {" }
        val close = (0 until depth).reversed().map { "    ".repeat(it + 2) + "}" }
        return listOf(if (java) "    void $PROBE() {" else "    fun $PROBE() {") + open + ("    ".repeat(depth + 2) + "println(1)" + if (java) ";" else "") + close + "    }"
    }

    private fun longFunction(java: Boolean): List<String> =
        listOf(if (java) "    void ${PROBE}Long() {" else "    fun ${PROBE}Long() {") + List(200) { if (java) "        int v$it = $it;" else "        val v$it = $it" } + "    }"

    private fun importExample(pattern: String): String? {
        val raw = RegexExample.of(pattern.removePrefix("^")) ?: return null
        val name = (if (raw.startsWith(".")) "com.ilsimprobe$raw" else raw).let { if (it.endsWith(".")) "${it}IlsimProbe" else it }
        return name.takeIf { runCatching { Regex(pattern).containsMatchIn(it) }.getOrDefault(false) }
    }
}

internal object RegexExample {
    private val steps = listOf(
        Regex("""\(\?[a-z]+\)""") to "",
        Regex("""\(\?<?[!=][^)]*\)""") to "",
        Regex("""\\b""") to "",
        Regex("""\\s\*""") to "",
        Regex("""\\s\+""") to " ",
        Regex("""\\s""") to " ",
        Regex("""\\d\+?\*?""") to "1",
        Regex("""\\w\*""") to "",
        Regex("""\\w\+?""") to "x",
        Regex("""\.\*""") to "",
        Regex("""\.\+""") to "x",
    )

    fun of(pattern: String): String? {
        var s = Regex("""\\Q(.*?)\\E""").replace(pattern) { it.groupValues[1].replace(Regex("""([\\.^$|?*+()\[\]{}])"""), "\\\\$1") }
        steps.forEach { (r, to) -> s = s.replace(r, to) }
        s = Regex("""\(([^()|]*)(\|[^()]*)?\)\?""").replace(s, "")
        s = Regex("""\(([^()|]*)(\|[^()]*)?\)""").replace(s) { it.groupValues[1] }
        s = Regex("""(\\.|[^\\])\?""").replace(s, "")
        s = s.substringBefore('|')
        if (Regex("""(?<!\\)[\[\]{}^$]""").containsMatchIn(s)) return null
        s = Regex("""\\(.)""").replace(s) { it.groupValues[1] }
        if (s.isBlank()) return null
        return s.takeIf { runCatching { Regex(pattern).containsMatchIn(it) }.getOrDefault(false) }
    }
}
