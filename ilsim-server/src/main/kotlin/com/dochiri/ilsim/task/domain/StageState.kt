package com.dochiri.ilsim.task.domain

import java.util.concurrent.CopyOnWriteArrayList

/** 한 단계의 진행 상태. 상태 전이는 [Task] 를 통해서만 일어난다. */
class StageState(
    val stage: Stage,
    status: StageStatus = StageStatus.PENDING,
    attempt: Int = 1,
    feedback: String? = null,
    lastErrorKey: String? = null,
    error: String? = null,
    diff: String? = null,
    artifactHash: String? = null,
    artifacts: Map<String, String> = emptyMap(),
    validation: Map<String, String> = emptyMap(),
    routing: Map<String, String> = emptyMap(),
    routingLog: List<String> = emptyList(),
) {
    @Volatile var status: StageStatus = status
        private set
    @Volatile var attempt: Int = attempt
        private set
    @Volatile var feedback: String? = feedback
        private set

    /** 직전 실패의 정규화한 사유. */
    @Volatile var lastErrorKey: String? = lastErrorKey
        private set
    @Volatile var error: String? = error
        private set
    @Volatile var diff: String? = diff
        private set
    @Volatile var artifactHash: String? = artifactHash
        private set
    @Volatile var artifacts: Map<String, String> = artifacts
        private set

    /** tests/analysis 등 검증 항목별 결과. 실행하지 못한 항목은 UNKNOWN. */
    @Volatile var validation: Map<String, String> = validation
        private set

    /** JEV 평가 결과(complexity, risk)와 이번에 고른 모델. 같은 단계의 재시도에서는 평가를 다시 하지 않고 재사용한다. */
    @Volatile var routing: Map<String, String> = routing
        private set

    /** 모델 선택 이력: 시도마다 어떤 모델을 왜 골랐는지. */
    val routingLog: MutableList<String> = CopyOnWriteArrayList(routingLog)

    internal fun start() {
        status = StageStatus.RUNNING
    }

    internal fun record(diff: String?, error: String?, validation: Map<String, String>?) {
        this.diff = diff
        this.error = error
        this.validation = validation ?: emptyMap()
    }

    internal fun pass(artifacts: Map<String, String>, awaitApproval: Boolean) {
        this.artifacts = artifacts
        artifactHash = ArtifactHash.of(artifacts)
        feedback = null
        lastErrorKey = null
        status = if (awaitApproval) StageStatus.WAITING_APPROVAL else StageStatus.PASSED
    }

    /** 같은 사유로 되풀이됐으면 남은 시도를 건너뛰고 마지막 시도로 올린다. */
    internal fun scheduleRetry(error: String?, maxAttempts: Int) {
        val key = FailureSignature.of(error)
        val repeated = key != null && key == lastErrorKey
        lastErrorKey = key
        feedback = error
        attempt = if (repeated) maxAttempts else attempt + 1
        status = StageStatus.PENDING
    }

    internal fun fail(message: String? = null) {
        if (message != null) error = message
        status = StageStatus.FAILED
    }

    internal fun approve() {
        status = StageStatus.PASSED
    }

    internal fun restart(feedback: String?) {
        this.feedback = feedback
        attempt = 1
        lastErrorKey = null
        status = StageStatus.PENDING
    }

    internal fun recoverInterrupted() {
        if (status == StageStatus.RUNNING) status = StageStatus.PENDING
    }

    fun cachedAssessment(): Assessment? {
        if (routing["source"] != "jev") return null
        val complexity = routing["complexity"]?.toDoubleOrNull() ?: return null
        val risk = routing["risk"]?.toDoubleOrNull() ?: return null
        return Assessment(complexity, risk)
    }

    fun recordAssessedRoute(assessment: Assessment, tier: ModelTier, attempt: Int) {
        routing = linkedMapOf(
            "source" to "jev",
            "complexity" to "%.2f".format(java.util.Locale.ROOT, assessment.complexity),
            "risk" to "%.2f".format(java.util.Locale.ROOT, assessment.risk),
            "model" to tier.label,
        )
        routingLog.add(
            "시도 %d: %s (JEV 난이도 %.2f/3, 위험 %.2f/2)".format(java.util.Locale.ROOT, attempt, tier.label, assessment.complexity, assessment.risk),
        )
    }

    fun recordFallbackRoute(tier: ModelTier, attempt: Int, reason: String) {
        routingLog.add("시도 $attempt: ${tier.label} (규칙 기반, $reason)")
    }
}
