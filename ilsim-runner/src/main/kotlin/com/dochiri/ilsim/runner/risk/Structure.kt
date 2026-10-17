package com.dochiri.ilsim.runner.risk

import com.dochiri.ilsim.runner.convention.ConvFile
import com.dochiri.ilsim.runner.convention.Decl

/** 클래스 멤버 하나. kind: func | field. body 는 주석·문자열을 지운 본문이며 위치 계산용 [bodyLine] 은 본문 첫 줄 번호다. */
internal class Member(
    val kind: String,
    val name: String,
    val line: Int,
    val annotations: List<String>,
    val modifiers: Set<String>,
    val type: String,
    val mutable: Boolean,
    val body: String,
    val bodyLine: Int,
) {
    val isPrivate get() = "private" in modifiers
    fun lineOf(offset: Int) = bodyLine + body.take(offset).count { it == '\n' }
}

internal class Cls(
    val name: String,
    val line: Int,
    val annotations: List<String>,
    val members: List<Member>,
    val ctorFields: List<Member>,
    val kind: String = "class",
    val modifiers: Set<String> = emptySet(),
) {
    /** 클래스 전체를 대신하는 검색용 텍스트: 이름·타입·본문. */
    val text: String get() = (ctorFields + members).joinToString("\n") { "${it.name} ${it.type} ${it.body}" }
    val funcs get() = members.filter { it.kind == "func" }
    val fields get() = ctorFields + members.filter { it.kind == "field" }
}

/** 주석과 문자열·문자 리터럴을 공백으로 바꾼다(줄바꿈과 길이는 유지). 중괄호 짝 맞추기와 패턴 검색을 안전하게 하기 위함이다. */
internal fun stripLiterals(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    fun blank(c: Char) = if (c == '\n') '\n' else ' '
    while (i < text.length) {
        val c = text[i]
        val n = text.getOrNull(i + 1)
        when {
            c == '/' && n == '/' -> { while (i < text.length && text[i] != '\n') { out.append(' '); i++ } }
            c == '/' && n == '*' -> {
                val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                while (i < end) { out.append(blank(text[i])); i++ }
            }
            text.startsWith("\"\"\"", i) -> {
                val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                while (i < end) { out.append(blank(text[i])); i++ }
            }
            c == '"' || c == '\'' -> {
                out.append(c); i++
                while (i < text.length && text[i] != c && text[i] != '\n') {
                    if (text[i] == '\\' && i + 1 < text.length) { out.append(' '); i++ }
                    out.append(' '); i++
                }
                if (i < text.length && text[i] == c) { out.append(c); i++ }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

internal object StructureParser {
    private val ktFun = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(`[^`]+`|\w+)\s*\(""")
    private val javaMethod = Regex("""^(?:(?:public|private|protected|static|final|abstract|synchronized|default)\s+)*(?:<[^>]+>\s*)?[\w<>\[\],.?]+\s+(\w+)\s*\(""")
    private val ktField = Regex("""\b(val|var)\s+(\w+)\s*(?::\s*([\w.<>?, ]+?))?\s*(?:=\s*(.*))?$""")
    private val javaField = Regex("""^(?:(?:public|private|protected|static|final|volatile|transient)\s+)*([\w<>\[\],.? ]+?)\s+(\w+)\s*(?:=\s*(.*))?;?$""")
    private val ctorParam = Regex("""((?:@[\w.:]+(?:\([^)]*\))?\s*)*)(?:(private|protected|public|internal)\s+)?(?:(val|var)\s+)(\w+)\s*:\s*([\w.]+(?:<(?:[^<>]|<[^<>]*>)*>)?\??)""")
    private val modifierWords = setOf("public", "private", "protected", "internal", "static", "final", "abstract", "open", "override", "lateinit", "volatile", "synchronized", "transient", "suspend")
    private val notMember = Regex("""^(return|new|else|if|for|while|throw|import|package|class|interface|object|enum|record|case|try|catch|do)\b""")

    fun classes(file: ConvFile): List<Cls> {
        val text = stripLiterals(file.lines.joinToString("\n"))
        val lines = text.split('\n')
        val starts = IntArray(lines.size).also { var o = 0; lines.forEachIndexed { i, l -> it[i] = o; o += l.length + 1 } }
        val depth = IntArray(lines.size + 1).also {
            var d = 0
            lines.forEachIndexed { i, l -> it[i] = d; d += l.count { c -> c == '{' } - l.count { c -> c == '}' } }
            it[lines.size] = d
        }
        val memberAnnotations = file.annotated.filter { it.kind != "type" }.groupBy { it.line }
        return file.types.mapNotNull { t -> classOf(t, text, lines, starts, depth, memberAnnotations) }
    }

    private fun classOf(t: Decl, text: String, lines: List<String>, starts: IntArray, depth: IntArray, memberAnnotations: Map<Int, List<Decl>>): Cls? {
        val declIndex = t.line - 1
        if (declIndex !in lines.indices) return null
        val from = starts[declIndex]
        var parens = 0
        var bodyStart = -1
        var headerEnd = text.length
        var pos = from
        val limitLine = minOf(lines.size - 1, declIndex + 12)
        val limit = starts[limitLine] + lines[limitLine].length
        while (pos < limit) {
            when (text[pos]) {
                '(' -> parens++
                ')' -> parens--
                '{' -> if (parens <= 0) { bodyStart = pos; headerEnd = pos; break }
                ';' -> if (parens <= 0) { headerEnd = pos; break }
                '\n' -> if (parens <= 0 && pos > from && !continuesHeader(text, pos + 1)) { headerEnd = pos; break }
            }
            pos++
        }
        val header = text.substring(from, minOf(headerEnd, text.length))
        val ctor = ctorParam.findAll(header).map {
            val annotations = Regex("""@(?:\w+:)?([\w.]+)""").findAll(it.groupValues[1]).map { a -> a.groupValues[1].substringAfterLast('.') }.toList()
            Member("field", it.groupValues[4], t.line, annotations, setOfNotNull(it.groupValues[2].ifEmpty { null }), it.groupValues[5].trim(), it.groupValues[3] == "var", "", t.line)
        }.toList()
        val head = Regex("""((?:[a-z]+\s+)*)(class|interface|object|record|enum)\s+${Regex.escape(t.name)}\b""").find(header)
        val mods = head?.groupValues?.get(1)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val kind = head?.groupValues?.get(2) ?: "class"
        if (bodyStart < 0) return Cls(t.name, t.line, t.annotations, emptyList(), ctor, kind, mods)

        var d = 0
        var bodyEnd = text.length
        for (k in bodyStart until text.length) {
            if (text[k] == '{') d++ else if (text[k] == '}') { d--; if (d == 0) { bodyEnd = k; break } }
        }
        val bodyLineIndex = text.substring(0, bodyStart).count { it == '\n' }
        val beforeOnLine = text.substring(starts[bodyLineIndex], bodyStart)
        val classDepth = depth[bodyLineIndex] + beforeOnLine.count { it == '{' } - beforeOnLine.count { it == '}' }
        val firstLine = bodyLineIndex + 1
        val lastLine = text.substring(0, bodyEnd).count { it == '\n' }
        val members = mutableListOf<Member>()
        var li = firstLine
        while (li <= lastLine && li < lines.size) {
            val raw = lines[li]
            val l = raw.trim()
            if (depth[li] != classDepth + 1 || l.isEmpty() || notMember.containsMatchIn(l)) { li++; continue }
            val stripped = l.replace(Regex("""^(@\w+(?:\([^)]*\))?\s*)+"""), "").trim()
            val memberMods = Regex("""\b[a-z]+\b""").findAll(stripped).map { it.value }.takeWhile { it in modifierWords }.toSet()
            val ann = memberAnnotations[li + 1].orEmpty().flatMap { it.annotations }
            val kt = ktFun.find(stripped)
            val java = if (kt == null) javaMethod.find(stripped) else null
            if (kt != null || (java != null && "=" !in stripped.substringBefore("("))) {
                val name = (kt ?: java!!).groupValues[1].trim('`')
                val (body, endLine) = bodyOf(text, lines, starts, depth, li, classDepth + 1)
                members += Member("func", name, li + 1, ann, memberMods, "", false, body, li + 1)
                li = maxOf(li + 1, endLine + 1)
                continue
            }
            ktField.find(stripped)?.takeIf { "fun " !in stripped }?.let { m ->
                val type = m.groupValues[3].ifEmpty { m.groupValues[4].substringBefore("(").trim() }
                members += Member("field", m.groupValues[2], li + 1, ann, memberMods, type, m.groupValues[1] == "var", m.groupValues[4], li + 1)
            } ?: javaField.find(stripped)?.takeIf { "(" !in stripped.substringBefore("=") }?.let { m ->
                members += Member("field", m.groupValues[2], li + 1, ann, memberMods, m.groupValues[1].trim() + " " + m.groupValues[3].substringBefore("(").trim(),
                    "final" !in memberMods, m.groupValues[3], li + 1)
            }
            li++
        }
        return Cls(t.name, t.line, t.annotations, members, ctor, kind, mods)
    }

    /** 줄바꿈 뒤에도 클래스 선언이 이어지는지: 본문 `{`, 상속·구현, Kotlin 상위 타입(`:`)이면 이어진다. */
    private fun continuesHeader(text: String, from: Int): Boolean {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return false
        return text[i] == '{' || text[i] == ':' || text.startsWith("extends", i) || text.startsWith("implements", i) || text.startsWith("where", i) || text.startsWith("permits", i)
    }

    /** 함수 본문(중괄호 또는 `=` 식)을 돌려준다. 두 번째 값은 본문이 끝나는 줄 인덱스. */
    private fun bodyOf(text: String, lines: List<String>, starts: IntArray, depth: IntArray, li: Int, memberDepth: Int): Pair<String, Int> {
        var parens = 0
        var pos = starts[li]
        while (pos < text.length) {
            when (text[pos]) {
                '(' -> parens++
                ')' -> parens--
                ';' -> if (parens <= 0) return "" to li
                '{' -> if (parens <= 0) {
                    var d = 0
                    for (k in pos until text.length) {
                        if (text[k] == '{') d++ else if (text[k] == '}') { d--; if (d == 0) return text.substring(pos + 1, k) to text.substring(0, k).count { it == '\n' } }
                    }
                    return text.substring(pos + 1) to lines.size - 1
                }
                '=' -> if (parens <= 0 && text.getOrNull(pos + 1) != '=' && text.getOrNull(pos - 1) !in listOf('=', '!', '<', '>')) {
                    var end = text.substring(0, pos).count { it == '\n' }
                    while (end + 1 < lines.size && (depth[end + 1] > memberDepth || lines[end + 1].isBlank() || lines[end + 1].trimStart().startsWith("."))) end++
                    return text.substring(pos + 1, minOf(text.length, starts[end] + lines[end].length)) to end
                }
            }
            pos++
        }
        return "" to li
    }
}
