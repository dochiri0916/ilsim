package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.document.DocSection
import io.github.gudcks0305.jev.NoulQuestion
import kotlinx.serialization.Serializable

@Serializable
data class Guideline(val path: String, val line: Int, val heading: String, val text: String, val relevance: Double) {
    val source: String get() = "$path:$line"
}

object Guidelines {
    const val IRRELEVANT = 0.2
    const val VIOLATED = 0.8
    const val CLEAR = 0.4
    const val BATCH = 10
    const val PARALLEL = 4
    const val MAX_REQUEST_CHARS = 4000
    const val MAX_DIFF_CHARS = 60_000

    data class Check(val probabilities: Map<Guideline, Double>, val violated: List<Guideline>, val uncertain: List<Guideline>, val truncated: Boolean)

    fun select(jev: Jev, request: String, sections: List<DocSection>): List<Guideline>? {
        if (!jev.enabled) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(PARALLEL)
        try {
            val batches = sections.chunked(BATCH).map { batch -> pool.submit<List<Guideline>?> { selectBatch(jev, request, batch) } }
            return batches.map { it.get() ?: return null }.flatten()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun selectBatch(jev: Jev, request: String, batch: List<DocSection>): List<Guideline>? {
        val selected = mutableListOf<Guideline>()
        val state = mapOf(
            "request" to request.take(MAX_REQUEST_CHARS),
            "sections" to batch.mapIndexed { i, s -> mapOf("id" to "s$i", "source" to "${s.path}:${s.line}", "heading" to s.heading, "text" to s.text) },
        )
        val questions = batch.indices.associate { i ->
            "s$i" to NoulQuestion.of("s$i", "문서 구간 s$i 에 요청(request)을 이 프로젝트에서 구현하거나 테스트할 때 지켜야 할 지침·규칙·스펙(동작, 구조, 의존 방향, 이름, 포맷, 테스트 방식, 금지 사항 등)이 있는가? 프로젝트 전체에 적용되는 규칙도 해당한다. 요청과 무관한 기능 설명, 일정, 변경 이력, 설치·실행 안내뿐이면 No.")
                .withCriteria("요청 작업에 적용되는 지침이나 스펙이 있음", "요청 작업에 적용할 내용이 없음")
        }
        val answers = jev.probabilities(state, questions) ?: return null
        batch.forEachIndexed { i, s -> selected += Guideline(s.path, s.line, s.heading, s.text, answers["s$i"] ?: 1.0) }
        return selected
    }

    fun relevant(scored: List<Guideline>): List<Guideline> = scored.filter { it.relevance > IRRELEVANT }.sortedByDescending { it.relevance }

    fun scores(scored: List<Guideline>): List<String> = scored.map { "${it.source}=${"%.2f".format(it.relevance)}" }

    fun check(jev: Jev, stage: String, guidelines: List<Guideline>, diff: String): Check? {
        if (!jev.enabled) return null
        val truncated = diff.length > MAX_DIFF_CHARS
        val probabilities = linkedMapOf<Guideline, Double>()
        for (batch in guidelines.chunked(BATCH)) {
            val state = mapOf(
                "stage" to stage,
                "guidelines" to batch.mapIndexed { i, g -> mapOf("id" to "g$i", "source" to g.source, "heading" to g.heading, "text" to g.text) },
                "diff" to diff.take(MAX_DIFF_CHARS),
            )
            val questions = batch.indices.associate { i ->
                "v$i" to NoulQuestion.of("v$i", "변경(diff)이 지침 g$i 를 어기는가? diff 에서 지침과 다른 구조·이름·동작·의존·테스트 방식이 확인되면 Yes. 지침이 이 변경과 무관하거나 지키고 있으면 No. 이 단계(stage)에서 아직 만들 차례가 아닌 코드가 없는 것은 위반이 아니다.")
                    .withCriteria("변경이 지침을 어김", "변경이 지침을 지키거나 지침과 무관함")
            }
            val answers = jev.probabilities(state, questions) ?: return null
            batch.forEachIndexed { i, g -> answers["v$i"]?.let { probabilities[g] = it } }
        }
        val violated = guidelines.filter { (probabilities[it] ?: 0.0) >= VIOLATED }
        val uncertain = guidelines.filter { it !in violated && (truncated || (probabilities[it] ?: 1.0) > CLEAR) }
        return Check(probabilities, violated, uncertain, truncated)
    }
}
