package com.dochiri.ilsim.runner.mcp

object Ledger {
    data class GuidelineRow(val source: String, val heading: String, val verdict: String, val probability: Double? = null) {
        val judged: Boolean get() = verdict != EXCLUDED
    }

    data class Row(val id: String, val text: String, val probability: Double?, val links: List<String>, val locations: List<String>)

    private val confirmed = setOf("PASSED", "RED", "NONE")
    private val meaning = mapOf(
        "UNKNOWN" to "판정 못 함",
        "NOT_CONFIGURED" to "설정 없음",
        "SKIPPED" to "생략",
        "UNCERTAIN" to "불확실",
        "LOW" to "기준 미달",
        "PROPOSED" to "승인 전 규칙에 걸림",
        "REVIEW" to "검토 규칙에 걸림",
    )

    fun unconfirmed(validation: Map<String, String>): List<String> =
        validation.filterValues { it !in confirmed }.map { (k, v) -> "$k=$v${meaning[v]?.let { "($it)" }.orEmpty()}" }

    fun render(stage: String, validation: Map<String, String>, title: String?, rows: List<Row>, audit: String, guidelines: List<GuidelineRow> = emptyList(), ruleHits: List<String> = emptyList()): String = buildString {
        appendLine("## 검증 원장 ($stage)")
        if (title != null && rows.isNotEmpty()) {
            appendLine()
            appendLine("| $title | 판정 확률 | 연결 | 테스트 위치 |")
            appendLine("|---|---|---|---|")
            rows.forEach { r ->
                appendLine("| ${r.id} ${cell(r.text)} | ${r.probability?.let { "%.2f".format(it) } ?: "-"} | ${r.links.joinToString(", ").ifEmpty { "-" }} | ${r.locations.joinToString(", ").ifEmpty { "-" }} |")
            }
        }
        if (guidelines.isNotEmpty()) {
            val judged = guidelines.filter { it.judged }
            val excluded = guidelines.filterNot { it.judged }
            appendLine()
            appendLine("### 프로젝트 문서 지침 (구간 ${guidelines.size}개: 판정 대상 ${judged.size}개, 이 작업과 관련 없어 제외 ${excluded.size}개)")
            if (judged.isNotEmpty()) {
                appendLine()
                appendLine("| 지침 | 결과 | 위반 확률 |")
                appendLine("|---|---|---|")
                judged.forEach { g -> appendLine("| ${g.source}${g.heading.takeIf { it.isNotBlank() }?.let { " ${cell(it)}" }.orEmpty()} | ${g.verdict} | ${g.probability?.let { "%.2f".format(it) } ?: "-"} |") }
            }
            if (excluded.isNotEmpty()) {
                appendLine()
                appendLine("관련 없어 제외한 구간(잘못 빠졌으면 사용자가 알려 주세요): " + excluded.take(MAX_EXCLUDED).joinToString(", ") { it.source } + if (excluded.size > MAX_EXCLUDED) " …외 ${excluded.size - MAX_EXCLUDED}개" else "")
            }
        }
        appendLine()
        appendLine("- 검사: ${validation.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "형식·경로 검사" }}")
        appendLine("- 독립 검토: $audit")
        val open = unconfirmed(validation)
        append("- 확인 못 한 항목: ${open.joinToString(", ").ifEmpty { "없음" }}")
        ruleHits.take(MAX_RULE_HITS).forEach { append("\n- 막지 않은 검토 규칙 위반(사용자 확인): ${it.replace(Regex("""\s+"""), " ").take(MAX_HIT)}") }
        if (ruleHits.size > MAX_RULE_HITS) append("\n- 막지 않은 검토 규칙 위반 …외 ${ruleHits.size - MAX_RULE_HITS}건")
        val blank = rows.filter { it.locations.isEmpty() }.map { it.id }
        if (title != null && blank.isNotEmpty()) append("\n- 테스트 위치를 찾지 못한 항목: ${blank.joinToString(", ")}")
    }

    fun brief(ledger: String): String = ledger.lines().filter { line ->
        val t = line.trim()
        t.startsWith("## ") || t.startsWith("- ") || (t.startsWith("| ") && !t.startsWith("| 지침 ") && " | $PASSED | " !in t && " | $EXCLUDED | " !in t)
    }.joinToString("\n")

    private fun cell(text: String): String = text.replace("|", "\\|").replace(Regex("""\s+"""), " ").take(MAX_TEXT).trim()

    private const val MAX_TEXT = 60
    private const val MAX_EXCLUDED = 40
    private const val MAX_RULE_HITS = 10
    private const val MAX_HIT = 240
    const val EXCLUDED = "제외"
    const val PASSED = "통과"
    const val UNCERTAIN = "불확실"
    const val VIOLATED = "위반"
    const val UNJUDGED = "판정 못 함"
}
