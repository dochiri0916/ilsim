package com.dochiri.ilsim.runner

import kotlinx.serialization.Serializable

@Serializable
data class ReviewQuality(val passed: Boolean, val missed: Boolean = false, val falsePositive: Boolean = false, val reason: String)

@Serializable
data class ReviewEvidence(
    val id: String, val workload: String, val policyVersion: String, val inputHash: String,
    val response: String = "", val quality: ReviewQuality? = null,
    val sampleId: String? = null, val sampleOf: String? = null,
)

@Serializable
data class ReviewProfile(
    val purpose: String, val workload: String, val model: String, val effort: String,
    val samples: Int, val passed: Int, val missed: Int, val falsePositives: Int,
    val meanCostUsd: Double?, val eligible: Boolean,
)

@Serializable
data class ReviewSampleGrant(
    val id: String, val executionId: String, val policyVersion: String,
    val model: String, val effort: String, val maxCostUsd: Double,
)

@Serializable
data class ReviewPolicySnapshot(
    val version: String = ReviewRouter.POLICY,
    val profiles: List<ReviewProfile> = emptyList(),
    val sample: ReviewSampleGrant? = null,
)

object ReviewContext {
    private data class Context(val policy: ReviewPolicySnapshot, val stage: String, var sampled: Boolean)
    private val current = ThreadLocal<Context?>()
    private val purpose = ThreadLocal<String?>()
    val policy: ReviewPolicySnapshot get() = current.get()?.policy ?: ReviewPolicySnapshot()
    val purposeName: String get() = purpose.get() ?: "REVIEW"
    fun workload(chars: Int): String = "${current.get()?.stage ?: "ANALYSIS"}:${when { chars < 12_000 -> "small"; chars < 90_000 -> "medium"; else -> "large" }}"
    fun sample(): ReviewSampleGrant? {
        val context = current.get() ?: return null
        if (context.sampled) return null
        return context.policy.sample?.also { context.sampled = true }
    }
    fun <T> within(policy: ReviewPolicySnapshot, stage: String, alreadySampled: Boolean = false, action: () -> T): T {
        val previous = current.get()
        current.set(Context(policy, stage, alreadySampled))
        return try { action() } finally { current.set(previous) }
    }
    fun <T> forPurpose(name: String, action: () -> T): T {
        val previous = purpose.get()
        purpose.set(name)
        return try { action() } finally { purpose.set(previous) }
    }
}
