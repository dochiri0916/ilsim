package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.document.DocSection

internal object StaticDocRules {
    private val sentenceEnd = Regex("""(?<=다\.)\s+|(?<=다)\s*$|\n""")
    private val code = Regex("""`([^`]+)`""")
    private val forbids = Regex("""(쓰지|사용하지|붙이지|부르지|호출하지|두지|넣지)\s*않(는다|음)|금지|미사용""")
    private val separator = Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?$""")
    private val clauseEnd = Regex("""(?<=[가-힣)]\.)\s+""")
    private val obliges = Regex("""(않는다|한다|쓴다|붙인다|둔다|넣는다|금지|해야|없어야)\.?\s*$""")
    private val conditional = Regex("""만\s|때는|경우|대신|예외|외에는|아니면|라면|면\s""")
    private val annotation = Regex("""^@([A-Z]\w*)(?:\(.*\))?$""")
    private val call = Regex("""^([A-Za-z_][\w.]*\w)(\(\))?$""")
    private val test = Regex("""테스트""")

    data class Result(val proposals: List<DocRules.Proposal>, val uncovered: List<String>)

    private class Clause(val text: String, val context: String)

    fun sentences(text: String): List<String> =
        text.lines().filterNot { it.trimStart().startsWith("|") }.flatMap { line -> line.split(sentenceEnd) }.map { it.trim().trimStart('-', '*', '+', ' ').trim() }.filter { it.isNotEmpty() }

    fun ruleLike(sentence: String) = obliges.containsMatchIn(sentence)

    fun extract(section: DocSection): Result {
        val proposals = mutableListOf<DocRules.Proposal>()
        val uncovered = mutableListOf<String>()
        val clauses = sentences(section.text).filter(::ruleLike).map { Clause(it, "") } + tableClauses(section.text).filter { forbids.containsMatchIn(it.text) }
        for (clause in clauses) {
            val found = forbidden(section, clause.text, clause.context)
            if (found.isEmpty()) uncovered += clause.text else proposals += found
        }
        return Result(proposals, uncovered)
    }

    private fun tableClauses(text: String): List<Clause> {
        val lines = text.lines().map { it.trim() }
        return lines.indices.filter { i -> lines[i].startsWith("|") && !separator.matches(lines[i]) && lines.getOrNull(i + 1)?.let { separator.matches(it) } != true }.flatMap { i ->
            val cells = lines[i].removePrefix("|").removeSuffix("|").split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val label = cells.firstOrNull().orEmpty()
            cells.drop(1).flatMap { it.split(clauseEnd) }.map { it.trim() }.filter { it.isNotEmpty() }.map { Clause(it, label) }
        }
    }

    private fun forbidden(section: DocSection, sentence: String, context: String): List<DocRules.Proposal> {
        if (!forbids.containsMatchIn(sentence) || conditional.containsMatchIn(sentence)) return emptyList()
        val spans = code.findAll(sentence).map { it.groupValues[1].trim() }.toList()
        if (spans.isEmpty()) return emptyList()
        val scope = if (test.containsMatchIn("$sentence $context ${section.heading}")) "test" else "all"
        val annotations = spans.mapNotNull { annotation.matchEntire(it)?.groupValues?.get(1) }
        val calls = spans.filter { annotation.matchEntire(it) == null }.mapNotNull { span -> call.matchEntire(span)?.takeIf { '.' in it.groupValues[1] || it.groupValues[2].isNotEmpty() } }
        if (annotations.size + calls.size != spans.size) return emptyList()
        val out = mutableListOf<DocRules.Proposal?>()
        if (annotations.isNotEmpty()) out += proposal(section, "doc-forbidden-annotation", mapOf("scope" to listOf(scope), "annotation" to annotations), sentence)
        if (calls.isNotEmpty()) {
            val patterns = calls.map { m -> """(?<![\w.])""" + Regex.escape(m.groupValues[1]).let { if (m.groupValues[2].isNotEmpty()) "$it\\s*\\(" else "$it\\b" } }
            out += proposal(section, "doc-forbidden-code", mapOf("scope" to listOf(scope), "pattern" to patterns), sentence)
        }
        return out.filterNotNull()
    }

    private fun proposal(section: DocSection, kind: String, params: Map<String, List<String>>, sentence: String): DocRules.Proposal? =
        runCatching { DocRules.Proposal(section, kind, DocProbes.normalize(kind, params), sentence.removeSuffix("."), sentence) }.getOrNull()
}
