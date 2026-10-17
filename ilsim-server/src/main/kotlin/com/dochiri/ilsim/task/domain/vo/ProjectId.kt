package com.dochiri.ilsim.task.domain.vo

/** 식별자 값 객체. 저장과 전송 형식(문자열)을 그대로 감싼다. */
@JvmInline
value class ProjectId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun of(value: String): ProjectId = ProjectId(value)
    }
}
