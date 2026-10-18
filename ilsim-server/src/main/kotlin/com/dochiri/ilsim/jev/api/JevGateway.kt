package com.dochiri.ilsim.jev.api

/**
 * JEV(결정 모델)에 질문 3종(boolean, choice, score)을 한 번에 보내고 확률·선택·점수를 받는다.
 * JEV 는 텍스트를 만들지 않고 판단만 하므로 결과는 판단 근거로만 쓰고 최종 결정은 호출한 쪽의 정책이 한다.
 * 키가 없거나 호출이 실패하면 빈 값(null)을 돌려주어 호출한 쪽이 규칙 기반 동작으로 되돌아가게 한다.
 */
interface JevGateway {
    fun enabled(): Boolean

    /** 질문 이름 → 답. 키가 없거나 호출·해석에 실패하면 null. */
    fun evaluate(state: Any, questions: Map<String, JevQuestion>): Map<String, JevAnswer>?
    fun drainUsage(): List<JevUsage> = emptyList()
}

data class JevUsage(val id: String, val model: String, val inputTokens: Long, val outputTokens: Long, val questions: Int, val costUsd: Double? = null)

class JevQuestion private constructor(val type: String, val instructions: String, val criteria: Any?) {
    companion object {
        fun score(instructions: String, levels: List<String>): JevQuestion = JevQuestion("score", instructions, levels)

        fun choice(instructions: String, options: Map<String, String>): JevQuestion = JevQuestion("choice", instructions, options)

        fun bool(instructions: String): JevQuestion = JevQuestion("boolean", instructions, null)
    }
}

/** 질문 종류에 따라 일부 필드만 채워진다: boolean=probability, choice=choice+probabilities, score=score+probabilities. */
data class JevAnswer(
    val type: String,
    val probability: Double? = null,
    val choice: String? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val score: Double? = null,
)
