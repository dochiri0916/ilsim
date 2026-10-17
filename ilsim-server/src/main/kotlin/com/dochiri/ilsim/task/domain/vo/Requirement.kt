package com.dochiri.ilsim.task.domain.vo

import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException

@JvmInline
value class Requirement private constructor(val value: String) {
    init {
        if (value.isBlank()) throw InvalidTaskRequestException("요구사항이 비어 있습니다")
    }

    companion object {
        fun of(value: String?): Requirement = Requirement(value.orEmpty())
    }
}
