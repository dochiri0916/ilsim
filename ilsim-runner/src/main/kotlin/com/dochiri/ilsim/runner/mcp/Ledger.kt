package com.dochiri.ilsim.runner.mcp

object Ledger {
    data class Row(val id: String, val text: String, val probability: Double?, val links: List<String>, val locations: List<String>)

    private val confirmed = setOf("PASSED", "RED", "NONE")
    private val meaning = mapOf(
        "UNKNOWN" to "판정 못 함",
        "NOT_CONFIGURED" to "설정 없음",
        "SKIPPED" to "생략",
        "UNCERTAIN" to "불확실",
        "LOW" to "기준 미달",
        "PROPOSED" to "승인 전 규칙에 걸림",
    )

    fun unconfirmed(validation: Map<String, String>): List<String> =
        validation.filterValues { it !in confirmed }.map { (k, v) -> "$k=$v${meaning[v]?.let { "($it)" }.orEmpty()}" }

    fun render(stage: String, validation: Map<String, String>, title: String?, rows: List<Row>, audit: String): String = buildString {
        appendLine("## 검증 원장 ($stage)")
        if (title != null && rows.isNotEmpty()) {
            appendLine()
            appendLine("| $title | 판정 확률 | 연결 | 테스트 위치 |")
            appendLine("|---|---|---|---|")
            rows.forEach { r ->
                appendLine("| ${r.id} ${cell(r.text)} | ${r.probability?.let { "%.2f".format(it) } ?: "-"} | ${r.links.joinToString(", ").ifEmpty { "-" }} | ${r.locations.joinToString(", ").ifEmpty { "-" }} |")
            }
        }
        appendLine()
        appendLine("- 검사: ${validation.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "형식·경로 검사" }}")
        appendLine("- 독립 검토: $audit")
        val open = unconfirmed(validation)
        append("- 확인 못 한 항목: ${open.joinToString(", ").ifEmpty { "없음" }}")
        val blank = rows.filter { it.locations.isEmpty() }.map { it.id }
        if (title != null && blank.isNotEmpty()) append("\n- 테스트 위치를 찾지 못한 항목: ${blank.joinToString(", ")}")
    }

    private fun cell(text: String): String = text.replace("|", "\\|").replace(Regex("""\s+"""), " ").take(MAX_TEXT).trim()

    private const val MAX_TEXT = 60
}
