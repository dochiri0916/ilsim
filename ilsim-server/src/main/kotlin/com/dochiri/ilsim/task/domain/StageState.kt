package com.dochiri.ilsim.task.domain

import com.dochiri.ilsim.task.domain.vo.Attempt
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus

/** 한 단계의 진행 상태. 불변 객체이며 상태 전이는 [Task] 를 통해서만 일어난다. 바꾸는 메서드는 새 인스턴스를 돌려준다. */
class StageState private constructor(
    val stage: Stage,
    val status: StageStatus,
    val attempt: Attempt,
    val feedback: String?,
    /** 직전 실패의 정규화한 사유. */
    val lastErrorKey: String?,
    val error: String?,
    val diff: String?,
    val artifactHash: String?,
    val artifacts: Map<String, String>,
    /** tests/analysis 등 검증 항목별 결과. 실행하지 못한 항목은 UNKNOWN. */
    val validation: Map<String, String>,
    /** JEV 평가 결과(complexity, risk)와 이번에 고른 모델. 같은 단계의 재시도에서는 평가를 다시 하지 않고 재사용한다. */
    val routing: Map<String, String>,
    /** 모델 선택 이력: 시도마다 어떤 모델을 왜 골랐는지. */
    val routingLog: List<String>,
    /** 이 단계에서 쓴 Claude 비용 합계(USD). 실패한 시도와 재시도 비용도 포함한다. */
    val costUsd: Double,
    val costByModel: Map<String, Double>,
    /** 마지막 실행의 Claude 세션. 요구사항 대화나 멈춘 단계에 사용자가 답할 때 이어 간다. 승인하면 버린다. */
    val sessionId: String?,
    /** 사용자가 보낸 대화 메시지(시간순). */
    val messages: List<String>,
    /** 아직 반영하지 못한 마지막 메시지. 있으면 다음 실행은 새로 쓰지 않고 이 말을 반영해 기존 문서를 고친다. */
    val pendingMessage: String?,
    val executionId: String? = null,
    val executions: List<ExecutionRecord> = emptyList(),
    val usage: List<AiUsage> = emptyList(),
    val decisions: List<ProcessingDecision> = emptyList(),
) {
    fun cachedAssessment(): Assessment? {
        if (routing["source"] != "jev") return null
        val complexity = routing["complexity"]?.toDoubleOrNull() ?: return null
        val risk = routing["risk"]?.toDoubleOrNull() ?: return null
        if (!complexity.isFinite() || complexity !in 0.0..3.0 || !risk.isFinite() || risk !in 0.0..2.0) return null
        val candidates = routing.filterKeys { it.startsWith("candidate:") }.mapNotNull { (key, value) ->
            val parts = value.split(',')
            val option = key.removePrefix("candidate:").split('/')
            if (parts.size != 2 || option.size != 2) null else {
                val success = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val uncertainty = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                CandidateAssessment(option[0], option[1], success, uncertainty)
            }
        }
        return Assessment(complexity, risk, candidates)
    }

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

    fun recordCostRoute(assessment: Assessment?, route: CostRoute, attempt: Int, hash: String, policy: String): StageState = copy(
        routing = mapOf(
            "source" to if (assessment == null) "fallback" else "jev", "contextHash" to hash,
            "complexity" to (assessment?.complexity?.toString() ?: ""), "risk" to (assessment?.risk?.toString() ?: ""),
            "model" to route.option.tier.label, "effort" to route.option.effort, "policyVersion" to policy,
            "expectedCostUsd" to route.expectedCostUsd.toString(), "predictedSuccess" to route.success.toString(),
        ) + assessment?.candidates.orEmpty().associate { "candidate:${it.model}/${it.effort}" to "${it.success},${it.uncertainty}" },
        routingLog = routingLog + "시도 $attempt: ${route.option.tier.label}/${route.option.effort} (${if (assessment == null) "규칙 기반 fallback" else "JEV"}, ${route.reason}, 정책 $policy, 예상 $${route.expectedCostUsd})",
    )

    /** 승인 대기 중인 단계에 사용자 메시지를 받아 다시 실행 대기로 돌린다. 세션과 이전 대화는 이어 간다. */
    internal fun converse(text: String): StageState = copy(
        messages = messages + text,
        pendingMessage = text,
        routing = emptyMap(),
        feedback = null,
        attempt = Attempt.FIRST,
        lastErrorKey = null,
        status = StageStatus.PENDING,
    )

    /** 멈춘 단계에 사용자 말을 받아 같은 세션에서 이어 가게 한다. [problem]은 직전 실패 사유. */
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

    /** 같은 사유로 되풀이됐으면 남은 시도를 건너뛰고 마지막 시도로 올린다. */
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

    fun recordAssessedRoute(assessment: Assessment, tier: ModelTier, attempt: Int): StageState = copy(
        routing = linkedMapOf(
            "source" to "jev",
            "complexity" to "%.2f".format(java.util.Locale.ROOT, assessment.complexity),
            "risk" to "%.2f".format(java.util.Locale.ROOT, assessment.risk),
            "model" to tier.label,
        ),
        routingLog = routingLog + "시도 %d: %s (JEV 난이도 %.2f/3, 위험 %.2f/2)".format(
            java.util.Locale.ROOT, attempt, tier.label, assessment.complexity, assessment.risk,
        ),
    )

    fun recordFallbackRoute(tier: ModelTier, attempt: Int, reason: String): StageState =
        copy(routingLog = routingLog + "시도 $attempt: ${tier.label} (규칙 기반, $reason)")

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

        /** 저장본에서 되살린다. */
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
