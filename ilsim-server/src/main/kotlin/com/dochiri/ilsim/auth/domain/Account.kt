package com.dochiri.ilsim.auth.domain

import com.dochiri.ilsim.auth.domain.exception.InvalidAccountException

class Account(val username: String, val passwordHash: String)

object AccountRules {
    fun password(raw: String?): String {
        if (raw == null || raw.length !in 8..72) throw InvalidAccountException("비밀번호는 8~72자여야 합니다")
        return raw
    }
}
