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
    /** 요구사항 단계의 대화를 이어 갈 Claude 세션. 승인하면 버린다. 다른 단계는 항상 새 세션이라 null. */
    val sessionId: String?,
    /** 사용자가 보낸 대화 메시지(시간순). */
    val messages: List<String>,
    /** 아직 반영하지 못한 마지막 메시지. 있으면 다음 실행은 새로 쓰지 않고 이 말을 반영해 기존 문서를 고친다. */
    val pendingMessage: String?,
) {
    fun cachedAssessment(): Assessment? {
        if (routing["source"] != "jev") return null
        val complexity = routing["complexity"]?.toDoubleOrNull() ?: return null
        val risk = routing["risk"]?.toDoubleOrNull() ?: return null
        return Assessment(complexity, risk)
    }

    internal fun addCost(usd: Double, model: String): StageState = copy(
        costUsd = costUsd + usd,
        costByModel = costByModel + (model to (costByModel[model] ?: 0.0) + usd),
    )

    internal fun recordSession(id: String?): StageState = if (id.isNullOrBlank()) this else copy(sessionId = id)

    /** 승인 대기 중인 단계에 사용자 메시지를 받아 다시 실행 대기로 돌린다. 세션과 이전 대화는 이어 간다. */
    internal fun converse(text: String): StageState = copy(
        messages = messages + text,
        pendingMessage = text,
        feedback = null,
        attempt = Attempt.FIRST,
        lastErrorKey = null,
        status = StageStatus.PENDING,
    )

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
    ): StageState = StageState(
        stage, status, attempt, feedback, lastErrorKey, error, diff, artifactHash,
        artifacts, validation, routing, routingLog, costUsd, costByModel, sessionId, messages, pendingMessage,
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
        ): StageState = StageState(
            stage, status, attempt, feedback, lastErrorKey, error, diff, artifactHash,
            artifacts, validation, routing, routingLog, costUsd, costByModel, sessionId, messages, pendingMessage,
        )
    }
}
