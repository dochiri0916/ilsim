package com.dochiri.ilsim.task.domain

data class ReviewQuality(val passed: Boolean, val missed: Boolean = false, val falsePositive: Boolean = false, val reason: String) {
    fun validate() {
        require(reason.isNotBlank()) { "검토 품질의 확인 근거가 필요합니다" }
        require(!passed || !missed && !falsePositive) { "통과 피드백에 누락·오탐을 함께 지정할 수 없습니다" }
    }
}

data class ReviewEvidence(
    val id: String,
    val workload: String,
    val policyVersion: String,
    val inputHash: String,
    val response: String = "",
    val quality: ReviewQuality? = null,
    val sampleId: String? = null,
    val sampleOf: String? = null,
)

data class ReviewProfile(
    val purpose: String, val workload: String, val model: String, val effort: String,
    val samples: Int, val passed: Int, val missed: Int, val falsePositives: Int,
    val meanCostUsd: Double?, val eligible: Boolean,
)

data class ReviewSampleGrant(
    val id: String, val executionId: String, val policyVersion: String,
    val model: String, val effort: String, val maxCostUsd: Double,
)

data class ReviewPolicySnapshot(
    val version: String = ReviewCostPolicy.VERSION,
    val profiles: List<ReviewProfile> = emptyList(),
    val sample: ReviewSampleGrant? = null,
)

data class ReviewCostPolicy(
    val version: String = VERSION,
    val minimumSamples: Int = 5,
    val historyLimit: Int = 100,
    val qualityThreshold: Double = 0.97,
    val comparisonEvery: Int = 5,
    val calibrate: Boolean = true,
    val sampleEvery: Int = 20,
    val sampleLimit: Int = 0,
    val sampleBudgetUsd: Double = 0.0,
    val sampleMaxUsd: Double = 0.05,
) {
    fun snapshot(usage: List<AiUsage>): ReviewPolicySnapshot {
        if (!calibrate) return ReviewPolicySnapshot(version)
        val calls = usage.filter { it.review?.policyVersion == version }.groupBy { it.review!!.id }.values.mapNotNull { parts ->
            if (parts.any { it.status != "CONFIRMED" || it.chargedUsd == null } || parts.map { it.model to it.effort }.distinct().size != 1) return@mapNotNull null
            val first = parts.first()
            first.copy(chargedUsd = parts.sumOf { it.chargedUsd!! })
        }
        val labeled = calls.filter { it.review!!.quality != null }
        val profiles = labeled.groupBy { listOf(it.purpose.removePrefix("ANALYSIS_"), it.review!!.workload) }.flatMap { (_, group) ->
            val history = group.takeLast(historyLimit.coerceAtLeast(1))
            val cutoff = history.size / comparisonEvery.coerceAtLeast(1) * comparisonEvery.coerceAtLeast(1)
            val recent = history.take(cutoff) + history.drop(cutoff).filter { !it.review!!.quality!!.passed }
            val control = recent.filter { it.model == ModelTier.SONNET.modelId && it.effort == "medium" }
            recent.groupBy { it.model to it.effort }.map { (option, samples) ->
                val passed = samples.count { it.review!!.quality!!.passed }
                val missed = samples.count { it.review!!.quality!!.missed }
                val falsePositives = samples.count { it.review!!.quality!!.falsePositive }
                fun rate(list: List<AiUsage>, predicate: (ReviewQuality) -> Boolean) = list.count { predicate(it.review!!.quality!!) }.toDouble() / list.size.coerceAtLeast(1)
                val baseline = option.first == ModelTier.SONNET.modelId && option.second == "medium"
                val admitted = baseline || samples.size >= minimumSamples && passed >= minimumSamples && control.size >= minimumSamples &&
                    passed.toDouble() / samples.size >= qualityThreshold &&
                    rate(samples) { it.passed } >= rate(control) { it.passed } &&
                    rate(samples) { it.missed } <= rate(control) { it.missed } &&
                    rate(samples) { it.falsePositive } <= rate(control) { it.falsePositive }
                ReviewProfile(samples.first().purpose.removePrefix("ANALYSIS_"), samples.first().review!!.workload, option.first, option.second,
                    samples.size, passed, missed, falsePositives, samples.mapNotNull { it.chargedUsd }.average(), admitted)
            }
        }
        return ReviewPolicySnapshot(version, profiles)
    }

    fun grant(id: String, executionId: String, codeExecutions: Int, previous: List<ReviewSampleGrant>): ReviewSampleGrant? {
        if (codeExecutions <= 0 || sampleLimit <= 0 || !sampleBudgetUsd.isFinite() || !sampleMaxUsd.isFinite() || sampleBudgetUsd <= 0 || sampleMaxUsd <= 0 ||
            codeExecutions % sampleEvery.coerceAtLeast(1) != 0) return null
        val used = previous.filter { it.policyVersion == version }
        if (used.size >= sampleLimit || used.sumOf { it.maxCostUsd } + sampleMaxUsd > sampleBudgetUsd + 1e-9) return null
        val candidates = ModelOptions.all.filterNot { it.tier == ModelTier.SONNET && it.effort == "medium" }
        val option = candidates[used.size % candidates.size]
        return ReviewSampleGrant(id, executionId, version, option.tier.modelId, option.effort, sampleMaxUsd)
    }

    companion object { const val VERSION = "review-cost-v2-support-5.5" }
}
