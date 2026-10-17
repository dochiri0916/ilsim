package com.dochiri.ilsim.task.domain.vo

@JvmInline
value class Branch private constructor(val value: String) {
    init {
        if (value.isBlank()) throw IllegalArgumentException("브랜치 이름이 비어 있습니다")
    }

    companion object {
        fun of(value: String): Branch = Branch(value)
    }
}
