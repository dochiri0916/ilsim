package com.dochiri.ilsim.task.domain.vo

import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException

@JvmInline
value class Feature private constructor(val value: String) {
    init {
        if (!PATTERN.matches(value)) throw InvalidTaskRequestException("feature는 소문자·숫자·하이픈만 사용할 수 있습니다")
    }

    companion object {
        private val PATTERN = Regex("[a-z0-9][a-z0-9-]{0,40}")

        fun of(value: String): Feature = Feature(value)

        fun defaultFor(taskId: TaskId): Feature = Feature("feat-${taskId.value.take(8)}")
    }
}
