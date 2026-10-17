package com.dochiri.ilsim.task.application.command

import com.dochiri.ilsim.task.domain.StageOutcome

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
) {
    fun toOutcome(): StageOutcome = StageOutcome(passed, retryable, diff, error, artifacts, validation, branch, costUsd, model, reviewCostUsd, reviewModel, sessionId)
}
