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
    /** 요구사항 대화를 이어 갈 Claude 세션. 없으면 새 세션으로 시작한다. */
    val resumeSessionId: String? = null,
    val executionId: String? = null,
    /** 이어서 지시한 작업이면 worktree 를 만들 기준 브랜치. 없으면 HEAD. */
    val baseBranch: String? = null,
    val reviewPolicy: com.dochiri.ilsim.task.domain.ReviewPolicySnapshot = com.dochiri.ilsim.task.domain.ReviewPolicySnapshot(),
)
