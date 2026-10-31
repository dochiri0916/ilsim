package com.dochiri.ilsim.runner.mcp

import com.dochiri.ilsim.runner.jev.RequirementCheck
import com.dochiri.ilsim.runner.jev.TestCodeCheck

object ApprovalView {
    private const val MAX_ITEM = 160
    private const val MAX_SCOPE = 15
    private const val MAX_DIFF_LINES = 80

    fun outline(stage: String, text: String): String = when (stage) {
        "REQUIREMENTS" -> listOfNotNull(
            text.lines().firstOrNull { it.startsWith("# ") }?.trim(),
            RequirementCheck.parse(text).takeIf { it.isNotEmpty() }?.joinToString("\n", "요구사항:\n") { "- ${it.id} ${it.text.take(MAX_ITEM)}" },
            section(text, "영향 범위").takeIf { it.isNotEmpty() }?.let { lines -> "영향 범위:\n" + lines.take(MAX_SCOPE).joinToString("\n") + if (lines.size > MAX_SCOPE) "\n…외 ${lines.size - MAX_SCOPE}줄" else "" },
        ).joinToString("\n\n").ifEmpty { text.take(MAX_ITEM * 20) }
        "TEST_CASES" -> TestCodeCheck.cases(text).takeIf { it.isNotEmpty() }?.joinToString("\n", "테스트 케이스:\n") { "- ${it.id} ${it.text.take(MAX_ITEM)}" } ?: text.take(MAX_ITEM * 20)
        else -> text.take(MAX_ITEM * 20)
    }

    fun diff(before: String, after: String, max: Int = MAX_DIFF_LINES): String {
        val a = before.trimEnd().takeIf { it.isNotEmpty() }?.lines().orEmpty()
        val b = after.trimEnd().takeIf { it.isNotEmpty() }?.lines().orEmpty()
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<String>()
        var i = 0
        var j = 0
        var gap = false
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { i++; j++; gap = true; continue }
                i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> { if (gap && out.isNotEmpty()) out += "…"; out += "- ${a[i]}"; i++ }
                else -> { if (gap && out.isNotEmpty()) out += "…"; out += "+ ${b[j]}"; j++ }
            }
            gap = false
        }
        if (out.isEmpty()) return "(달라진 줄 없음)"
        return out.take(max).joinToString("\n") + if (out.size > max) "\n…외 ${out.size - max}줄" else ""
    }

    private fun section(text: String, title: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("#") && title in it }
        if (start < 0) return emptyList()
        val level = lines[start].trimStart().takeWhile { it == '#' }.length
        return lines.drop(start + 1).takeWhile { line ->
            val t = line.trimStart()
            !(t.startsWith("#") && t.takeWhile { it == '#' }.length <= level)
        }.filter { it.isNotBlank() }
    }
}
