package com.dochiri.ilsim.task.domain.vo

@JvmInline
value class RunCount private constructor(val value: Int) {
    init {
        if (value < 0) throw IllegalArgumentException("실행 횟수는 음수일 수 없습니다")
    }

    fun increment(): RunCount = RunCount(value + 1)

    companion object {
        val ZERO = RunCount(0)

        fun of(value: Int): RunCount = RunCount(value)
    }
}
