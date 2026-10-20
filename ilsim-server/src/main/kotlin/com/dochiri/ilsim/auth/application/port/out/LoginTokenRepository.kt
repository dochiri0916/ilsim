package com.dochiri.ilsim.auth.application.port.out

import com.dochiri.ilsim.auth.domain.LoginToken

interface LoginTokenRepository {
    fun save(token: LoginToken)

    fun find(series: String): LoginToken?

    fun delete(series: String)

    fun deleteByUsername(username: String)
}
