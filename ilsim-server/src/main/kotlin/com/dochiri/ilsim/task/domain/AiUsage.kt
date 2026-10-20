package com.dochiri.ilsim.task.domain

data class AiUsage(
    val id: String,
    val purpose: String,
    val model: String,
    val effort: String = "medium",
    val sessionId: String? = null,
    val cliVersion: String? = null,
    val cumulative: Boolean = false,
    val costUsd: Double? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val status: String = "CONFIRMED",
    val chargedUsd: Double? = null,
    val toolCalls: Int = 0,
    val questions: Int = 0,
    val executionId: String? = null,
    val countedInputTokens: Long = 0,
    val countedOutputTokens: Long = 0,
    val countedCacheReadTokens: Long = 0,
    val countedCacheWriteTokens: Long = 0,
    val review: ReviewEvidence? = null,
) {
    fun accounted(previous: List<AiUsage>): AiUsage {
        val sameSession = previous.filter { cumulative && sessionId != null && it.sessionId == sessionId && it.model == model && it.cumulative }
        fun delta(value: Long, read: (AiUsage) -> Long): Long = (value - (sameSession.maxOfOrNull(read) ?: 0)).coerceAtLeast(0)
        val counted = copy(countedInputTokens = delta(inputTokens, AiUsage::inputTokens), countedOutputTokens = delta(outputTokens, AiUsage::outputTokens),
            countedCacheReadTokens = delta(cacheReadTokens, AiUsage::cacheReadTokens), countedCacheWriteTokens = delta(cacheWriteTokens, AiUsage::cacheWriteTokens))
        val cost = costUsd?.takeIf { it.isFinite() && it >= 0 }
        if (status != "CONFIRMED" || cost == null) return counted.copy(chargedUsd = null, status = if (status == "RECOVERED") "RECOVERED" else "UNKNOWN")
        if (!cumulative || sessionId == null) return counted.copy(chargedUsd = cost)
        val before = previous.filter { it.cumulative && it.sessionId == sessionId && it.model == model && it.status in setOf("CONFIRMED", "BASELINE") }
            .mapNotNull { it.costUsd }.maxOrNull() ?: 0.0
        return if (cost < before) counted.copy(chargedUsd = null, status = "UNKNOWN") else counted.copy(chargedUsd = cost - before)
    }
}

data class ExecutionRecord(
    val id: String,
    val model: String,
    val effort: String,
    val policyVersion: String,
    val expectedCostUsd: Double? = null,
    val predictedSuccess: Double? = null,
    val contextHash: String = "",
    val completed: Boolean = false,
    val passed: Boolean = false,
    val failureKind: String? = null,
    val validation: Map<String, String> = emptyMap(),
    val workload: String = "",
    val actualCostUsd: Double? = null,
    val adjustmentVersion: String? = null,
)
