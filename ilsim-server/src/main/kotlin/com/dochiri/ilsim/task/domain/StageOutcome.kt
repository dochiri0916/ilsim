package com.dochiri.ilsim.task.domain

/** Runner 가 보고한 한 단계의 실행 결과. */
data class StageOutcome(
    val passed: Boolean,
    val retryable: Boolean,
    val diff: String?,
    val error: String?,
    val artifacts: Map<String, String>?,
    val validation: Map<String, String>?,
    val branch: String?,
    /** 이번 실행에서 쓴 Claude 비용(USD)과 쓴 모델. 알 수 없으면 null. */
    val costUsd: Double? = null,
    val model: String? = null,
    /** AI 검토 호출(변경 검토, 위험 검토)에 쓴 비용과 모델. 작성 모델과 달라 따로 받는다. */
    val reviewCostUsd: Double? = null,
    val reviewModel: String? = null,
    /** 요구사항 단계에서 Claude 가 연 세션. 대화를 이어 갈 때 쓴다. */
    val sessionId: String? = null,
)
