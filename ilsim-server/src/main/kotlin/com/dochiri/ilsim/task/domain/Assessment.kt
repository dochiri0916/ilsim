package com.dochiri.ilsim.task.domain

import kotlin.math.max
import kotlin.math.min

data class Assessment(val complexity: Double, val risk: Double, val candidates: List<CandidateAssessment> = emptyList())

data class CandidateAssessment(val model: String, val effort: String, val success: Double, val uncertainty: Double = 0.0)

object RoutingPolicy {

    fun choose(assessment: Assessment, attempt: Int, minimum: ModelTier): ModelTier {
        var tier = 0
        if (assessment.complexity >= 1.5 || assessment.risk >= 0.8) tier = 1
        if (assessment.complexity >= 2.5 || assessment.risk >= 1.5) tier = 2
        tier = max(tier, minimum.ordinal)
        return ModelTier.entries[tier]
    }

    fun fallback(attempt: Int): ModelTier = ModelTier.SONNET
}
