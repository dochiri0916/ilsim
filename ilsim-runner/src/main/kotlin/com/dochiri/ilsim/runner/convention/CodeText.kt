package com.dochiri.ilsim.runner.convention

internal class CodeView(val code: List<String>, val commentLines: List<Int>)

internal class FunctionSpan(val name: String, val start: Int, val end: Int, val nesting: Int) {
    val length: Int get() = end - start + 1
}

internal object CodeText {

    fun scrub(lines: List<String>): CodeView {
        val code = ArrayList<String>(lines.size)
        val comments = mutableListOf<Int>()
        var blockDepth = 0
        var raw = false
        var inString = false
        for ((index, line) in lines.withIndex()) {
            val out = StringBuilder()
            var commented = blockDepth > 0
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    blockDepth > 0 -> when {
                        line.startsWith("*/", i) -> { blockDepth--; i += 2 }
                        line.startsWith("/*", i) -> { blockDepth++; i += 2 }
                        else -> i++
                    }
                    inString -> when {
                        raw && line.startsWith("\"\"\"", i) -> { inString = false; raw = false; out.append("\"\"\""); i += 3 }
                        !raw && c == '\\' -> i += 2
                        !raw && c == '"' -> { inString = false; out.append('"'); i++ }
                        else -> i++
                    }
                    line.startsWith("//", i) -> { commented = true; i = line.length }
                    line.startsWith("/*", i) -> { commented = true; blockDepth = 1; i += 2 }
                    line.startsWith("\"\"\"", i) -> { inString = true; raw = true; out.append("\"\"\""); i += 3 }
                    c == '"' -> { inString = true; raw = false; out.append('"'); i++ }
                    c == '\'' -> {
                        var j = i + 1
                        while (j < line.length && line[j] != '\'') j += if (line[j] == '\\') 2 else 1
                        out.append("''")
                        i = j + 1
                    }
                    c == '`' -> {
                        val j = line.indexOf('`', i + 1).let { if (it < 0) line.length else it }
                        out.append(line, i, minOf(j + 1, line.length))
                        i = j + 1
                    }
                    else -> { out.append(c); i++ }
                }
            }
            if (inString && !raw) inString = false
            if (commented) comments += index
            code += out.toString()
        }
        return CodeView(code, comments)
    }

    private val ktFun = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(`[^`]+`|\w+)\s*\(""")
    private val javaMethod = Regex("""^\s*(?:@\w+\s+)*(?:(?:public|protected|private|static|final|synchronized|abstract|default|native)\s+)*(?:<[^>]+>\s+)?([\w.<>\[\], ?]+)\s+(\w+)\s*\(""")
    private val keywords = setOf("if", "for", "while", "switch", "catch", "return", "new", "else", "try", "do", "synchronized", "throw", "case")

    fun functionNames(file: ConvFile): List<String> =
        if (file.lang == "kotlin") file.view.code.mapNotNull { ktFun.find(it)?.groupValues?.get(1)?.trim('`') }
        else functions(file).map { it.name }

    fun functions(file: ConvFile): List<FunctionSpan> {
        val code = file.view.code
        val out = mutableListOf<FunctionSpan>()
        for ((i, line) in code.withIndex()) {
            val name = if (file.lang == "kotlin") ktFun.find(line)?.groupValues?.get(1)?.trim('`')
            else javaMethod.find(line)?.takeIf { m -> m.groupValues[1].trim().substringAfterLast(' ') !in keywords && m.groupValues[2] !in keywords }?.groupValues?.get(2)
            if (name == null) continue
            val body = bodyStart(code, i) ?: continue
            val (end, nesting) = bodyEnd(code, body.first, body.second) ?: continue
            out += FunctionSpan(name, i, end, nesting)
        }
        return out
    }

    private fun bodyStart(code: List<String>, from: Int): Pair<Int, Int>? {
        var paren = 0
        var line = from
        while (line < code.size && line < from + 12) {
            val text = code[line]
            for ((col, c) in text.withIndex()) {
                when (c) {
                    '(' -> paren++
                    ')' -> paren--
                    '{' -> if (paren == 0) return line to col
                    ';' -> if (paren == 0) return null
                    '=' -> if (paren == 0 && !isOperator(text, col)) return null
                }
            }
            val next = code.getOrNull(line + 1)?.trim().orEmpty()
            if (paren <= 0 && !(next.startsWith("{") || next.startsWith(":") || next.startsWith("=") || next.startsWith("where") || next.startsWith("throws"))) return null
            line++
        }
        return null
    }

    private fun isOperator(text: String, col: Int): Boolean {
        val before = text.getOrNull(col - 1)
        val after = text.getOrNull(col + 1)
        return after == '=' || after == '>' || before == '=' || before == '!' || before == '<' || before == '>'
    }

    private fun bodyEnd(code: List<String>, startLine: Int, startCol: Int): Pair<Int, Int>? {
        var depth = 0
        var max = 0
        for (line in startLine until code.size) {
            val text = code[line]
            val from = if (line == startLine) startCol else 0
            for (col in from until text.length) {
                when (text[col]) {
                    '{' -> { depth++; max = maxOf(max, depth) }
                    '}' -> { depth--; if (depth == 0) return line to (max - 1) }
                }
            }
        }
        return null
    }
}
