package com.dochiri.ilsim.task.domain.vo

/** Runner 에 배정된 횟수. 사람이 개입하면 0으로 돌아간다. */
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
