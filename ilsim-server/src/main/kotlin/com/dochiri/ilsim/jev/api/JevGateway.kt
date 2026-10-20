package com.dochiri.ilsim.jev.api

interface JevGateway {
    fun enabled(): Boolean

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

data class JevAnswer(
    val type: String,
    val probability: Double? = null,
    val choice: String? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val score: Double? = null,
)
