package com.dochiri.ilsim.runner.jev

import io.github.gudcks0305.jev.NoulQuestion

object RequirementCheck {
    const val MET = 0.8
    const val MISSING = 0.2
    const val MAX_DIFF_CHARS = 60_000

    data class Result(val probabilities: Map<String, Double>, val missing: List<Requirement>, val uncertain: List<Requirement>, val truncated: Boolean)

    private val item = Regex("""(REQ-\d+)\s*[:：.)\]|-]?\s*(.*)""")

    fun parse(markdown: String): List<Requirement> = markdown.lines().mapNotNull { line ->
        val m = item.find(line) ?: return@mapNotNull null
        val text = m.groupValues[2].replace(Regex("""[*`|]"""), " ").replace(Regex("""\s+"""), " ").trim()
        Requirement(m.groupValues[1], text.ifEmpty { line.trim() })
    }.distinctBy { it.id }

    fun questions(requirements: List<Requirement>): Map<String, NoulQuestion> = requirements.withIndex().flatMap { (i, r) -> listOf(
        "m$i" to NoulQuestion.of("m$i", "변경(diff)이 요구사항 ${r.id}「${r.text}」를 실행 코드나 설정에서 완전히 구현하는가? 테스트만 있거나 TODO·주석뿐이거나 조건·범위가 요구사항과 다르면 No.")
            .withCriteria("요구사항의 동작이 구현에서 확인됨", "구현이 없거나 일부만 있거나 조건이 다름"),
        "d$i" to NoulQuestion.of("d$i", "제공된 diff만으로 요구사항 ${r.id}의 충족 여부를 확정할 수 있는가? 변경 밖의 코드나 설정을 확인해야 하면 No."),
    ) }.toMap()

    fun judge(jev: Jev, requirements: List<Requirement>, diff: String): Result? {
        if (!jev.enabled || requirements.isEmpty()) return null
        val truncated = diff.length > MAX_DIFF_CHARS
        val state = mapOf("requirements" to requirements.map { mapOf("id" to it.id, "text" to it.text) }, "diff" to diff.take(MAX_DIFF_CHARS))
        val answers = jev.probabilities(state, questions(requirements)) ?: return null
        val probabilities = requirements.withIndex().mapNotNull { (i, r) -> answers["m$i"]?.let { r.id to it } }.toMap()
        val missing = if (truncated) emptyList() else requirements.filter { (probabilities[it.id] ?: 1.0) <= MISSING }
        val uncertain = requirements.filter { it !in missing && (probabilities[it.id] ?: 0.0) < MET }
        return Result(probabilities, missing, uncertain, truncated)
    }
}
