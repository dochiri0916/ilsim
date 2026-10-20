package com.dochiri.ilsim.task.domain.vo

@JvmInline
value class TaskId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun of(value: String): TaskId = TaskId(value)
    }
}
