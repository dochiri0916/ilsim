package com.dochiri.ilsim.task.domain

data class StageOutcome(
    val passed: Boolean,
    val retryable: Boolean,
    val diff: String?,
    val error: String?,
    val artifacts: Map<String, String>?,
    val validation: Map<String, String>?,
    val branch: String?,
    val costUsd: Double? = null,
    val model: String? = null,
    val reviewCostUsd: Double? = null,
    val reviewModel: String? = null,
    val sessionId: String? = null,
    val executionId: String? = null,
    val stage: String? = null,
    val usage: List<AiUsage> = emptyList(),
    val failureKind: String? = null,
    val decisions: List<ProcessingDecision> = emptyList(),
    val answered: Boolean = false,
)
