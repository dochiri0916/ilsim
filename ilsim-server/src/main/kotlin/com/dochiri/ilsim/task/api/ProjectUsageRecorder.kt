package com.dochiri.ilsim.task.api

interface ProjectUsageRecorder {
    fun recordProjectUsage(projectId: String, usage: List<ProjectUsageReport>)
}

data class ProjectUsageReport(
    val id: String, val model: String, val purpose: String, val effort: String = "medium", val costUsd: Double? = null,
    val inputTokens: Long = 0, val outputTokens: Long = 0, val cacheReadTokens: Long = 0, val cacheWriteTokens: Long = 0,
    val status: String = "UNKNOWN", val questions: Int = 0, val toolCalls: Int = 0,
    val executionId: String? = null,
    val review: com.dochiri.ilsim.task.domain.ReviewEvidence? = null,
)
