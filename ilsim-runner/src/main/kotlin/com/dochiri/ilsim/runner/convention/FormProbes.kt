package com.dochiri.ilsim.runner.convention

private val proxyAnnotations = setOf(
    "Entity", "MappedSuperclass", "Embeddable", "Configuration", "Service", "Component", "Repository", "RestController", "Controller",
    "ControllerAdvice", "RestControllerAdvice", "Transactional", "SpringBootApplication", "ConfigurationProperties",
)

private val javaClassDecl = Regex("""^((?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\s+)*)class\s+(\w+)""")
private val javaInterfaceDecl = Regex("""^(?:(?:public|protected|private|static|abstract|sealed|non-sealed)\s+)*interface\s+(\w+)""")

private fun ConvFile.javaOnly() = lang == "java"

private fun ConvFile.declaration(d: Decl): String = view.code.getOrNull(d.line - 1)?.trim().orEmpty()

private fun List<String>.depthAt(from: Int): List<Int> {
    val out = ArrayList<Int>(size - from)
    var depth = 0
    for (i in from until size) {
        out += depth
        depth += this[i].count { it == '{' } - this[i].count { it == '}' }
    }
    return out
}

internal object ClassFinal : Probe("class-final") {
    override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "프록시가 필요 없는 클래스는 final 로 선언한다"))

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.javaOnly() }.flatMap { c ->
            c.types.filter { t -> t.topLevel && t.annotations.none { it in proxyAnnotations } }.mapNotNull { t ->
                val m = javaClassDecl.find(c.declaration(t)) ?: return@mapNotNull null
                val mods = m.groupValues[1].split(Regex("""\s+""")).toSet()
                if ("abstract" in mods || "sealed" in mods || "non-sealed" in mods) return@mapNotNull null
                Sample(c.path, t.line, "final" in mods, "클래스 `${t.name}` 은 final 로 선언해야 합니다")
            }
        }
}

internal object ParameterFinal : Probe("parameter-final") {
    override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "메서드·생성자의 파라미터는 final 로 선언한다"))

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.javaOnly() }.flatMap { c ->
            CodeText.functions(c).filter { !c.view.code[it.start].contains(Regex("""\brecord\s""")) }.mapNotNull { fn ->
                val list = parameterText(c.view.code, fn.start) ?: return@mapNotNull null
                val parts = splitTopLevel(list).filter { it.isNotBlank() }
                if (parts.isEmpty()) return@mapNotNull null
                val ok = parts.all { p -> p.replace(Regex("""@\w+(?:\([^)]*\))?\s*"""), "").trimStart().startsWith("final ") }
                Sample(c.path, fn.start + 1, ok, "`${fn.name}` 의 파라미터는 final 로 선언해야 합니다")
            }
        }

    private fun parameterText(code: List<String>, start: Int): String? {
        val text = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until minOf(code.size, start + 12)) {
            for (ch in code[i]) {
                if (ch == '(') { depth++; opened = true; if (depth == 1) continue }
                if (ch == ')') { depth--; if (opened && depth == 0) return text.toString() }
                if (opened) text.append(ch)
            }
            if (opened) text.append(' ')
        }
        return null
    }

    private fun splitTopLevel(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (ch in text) {
            when {
                ch == '<' || ch == '(' -> { depth++; cur.append(ch) }
                ch == '>' || ch == ')' -> { depth--; cur.append(ch) }
                ch == ',' && depth == 0 -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out += cur.toString()
        return out
    }
}

internal object FunctionalInterfaceDeclared : Probe("functional-interface", minSamples = 3) {
    override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "추상 메서드가 하나뿐인 인터페이스에는 @FunctionalInterface 를 붙인다"))

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.javaOnly() }.flatMap { c ->
            c.types.mapNotNull { t ->
                val m = javaInterfaceDecl.find(c.declaration(t)) ?: return@mapNotNull null
                val depths = c.view.code.depthAt(t.line - 1)
                var abstract = 0
                for ((k, d) in depths.withIndex()) {
                    val line = c.view.code[t.line - 1 + k].trim()
                    if (d == 1 && line.contains("(") && line.endsWith(";") && !line.contains("=") &&
                        listOf("default ", "static ", "private ").none { line.startsWith(it) }) abstract++
                }
                if (abstract != 1) return@mapNotNull null
                Sample(c.path, t.line, "FunctionalInterface" in t.annotations, "인터페이스 `${m.groupValues[1]}` 는 추상 메서드가 하나뿐이라 @FunctionalInterface 가 필요합니다")
            }
        }
}

internal object NoValueAnnotation : Probe("no-value-annotation") {
    private const val SPRING_VALUE = "org.springframework.beans.factory.annotation.Value"

    override fun candidates(f: Facts): List<Candidate> =
        if (f.main.any { c -> c.annotated.any { "ConfigurationProperties" in it.annotations } })
            listOf(Candidate("main", emptyMap(), "설정값은 @Value 대신 @ConfigurationProperties 로 바인딩한다")) else emptyList()

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.types.isNotEmpty() }.map { c ->
            val hit = c.lines.indexOfFirst { it.trim() == "import $SPRING_VALUE;" || it.trim() == "import $SPRING_VALUE" }
            if (hit < 0) Sample(c.path, 1, true, "") else Sample(c.path, hit + 1, false, "@Value 대신 @ConfigurationProperties 를 사용해야 합니다")
        }
}

internal object ValueObjectConstructor : Probe("vo-private-constructor") {
    override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "vo 패키지의 클래스는 생성자를 private 으로 두고 팩터리로 만든다"))

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.pkg.substringAfterLast('.') == "vo" }.flatMap { c ->
            c.types.filter { it.topLevel }.mapNotNull { t ->
                val decl = c.declaration(t)
                val isClass = if (c.lang == "java") javaClassDecl.containsMatchIn(decl) else Regex("""^(?:[a-z]+\s+)*class\s""").containsMatchIn(decl) && !decl.contains("enum ")
                if (!isClass) return@mapNotNull null
                val private = if (c.lang == "java") c.view.code.any { Regex("""\bprivate\s+${t.name}\s*\(""").containsMatchIn(it) }
                else c.view.code.any { it.contains("private constructor") }
                Sample(c.path, t.line, private, "값 객체 `${t.name}` 의 생성자는 private 으로 두고 팩터리로 만들어야 합니다")
            }
        }
}

internal object DomainFinalFields : Probe("domain-final-fields") {
    private val field = Regex("""^(?:(?:private|protected|public)\s+)?((?:(?:static|final|transient|volatile)\s+)*)[\w.<>\[\], ?]+\s+\w+\s*(?:=[^;]*)?;$""")

    override fun candidates(f: Facts) = listOf(Candidate("main", emptyMap(), "도메인 객체의 인스턴스 필드는 final 로 선언한다"))

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.javaOnly() && it.pkg.split('.').contains("domain") }.mapNotNull { c ->
            val top = c.types.firstOrNull { it.topLevel } ?: return@mapNotNull null
            if (top.annotations.any { it in proxyAnnotations }) return@mapNotNull null
            if (!javaClassDecl.containsMatchIn(c.declaration(top))) return@mapNotNull null
            val code = c.view.code
            val depths = code.depthAt(top.line - 1)
            val fields = depths.withIndex().mapNotNull { (k, d) ->
                val line = code[top.line - 1 + k].trim()
                if (d != 1 || line.contains("(")) return@mapNotNull null
                val m = field.find(line) ?: return@mapNotNull null
                val mods = m.groupValues[1]
                if ("static" in mods) null else (top.line + k) to ("final" in mods)
            }
            if (fields.isEmpty()) return@mapNotNull null
            val bad = fields.firstOrNull { !it.second }
            Sample(c.path, bad?.first ?: top.line, bad == null, "도메인 객체 `${top.name}` 의 필드는 final 로 선언해야 합니다")
        }
}
