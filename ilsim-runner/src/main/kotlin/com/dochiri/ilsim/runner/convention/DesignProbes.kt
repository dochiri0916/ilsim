package com.dochiri.ilsim.runner.convention

private fun ConvFile.isDomain() = pkg.split('.').contains("domain")

private fun limit(params: Map<String, List<String>>, default: Int) = params["max"]?.firstOrNull()?.toIntOrNull() ?: default

internal object NoComments : Probe("no-comments") {
    override fun candidates(f: Facts): List<Candidate> = emptyList()

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.map { c ->
            val first = c.view.commentLines.firstOrNull()
            if (first == null) Sample(c.path, 1, true, "")
            else Sample(c.path, first + 1, false, "주석 ${c.view.commentLines.size}줄: 이름과 구조로 의도를 드러내고 주석은 요청이 있을 때만 씁니다")
        }
}

internal object DomainEncapsulation : Probe("domain-encapsulation") {
    private val ktVar = Regex("""^\s*(?:(?:public|internal|protected|override|open|lateinit|final)\s+)*var\s+\w+""")
    private val ktCtorVar = Regex("""[(,]\s*(?:(?:public|internal|protected|override|open)\s+)*var\s+\w+""")
    private val javaSetter = Regex("""\bpublic\s+(?:\w+\s+)*void\s+set[A-Z]\w*\s*\(""")
    private val lombok = Regex("""@(Setter|Data)\b""")

    override fun candidates(f: Facts): List<Candidate> = emptyList()

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.isDomain() && it.types.isNotEmpty() }.map { c ->
            val hit = if (c.lang == "kotlin") kotlinHit(c) else c.view.code.indexOfFirst { javaSetter.containsMatchIn(it) || lombok.containsMatchIn(it) }
            if (hit < 0) Sample(c.path, 1, true, "")
            else Sample(c.path, hit + 1, false, "도메인 상태를 밖에서 바꿀 수 있습니다(공개 var·setter). 의도를 드러내는 메서드로 바꿉니다")
        }

    private fun kotlinHit(c: ConvFile): Int {
        val code = c.view.code
        var depth = 0
        for ((i, line) in code.withIndex()) {
            val member = depth <= 1 && (ktVar.containsMatchIn(line) || ktCtorVar.containsMatchIn(line))
            val privateSet = line.contains("private set") || code.getOrNull(i + 1)?.trim()?.startsWith("private set") == true
            if (member && !privateSet && !line.trimStart().startsWith("private ")) return i
            depth += line.count { it == '{' } - line.count { it == '}' }
        }
        return -1
    }
}

internal object IdentifierValueObject : Probe("identifier-value-object") {
    private val primitives = setOf("String", "Long", "Int", "UUID", "java.util.UUID", "long", "int", "Integer")
    private val ktId = Regex("""\b(?:val|var)\s+(id|\w+Id)\s*:\s*([\w.]+)""")
    private val javaId = Regex("""\b(?:private|protected|public)\s+(?:final\s+)?([\w.]+)\s+(id|\w+Id)\s*[;=]""")

    override fun candidates(f: Facts): List<Candidate> = emptyList()

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.isDomain() }.flatMap { c ->
            c.view.code.withIndex().flatMap { (i, line) ->
                val found = if (c.lang == "kotlin") ktId.findAll(line).map { it.groupValues[1] to it.groupValues[2] }
                else javaId.findAll(line).map { it.groupValues[2] to it.groupValues[1] }
                found.map { (name, type) ->
                    Sample(c.path, i + 1, type !in primitives, "식별자 `$name` 이 원시 타입 `$type` 입니다. 값 객체로 감쌉니다")
                }.toList()
            }
        }
}

internal object MaxFunctionLines : Probe("max-function-lines") {
    override fun candidates(f: Facts): List<Candidate> = emptyList()

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val max = limit(params, 30)
        return f.main.flatMap { c ->
            CodeText.functions(c).map { fn ->
                Sample(c.path, fn.start + 1, fn.length <= max, "함수 `${fn.name}` 이 ${fn.length}줄로 ${max}줄을 넘습니다")
            }
        }
    }
}

internal object MaxNesting : Probe("max-nesting") {
    override fun candidates(f: Facts): List<Candidate> = emptyList()

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val max = limit(params, 2)
        return f.main.flatMap { c ->
            CodeText.functions(c).map { fn ->
                Sample(c.path, fn.start + 1, fn.nesting <= max, "함수 `${fn.name}` 의 중첩이 ${fn.nesting}단계로 ${max}단계를 넘습니다")
            }
        }
    }
}

internal object MaxFileLines : Probe("max-file-lines") {
    override fun candidates(f: Facts): List<Candidate> = emptyList()

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> {
        val max = limit(params, 300)
        return f.main.map { c ->
            val size = c.view.code.count { line -> line.isNotBlank() && !line.trimStart().let { it.startsWith("import ") || it.startsWith("package ") } }
            Sample(c.path, 1, size <= max, "파일이 ${size}줄로 ${max}줄을 넘습니다. 책임을 나눕니다")
        }
    }
}

internal object FirstClassCollection : Probe("first-class-collection") {
    private val collection = Regex("""^(?:Mutable|Immutable)?(?:List|Set|Map|Collection|Iterable|Queue|Deque|ArrayList|LinkedList|HashSet|LinkedHashSet|TreeSet|HashMap|LinkedHashMap|TreeMap|EnumMap|EnumSet|Array)\b|\[\]$|^(?:mutable|linked|hash|sorted)?(?:List|Set|Map)Of\b""")
    private val annotationPrefix = Regex("""^(?:@\w+(?:\([^)]*\))?\s*)+""")
    private val modifiers = setOf("private", "protected", "public", "final", "transient", "volatile", "static")
    private val ktField = Regex("""\b(?:val|var)\s+(\w+)\s*(?::\s*([^=,)]+?))?\s*(?:=\s*([^,)]*))?(?=[,)=]|$)""")

    override fun candidates(f: Facts): List<Candidate> = emptyList()

    override fun samples(f: Facts, params: Map<String, List<String>>): List<Sample> =
        f.main.filter { it.isDomain() && it.types.isNotEmpty() }.map { c ->
            val fields = if (c.lang == "kotlin") kotlinFields(c) else javaFields(c)
            val bad = fields.firstOrNull { (_, type, _) -> isCollection(type) }
            if (bad == null || fields.size == 1) Sample(c.path, 1, true, "")
            else Sample(c.path, bad.third + 1, false, "컬렉션 필드 `${bad.first}` 가 있는 클래스는 다른 필드를 두지 않습니다. 일급 컬렉션으로 감쌉니다")
        }

    private fun isCollection(type: String) = collection.containsMatchIn(type.trim())

    private fun javaFields(c: ConvFile): List<Triple<String, String, Int>> {
        var depth = 0
        val out = mutableListOf<Triple<String, String, Int>>()
        for ((i, raw) in c.view.code.withIndex()) {
            if (depth == 1) parseJava(raw)?.let { (name, type) -> out += Triple(name, type, i) }
            depth += raw.count { it == '{' } - raw.count { it == '}' }
        }
        return out
    }

    private fun parseJava(raw: String): Pair<String, String>? {
        val line = raw.trim().replace(annotationPrefix, "")
        if (!line.endsWith(";") || line.startsWith("return ") || line.startsWith("import ") || line.startsWith("package ")) return null
        val head = line.removeSuffix(";").substringBefore("=").trim()
        if ('(' in head) return null
        val words = head.split(Regex("""\s+(?![^<]*>)""")).filter { it.isNotEmpty() }
        if (words.any { it == "static" }) return null
        val rest = words.filter { it !in modifiers }
        if (rest.size < 2) return null
        return rest.last() to rest.dropLast(1).joinToString(" ")
    }

    private fun kotlinFields(c: ConvFile): List<Triple<String, String, Int>> {
        var depth = 0
        var skipTo = -1
        val out = mutableListOf<Triple<String, String, Int>>()
        for ((i, line) in c.view.code.withIndex()) {
            val before = depth
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (skipTo >= 0) { if (depth <= skipTo) skipTo = -1; continue }
            if (Regex("""\bfun\s""").containsMatchIn(line)) {
                if (depth > before) skipTo = before
                continue
            }
            if (before > 1) continue
            ktField.findAll(line).forEach { m ->
                val type = m.groupValues[2].ifBlank { m.groupValues[3] }
                out += Triple(m.groupValues[1], type, i)
            }
        }
        return out
    }
}
