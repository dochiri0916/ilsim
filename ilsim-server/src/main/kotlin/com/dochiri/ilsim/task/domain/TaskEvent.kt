package com.dochiri.ilsim.task.domain

import java.time.Instant

data class TaskEvent(val at: Instant, val stage: String?, val type: String?, val data: String?)
