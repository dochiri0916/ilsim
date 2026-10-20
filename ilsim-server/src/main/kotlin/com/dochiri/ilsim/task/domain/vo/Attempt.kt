package com.dochiri.ilsim.task.domain.vo

@JvmInline
value class Attempt private constructor(val value: Int) {
    init {
        if (value < 1) throw IllegalArgumentException("시도 번호는 1 이상이어야 합니다")
    }

    fun next(): Attempt = Attempt(value + 1)

    companion object {
        val FIRST = Attempt(1)

        fun of(value: Int): Attempt = Attempt(value)
    }
}
