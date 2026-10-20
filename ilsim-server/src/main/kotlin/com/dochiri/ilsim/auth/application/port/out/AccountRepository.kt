package com.dochiri.ilsim.auth.application.port.out

import com.dochiri.ilsim.auth.domain.Account

interface AccountRepository {
    fun findByUsername(username: String): Account?

    fun updatePasswordHash(username: String, passwordHash: String): Boolean
}
