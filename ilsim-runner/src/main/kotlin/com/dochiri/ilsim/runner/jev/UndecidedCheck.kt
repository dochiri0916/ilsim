package com.dochiri.ilsim.runner.jev

import io.github.gudcks0305.jev.NoulQuestion

object UndecidedCheck {
    const val DECIDED = 0.6
    const val OPEN = 0.4
    private const val MAX_SPEC_CHARS = 6_000
    private const val MAX_QUESTION_CHARS = 300
    private val numbered = Regex("""^[\s*_\[(]*(Q\d+)\b.*""", RegexOption.IGNORE_CASE)
    private val STAGES = mapOf(
        "TEST_CASES" to "테스트 케이스 단계. change는 이번에 작성한 테스트 케이스 문서 전체다.",
        "TEST_CODE" to "테스트 코드 단계. change는 이번 단계에서 바꾼 테스트 코드 diff다.",
        "IMPLEMENTATION" to "구현 단계. change는 이번 단계에서 바꾼 프로덕트 코드와 설정 diff다. 테스트는 앞 단계에서 작성됐다.",
    )

    data class Question(val id: String, val text: String) {
        val label: String get() = if (numbered.matchEntire(text)?.groupValues?.get(1).equals(id, ignoreCase = true)) text else "$id. $text"
    }

    data class Decision(val question: String?, val text: String) {
        val label: String get() = listOfNotNull(question, text).joinToString(": ")
    }

    data class Result(val probabilities: Map<String, Double>, val decided: List<Question>, val uncertain: List<Question>, val truncated: Boolean)

    fun questions(markdown: String): List<Question> {
        val items = RequirementCheck.openQuestions(markdown)
        val ids = items.map { numbered.matchEntire(it)?.groupValues?.get(1)?.uppercase() }
        val explicit = ids.none { it == null } && ids.toSet().size == ids.size
        return items.mapIndexed { i, item -> Question(if (explicit) ids[i]!! else "Q${i + 1}", item) }
    }

    fun open(markdown: String, decisions: List<Decision>): List<Question> {
        val answered = decisions.mapNotNull { it.question?.trim()?.uppercase() }.toSet()
        return questions(markdown).filter { it.id !in answered }
    }

    fun judge(jev: Jev, spec: String, stage: String, questions: List<Question>, decisions: List<Decision>, change: String): Result? {
        if (questions.isEmpty()) return Result(emptyMap(), emptyList(), emptyList(), false)
        if (!jev.enabled) return null
        val shown = shown(spec, stage, questions, decisions, change)
        val probabilities = ask(jev, spec, stage, questions, decisions, shown) ?: return null
        val decided = questions.filter { (probabilities[it.id] ?: 0.0) >= DECIDED }
        val uncertain = questions.filter { it !in decided && (probabilities[it.id] ?: 1.0) > OPEN }
        return Result(probabilities, decided, uncertain, shown.length < change.length)
    }

    internal fun shown(spec: String, stage: String, questions: List<Question>, decisions: List<Decision>, change: String): String {
        val fixed = Guidelines.weight(spec.take(MAX_SPEC_CHARS)) + Guidelines.weight(STAGES[stage].orEmpty()) +
            decisions.sumOf { Guidelines.weight(it.question.orEmpty() + it.text) } + questions.sumOf { Guidelines.weight(it.text.take(MAX_QUESTION_CHARS)) }
        return Guidelines.cut(change, (Guidelines.MAX_STATE_WEIGHT - fixed).coerceAtLeast(Guidelines.MAX_DIFF_WEIGHT / 4))
    }

    internal fun question(i: Int, q: Question): NoulQuestion =
        NoulQuestion.of("u$i", "open의 ${q.id}「${q.text.take(MAX_QUESTION_CHARS)}」는 사용자가 아직 답하지 않은 질문이다. change에 이 질문의 답을 고른 흔적이 있는가? 흔적은 (1) 이 질문의 상황만을 위한 조건 분기·검증·예외·필터·락·상수·설정값·기본값, (2) 질문이 고르라는 선택지(반환 형태·인자 타입·형식·경계 비교·상태 값·상태 코드) 중 하나를 쓴 코드, (3) 이 질문의 상황에서 나올 결과를 단정하는 테스트나 테스트 케이스다. 흔적이 있으면 Yes. 이 상황을 위한 코드·테스트가 따로 없으면 그 상황의 결과가 다른 요구사항을 구현한 코드에 따라 어떻게 나오든 No. 값을 고르지 않고 기본값 없는 설정·인자로 받게만 한 경우, 미결정이라 제외한다고만 적은 경우, 테스트가 테스트 전용 설정값을 넣어 검증만 하는 경우, decided·spec에 이미 정해진 동작만 다루는 경우도 No.")
            .withCriteria("답을 고른 흔적이 있음", "답을 고른 흔적이 없음")

    internal fun ask(jev: Jev, spec: String, stage: String, questions: List<Question>, decisions: List<Decision>, change: String): Map<String, Double>? {
        val state = { batch: List<Question> ->
            mapOf(
                "stage" to "$stage: ${STAGES[stage].orEmpty()}",
                "spec" to spec.take(MAX_SPEC_CHARS),
                "decided" to decisions.map { it.label },
                "open" to batch.map { mapOf("id" to it.id, "question" to it.text.take(MAX_QUESTION_CHARS)) },
                "change" to change,
            )
        }
        val batches = questions.chunked(Guidelines.BATCH)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(Guidelines.PARALLEL)
        try {
            return batches.map { batch ->
                pool.submit<Map<String, Double>?> {
                    val answers = jev.probabilities(state(batch), batch.withIndex().associate { (i, q) -> "u$i" to question(i, q) }) ?: return@submit null
                    batch.withIndex().mapNotNull { (i, q) -> answers["u$i"]?.let { q.id to it } }.toMap()
                }
            }.map { it.get() ?: return null }.fold(emptyMap()) { a, b -> a + b }
        } finally {
            pool.shutdownNow()
        }
    }
}
