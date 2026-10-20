package com.dochiri.ilsim.task.application.result

data class Assignment(
    val taskId: String,
    val projectId: String,
    val stage: String,
    val attempt: Int,
    val feature: String,
    val model: String,
    val effort: String,
    val prompt: String,
    val resumeSessionId: String? = null,
    val executionId: String? = null,
    val baseBranch: String? = null,
    val reviewPolicy: com.dochiri.ilsim.task.domain.ReviewPolicySnapshot = com.dochiri.ilsim.task.domain.ReviewPolicySnapshot(),
)
