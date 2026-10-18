package com.dochiri.ilsim.auth.application.port.out

import com.dochiri.ilsim.auth.domain.LoginToken

/** 자동 로그인 토큰. */
interface LoginTokenRepository {
    fun save(token: LoginToken)

    fun find(series: String): LoginToken?

    fun delete(series: String)

    fun deleteByUsername(username: String)
}
