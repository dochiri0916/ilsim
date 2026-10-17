package com.dochiri.ilsim.runner.profile

/** 모듈 이름 조건식의 항 하나. 예: `!project.name.endsWith("-x")`, `name == "infra"`. */
internal class NameTerm(val negate: Boolean, val op: String, val arg: String) {
    fun matches(moduleName: String): Boolean {
        val hit = when (op) {
            "endsWith" -> moduleName.endsWith(arg)
            "startsWith" -> moduleName.startsWith(arg)
            "contains" -> moduleName.contains(arg)
            else -> moduleName == arg
        }
        return hit != negate
    }
}

/** 모듈 이름에 따라 적용되는 공통 설정 블록. 항들은 `||`(any) 또는 `&&`(all) 중 한 가지로만 결합된다. */
internal class NameCondition(val terms: List<NameTerm>, val all: Boolean, val body: String) {
    fun matches(moduleName: String): Boolean =
        if (all) terms.all { it.matches(moduleName) } else terms.any { it.matches(moduleName) }
}

internal object NameConditions {
    private val start = Regex("""\bif\s*\(([^{}]*?)\)\s*\{""")
    private val term = Regex(
        """^\s*(!)?\s*(?:project\.)?name\s*(?:\.(endsWith|startsWith|contains)\(\s*["']([^"']+)["']\s*\)|(==|!=)\s*["']([^"']+)["'])\s*$""",
    )

    /** 인식한 조건부 블록을 본문에서 떼어 내고, (나머지 텍스트, 블록 목록)을 돌려준다. 인식하지 못한 `if` 는 그대로 둔다. */
    fun split(text: String): Pair<String, List<NameCondition>> {
        val rest = StringBuilder()
        val blocks = mutableListOf<NameCondition>()
        var cursor = 0
        var searchFrom = 0
        while (true) {
            val m = start.find(text, searchFrom) ?: break
            val parsed = parseExpression(m.groupValues[1])
            if (parsed == null) { searchFrom = m.range.last + 1; continue }
            val bodyStart = m.range.last + 1
            var depth = 1
            var i = bodyStart
            while (i < text.length && depth > 0) {
                when (text[i]) { '{' -> depth++; '}' -> depth-- }
                i++
            }
            if (depth != 0) break
            rest.append(text, cursor, m.range.first)
            blocks += NameCondition(parsed.first, parsed.second, text.substring(bodyStart, i - 1))
            cursor = i
            searchFrom = i
        }
        rest.append(text, cursor, text.length)
        return rest.toString() to blocks
    }

    private fun parseExpression(expr: String): Pair<List<NameTerm>, Boolean>? {
        val hasOr = "||" in expr
        val hasAnd = "&&" in expr
        if (hasOr && hasAnd) return null
        val parts = expr.split(if (hasAnd) "&&" else "||")
        val terms = parts.map { part ->
            val m = term.find(part) ?: return null
            if (m.groups[2] != null) NameTerm(m.groups[1] != null, m.groupValues[2], m.groupValues[3])
            else NameTerm(m.groupValues[4] == "!=", "equals", m.groupValues[5])
        }
        return terms to hasAnd
    }
}
