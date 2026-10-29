package com.dochiri.ilsim.runner.jev

import io.github.gudcks0305.jev.NoulQuestion

object RequirementCheck {
    const val MET = 0.8
    const val MISSING = 0.2
    private const val STAGE = "구현 단계. diff는 이번 단계에서 바꾼 프로덕트 코드만 담는다. 테스트는 앞 단계(테스트 코드 단계)에서 이미 작성·커밋돼 이 diff에 없다."

    data class Result(val probabilities: Map<String, Double>, val missing: List<Requirement>, val uncertain: List<Requirement>, val truncated: Boolean)

    private val item = Regex("""(REQ-\d+)\s*[:：.)\]|-]?\s*(.*)""")
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val listItem = Regex("""^(?:[-*+]|\d+[.)])\s+(.*)$""")
    private val nothing = Regex("""^\(?\s*(없음|해당\s*없음|none)\s*\)?\.?$""", RegexOption.IGNORE_CASE)

    fun openQuestions(markdown: String): List<String> {
        val items = mutableListOf<String>()
        var level = 0
        for (line in markdown.lines()) {
            val h = heading.matchEntire(line.trimEnd())
            if (h != null) {
                val depth = h.groupValues[1].length
                when {
                    "미결정" in h.groupValues[2] -> level = depth
                    level > 0 && depth <= level -> level = 0
                    level > 0 -> items += h.groupValues[2].trim()
                }
                continue
            }
            if (level == 0 || line.isBlank()) continue
            val item = listItem.matchEntire(line.trimEnd())
            when {
                item != null -> items += item.groupValues[1].trim()
                items.isNotEmpty() -> items[items.lastIndex] = items.last() + " " + line.trim()
            }
        }
        return items.filterNot { nothing.matches(it) }
    }

    fun parse(markdown: String): List<Requirement> = markdown.lines().mapNotNull { line ->
        val m = item.find(line) ?: return@mapNotNull null
        val text = m.groupValues[2].replace(Regex("""[*`|]"""), " ").replace(Regex("""\s+"""), " ").trim()
        Requirement(m.groupValues[1], text.ifEmpty { line.trim() })
    }.distinctBy { it.id }

    fun questions(requirements: List<Requirement>): Map<String, NoulQuestion> = requirements.withIndex().flatMap { (i, r) -> listOf(
        "m$i" to NoulQuestion.of("m$i", "변경(diff)이 요구사항 ${r.id}「${r.text}」를 실행 코드나 설정에서 완전히 구현하는가? 테스트만 있거나 TODO·주석뿐이거나 조건·범위가 요구사항과 다르면 No. 단, stage 대로 테스트는 앞 단계에서 이미 작성돼 이 diff에 없다. 요구사항 중 테스트 자체에 관한 부분(테스트 작성·이름·애너테이션·테스트 분리·테스트 통과)은 충족된 것으로 보고 나머지 동작만 이 diff로 판정하며, 테스트에 관한 부분뿐이면 Yes.")
            .withCriteria("요구사항의 동작이 구현에서 확인됨", "구현이 없거나 일부만 있거나 조건이 다름"),
        "d$i" to NoulQuestion.of("d$i", "제공된 diff만으로 요구사항 ${r.id}의 충족 여부를 확정할 수 있는가? 변경 밖의 코드나 설정을 확인해야 하면 No."),
    ) }.toMap()

    fun judge(jev: Jev, requirements: List<Requirement>, diff: String): Result? {
        if (!jev.enabled || requirements.isEmpty()) return null
        val shown = Guidelines.cut(diff, Guidelines.MAX_DIFF_WEIGHT)
        val truncated = shown.length < diff.length
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        val probabilities = try {
            val first = batches(requirements, Guidelines.MAX_STATE_WEIGHT - Guidelines.weight(shown) - Guidelines.weight(STAGE)).map { batch -> pool.submit<Map<String, Double>?> { judgeBatch(jev, batch, shown) } }
                .map { it.get() ?: return null }.fold(emptyMap<String, Double>()) { a, b -> a + b }
            val again = requirements.filter { r -> first[r.id]?.let { it > MISSING && it < MET } == true }
                .map { r -> r.id to pool.submit<Map<String, Double>?> { judgeBatch(jev, listOf(r), shown) } }
                .mapNotNull { (id, single) -> single.get()?.get(id)?.let { id to maxOf(it, first.getValue(id)) } }.toMap()
            first + again
        } finally {
            pool.shutdownNow()
        }
        val missing = if (truncated) emptyList() else requirements.filter { (probabilities[it.id] ?: 1.0) <= MISSING }
        val uncertain = requirements.filter { it !in missing && (probabilities[it.id] ?: 0.0) < MET }
        return Result(requirements.mapNotNull { r -> probabilities[r.id]?.let { r.id to it } }.toMap(), missing, uncertain, truncated)
    }

    private fun batches(requirements: List<Requirement>, budget: Int): List<List<Requirement>> {
        val batches = mutableListOf<MutableList<Requirement>>()
        var used = 0
        for (requirement in requirements) {
            val size = Guidelines.weight(requirement.id) + Guidelines.weight(requirement.text)
            if (batches.isEmpty() || batches.last().size >= Guidelines.BATCH || used + size > budget) { batches += mutableListOf<Requirement>(); used = 0 }
            batches.last() += requirement
            used += size
        }
        return batches
    }

    internal fun state(requirements: List<Requirement>, diff: String): Map<String, Any> =
        mapOf("stage" to STAGE, "requirements" to requirements.map { mapOf("id" to it.id, "text" to it.text) }, "diff" to diff)

    private fun judgeBatch(jev: Jev, batch: List<Requirement>, diff: String): Map<String, Double>? {
        val answers = jev.probabilities(state(batch, diff), questions(batch)) ?: return null
        return batch.withIndex().mapNotNull { (i, r) -> answers["m$i"]?.let { r.id to it } }.toMap()
    }
}
