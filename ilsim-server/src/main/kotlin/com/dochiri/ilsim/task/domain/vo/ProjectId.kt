package com.dochiri.ilsim.task.domain.vo

@JvmInline
value class ProjectId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun of(value: String): ProjectId = ProjectId(value)
    }
}
