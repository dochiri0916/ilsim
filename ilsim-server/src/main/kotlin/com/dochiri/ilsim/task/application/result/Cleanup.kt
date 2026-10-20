package com.dochiri.ilsim.task.application.result

data class Cleanup(val taskId: String, val projectId: String, val feature: String, val branch: String?, val keepBranch: Boolean)
