package com.dochiri.ilsim.task.application.result

/**
 * 삭제한 작업의 worktree 를 Runner 가 지우도록 넘기는 일감. [keepBranch] 면 브랜치(작업 결과)는 남긴다.
 * 끝난 작업의 브랜치는 결과물이라 남기고, 끝나지 않은 작업의 브랜치만 지운다.
 */
data class Cleanup(val taskId: String, val projectId: String, val feature: String, val branch: String?, val keepBranch: Boolean)
