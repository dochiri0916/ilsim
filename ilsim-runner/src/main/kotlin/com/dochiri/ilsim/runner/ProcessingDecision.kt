package com.dochiri.ilsim.runner

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class ProcessingDecision(val id: String, val purpose: String, val handler: String, val reason: String, val evidenceHash: String, val policyVersion: String)

object DecisionScope {
    private val current = ThreadLocal<MutableList<ProcessingDecision>?>()
    fun record(purpose: String, handler: String, reason: String, evidence: String, policy: String = ReviewRouter.POLICY) {
        current.get()?.add(ProcessingDecision(UUID.randomUUID().toString(), purpose, handler, reason, ReviewCache.digest(evidence), policy))
    }
    fun <T> collect(decisions: MutableList<ProcessingDecision>, action: () -> T): T {
        val before = current.get(); current.set(decisions)
        return try { action() } finally { current.set(before) }
    }
}
