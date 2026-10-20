package com.dochiri.ilsim.claude.domain

import com.dochiri.ilsim.claude.domain.exception.InvalidClaudeRequestException
import java.time.Instant

data class ClaudeAccount(
    val runnerId: String,
    val name: String,
    val loggedIn: Boolean,
    val email: String? = null,
    val plan: String? = null,
    val reportedAt: Instant? = null,
) {
    val usable: Boolean get() = loggedIn && !plan.isNullOrBlank()

    companion object {
        private val NAME = Regex("^[a-z0-9][a-z0-9-]{0,29}$")

        fun name(raw: String?): String {
            val name = raw?.trim()?.lowercase().orEmpty()
            if (!NAME.matches(name)) throw InvalidClaudeRequestException("계정 이름은 영문 소문자·숫자·하이픈 1~30자입니다")
            return name
        }
    }
}

enum class LoginStatus { REQUESTED, WAITING_CODE, CODE_SUBMITTED, DONE, FAILED }

data class LoginSession(
    val id: String,
    val runnerId: String,
    val name: String,
    val status: LoginStatus,
    val requestedAt: Instant,
    val url: String? = null,
    val code: String? = null,
    val error: String? = null,
    val email: String? = null,
    val plan: String? = null,
) {
    fun open(url: String): LoginSession {
        if (status != LoginStatus.REQUESTED) throw InvalidClaudeRequestException("로그인 URL 을 받을 상태가 아닙니다: $status")
        return copy(status = LoginStatus.WAITING_CODE, url = url)
    }

    fun submit(code: String?): LoginSession {
        if (status != LoginStatus.WAITING_CODE) throw InvalidClaudeRequestException("코드를 받을 상태가 아닙니다: $status")
        val value = code?.trim().orEmpty()
        if (value.isEmpty() || value.length > 2000 || value.any { it.isWhitespace() }) throw InvalidClaudeRequestException("코드 형식이 올바르지 않습니다")
        return copy(status = LoginStatus.CODE_SUBMITTED, code = value)
    }

    fun takeCode(): LoginSession = copy(code = null)

    fun finish(loggedIn: Boolean, email: String?, plan: String?, error: String?): LoginSession =
        copy(status = if (loggedIn) LoginStatus.DONE else LoginStatus.FAILED, code = null, email = email, plan = plan, error = error)

    val active: Boolean get() = status in setOf(LoginStatus.REQUESTED, LoginStatus.WAITING_CODE, LoginStatus.CODE_SUBMITTED)
}
