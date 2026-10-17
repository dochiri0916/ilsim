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
)
