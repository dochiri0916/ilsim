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
)
