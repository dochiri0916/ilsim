package com.dochiri.ilsim.task.domain

import com.dochiri.ilsim.task.domain.vo.Attempt
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus

class StageState private constructor(
    val stage: Stage,
    val status: StageStatus,
    val attempt: Attempt,
    val feedback: String?,
    val lastErrorKey: String?,
    val error: String?,
    val diff: String?,
    val artifactHash: String?,
    val artifacts: Map<String, String>,
    val validation: Map<String, String>,
    val routing: Map<String, String>,
    val routingLog: List<String>,
    val costUsd: Double,
    val costByModel: Map<String, Double>,
    val sessionId: String?,
    val messages: List<String>,
    val pendingMessage: String?,
    val executionId: String? = null,
    val executions: List<ExecutionRecord> = emptyList(),
    val usage: List<AiUsage> = emptyList(),
    val decisions: List<ProcessingDecision> = emptyList(),
) {
    internal fun addCost(usd: Double, model: String): StageState = copy(
        costUsd = costUsd + usd,
        costByModel = costByModel + (model to (costByModel[model] ?: 0.0) + usd),
    )

    internal fun recordSession(id: String?): StageState = if (id.isNullOrBlank()) this else copy(sessionId = id)

    fun recordExecution(record: ExecutionRecord): StageState = copy(executionId = record.id, executions = executions + record,
        usage = usage.map { if (it.executionId == null && it.purpose == "JEV_ROUTING") it.copy(executionId = record.id) else it })

    internal fun recordDecisions(records: List<ProcessingDecision>): StageState = copy(decisions = (decisions + records).distinctBy { it.id })

    internal fun finishPendingUsage(id: String?): StageState = if (id == null) this else copy(usage = usage.filterNot { it.id == id && it.costUsd == null && it.status == "UNKNOWN" })

    internal fun recordQualityFeedback(id: String, passed: Boolean, reason: String): StageState = copy(executions = executions.map {
        if (it.id != id) it else it.copy(passed = passed, failureKind = if (passed) null else "REGRESSION",
            validation = it.validation + ("user-feedback" to if (passed) "PASSED: $reason" else "FAILED: $reason"))
    })

    internal fun reviewFeedback(reviewId: String, quality: ReviewQuality): StageState = copy(usage = usage.map {
        if (it.review?.id == reviewId) it.copy(review = it.review.copy(quality = quality)) else it
    })

    internal fun account(calls: List<AiUsage>): StageState {
        var next = this
        calls.forEach { call ->
            if (next.usage.any { it.id == call.id }) return@forEach
            val migrated = call.cumulative && call.sessionId != null && next.sessionId == call.sessionId && next.costUsd > 0 && next.usage.none { it.sessionId == call.sessionId }
            val accounted = if (migrated) call.copy(status = "BASELINE", chargedUsd = null) else call.accounted(next.usage)
            next = next.copy(usage = next.usage + accounted)
            accounted.chargedUsd?.let { next = next.addCost(it, call.model) }
        }
        return next
    }

    internal fun completeExecution(outcome: StageOutcome): StageState = copy(executions = executions.map {
        if (it.id != outcome.executionId) it else it.copy(
            completed = true, passed = outcome.passed, model = outcome.model ?: it.model,
            failureKind = outcome.failureKind, validation = outcome.validation.orEmpty(),
            actualCostUsd = usage.filter { u -> u.executionId == outcome.executionId }.let { calls ->
                if (calls.isEmpty() || calls.any { u -> u.chargedUsd == null }) null else calls.sumOf { u -> u.chargedUsd ?: 0.0 }
            },
        )
    })

    internal fun converse(text: String): StageState = copy(
        messages = messages + text,
        pendingMessage = text,
        routing = emptyMap(),
        feedback = null,
        attempt = Attempt.FIRST,
        lastErrorKey = null,
        status = StageStatus.PENDING,
    )

    internal fun continueWith(text: String, problem: String?): StageState = converse(text).copy(feedback = problem)

    internal fun start(): StageState = copy(status = StageStatus.RUNNING)

    internal fun record(diff: String?, error: String?, validation: Map<String, String>?): StageState =
        copy(diff = diff, error = error, validation = validation.orEmpty())

    internal fun pass(artifacts: Map<String, String>, awaitApproval: Boolean): StageState = copy(
        artifacts = artifacts,
        artifactHash = ArtifactHash.of(artifacts),
        feedback = null,
        lastErrorKey = null,
        pendingMessage = null,
        status = if (awaitApproval) StageStatus.WAITING_APPROVAL else StageStatus.PASSED,
    )

    internal fun scheduleRetry(error: String?, maxAttempts: Int): StageState {
        val key = FailureSignature.of(error)
        val repeated = key != null && key == lastErrorKey
        return copy(
            lastErrorKey = key,
            feedback = error,
            attempt = if (repeated) Attempt.of(maxAttempts) else attempt.next(),
            status = StageStatus.PENDING,
        )
    }

    internal fun fail(message: String? = null): StageState = copy(error = message ?: error, pendingMessage = null, status = StageStatus.FAILED)

    internal fun approve(): StageState = copy(status = StageStatus.PASSED, sessionId = null, pendingMessage = null)

    internal fun restart(feedback: String?): StageState = copy(
        routing = emptyMap(),
        feedback = feedback,
        attempt = Attempt.FIRST,
        lastErrorKey = null,
        sessionId = null,
        pendingMessage = null,
        status = StageStatus.PENDING,
    )

    internal fun recoverInterrupted(): StageState =
        if (status == StageStatus.RUNNING) copy(status = StageStatus.PENDING) else this

    private fun copy(
        status: StageStatus = this.status,
        attempt: Attempt = this.attempt,
        feedback: String? = this.feedback,
        lastErrorKey: String? = this.lastErrorKey,
        error: String? = this.error,
        diff: String? = this.diff,
        artifactHash: String? = this.artifactHash,
        artifacts: Map<String, String> = this.artifacts,
        validation: Map<String, String> = this.validation,
        routing: Map<String, String> = this.routing,
        routingLog: List<String> = this.routingLog,
        costUsd: Double = this.costUsd,
        costByModel: Map<String, Double> = this.costByModel,
        sessionId: String? = this.sessionId,
        messages: List<String> = this.messages,
        pendingMessage: String? = this.pendingMessage,
        executionId: String? = this.executionId,
        executions: List<ExecutionRecord> = this.executions,
        usage: List<AiUsage> = this.usage,
        decisions: List<ProcessingDecision> = this.decisions,
    ): StageState = StageState(
        stage, status, attempt, feedback, lastErrorKey, error, diff, artifactHash,
        artifacts, validation, routing, routingLog, costUsd, costByModel, sessionId, messages, pendingMessage, executionId, executions, usage, decisions,
    )

    companion object {
        fun initial(stage: Stage): StageState = StageState(
            stage, StageStatus.PENDING, Attempt.FIRST, null, null, null, null, null,
            emptyMap(), emptyMap(), emptyMap(), emptyList(), 0.0, emptyMap(), null, emptyList(), null,
        )

        fun restore(
            stage: Stage,
            status: StageStatus,
            attempt: Attempt,
            feedback: String?,
            lastErrorKey: String?,
            error: String?,
            diff: String?,
            artifactHash: String?,
            artifacts: Map<String, String>,
            validation: Map<String, String>,
            routing: Map<String, String>,
            routingLog: List<String>,
            costUsd: Double,
            costByModel: Map<String, Double>,
            sessionId: String? = null,
            messages: List<String> = emptyList(),
            pendingMessage: String? = null,
            executionId: String? = null,
            executions: List<ExecutionRecord> = emptyList(),
            usage: List<AiUsage> = emptyList(),
            decisions: List<ProcessingDecision> = emptyList(),
        ): StageState = StageState(
            stage, status, attempt, feedback, lastErrorKey, error, diff, artifactHash,
            artifacts, validation, routing, routingLog, costUsd, costByModel, sessionId, messages, pendingMessage, executionId, executions, usage, decisions,
        )
    }
}
