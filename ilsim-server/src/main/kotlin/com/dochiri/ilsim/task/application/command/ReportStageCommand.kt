package com.dochiri.ilsim.task.application.command

import com.dochiri.ilsim.task.domain.StageOutcome
import com.dochiri.ilsim.task.domain.AiUsage
import com.dochiri.ilsim.task.domain.ProcessingDecision

data class ReportStageCommand(
    val passed: Boolean,
    val retryable: Boolean,
    val diff: String? = null,
    val error: String? = null,
    val artifacts: Map<String, String>? = null,
    val validation: Map<String, String>? = null,
    val branch: String? = null,
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
) {
    fun toOutcome(): StageOutcome = StageOutcome(passed, retryable, diff, error, artifacts, validation, branch, costUsd, model, reviewCostUsd, reviewModel, sessionId, executionId, stage, usage, failureKind, decisions, answered)
}
