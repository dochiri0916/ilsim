package com.dochiri.ilsim.auth.domain

import com.dochiri.ilsim.auth.domain.exception.InvalidAccountException

/** 로그인 계정. 비밀번호는 해시만 보관한다. */
class Account(val username: String, val passwordHash: String)

/** 새 계정이 지켜야 하는 형식. */
object AccountRules {
    fun username(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.length !in 3..50) throw InvalidAccountException("아이디는 3~50자여야 합니다")
        return name
    }

    fun password(raw: String?): String {
        if (raw == null || raw.length !in 8..72) throw InvalidAccountException("비밀번호는 8~72자여야 합니다")
        return raw
    }
}
