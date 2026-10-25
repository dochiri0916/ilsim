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
    const val APPLIES = 0.5
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
        val questions = batch.indices.flatMap { i ->
            listOf(
                "r$i" to NoulQuestion.of("r$i", "문서 구간 s$i 의 text 자체에 코드를 만들거나 고칠 때 따라야 할 구체적인 규칙·스펙(정해진 동작, 문구, 구조, 의존 방향, 이름, 포맷, 테스트 방식, 금지 사항 등)이 문장이나 표로 적혀 있는가? 소개, 배경, 목적, 개요, 미결정 사항·질문, 제외 목록, 일정, 변경 이력, 연락처, 설치·빌드·실행·배포 안내처럼 지킬 규칙이 없는 구간이면 No.")
                    .withCriteria("따라야 할 구체적 규칙이 적혀 있음", "지킬 규칙이 없는 설명·안내 구간"),
                "s$i" to NoulQuestion.of("s$i", "요청(request)을 이 프로젝트에서 구현하거나 테스트할 때 새로 만들거나 바꾸는 코드에 문서 구간 s$i 의 규칙이 적용되는가? 프로젝트 전체에 적용되는 규칙은 Yes. 특정 기능 하나만의 요구사항·테스트 케이스는 요청이 그 기능 자체를 바꿀 때만 Yes이고, 다른 기능을 새로 추가하는 요청이면 No.")
                    .withCriteria("요청 작업 코드에 적용됨", "요청 작업 코드에 적용되지 않음"),
            )
        }.toMap()
        val answers = jev.probabilities(state, questions) ?: return null
        batch.forEachIndexed { i, s -> selected += Guideline(s.path, s.line, s.heading, s.text, minOf(answers["r$i"] ?: 1.0, answers["s$i"] ?: 1.0)) }
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
                "v$i" to NoulQuestion.of("v$i", "변경(diff)이 지침 g$i 의 text 에 적힌 규칙을 직접 어기는가? g$i 의 text 에 적힌 규칙만 기준으로 하고, 다른 지침의 위반은 g$i 위반으로 보지 않는다. diff 에 g$i 가 정한 것과 다른 구조·이름·문구·동작·의존·테스트 방식이 있으면 Yes. g$i 에 지킬 규칙이 없거나(소개·배경·미결정 사항 등), 규칙이 이 변경 코드에 적용되지 않거나, 지키고 있으면 No. 이 단계(stage)에서 아직 만들 차례가 아닌 코드가 없는 것은 위반이 아니다.")
                    .withCriteria("변경이 지침을 어김", "변경이 지침을 지키거나 지침과 무관함")
            }
            val answers = jev.probabilities(state, questions) ?: return null
            batch.forEachIndexed { i, g -> answers["v$i"]?.let { probabilities[g] = it } }
        }
        val violated = guidelines.filter { (probabilities[it] ?: 0.0) >= VIOLATED }
        val uncertain = guidelines.filter { it !in violated && (truncated || (it.relevance >= APPLIES && (probabilities[it] ?: 1.0) > CLEAR)) }
        return Check(probabilities, violated, uncertain, truncated)
    }
}
