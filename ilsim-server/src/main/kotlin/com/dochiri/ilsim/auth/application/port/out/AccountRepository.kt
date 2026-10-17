package com.dochiri.ilsim.auth.application.port.out

import com.dochiri.ilsim.auth.domain.Account

interface AccountRepository {
    fun isEmpty(): Boolean

    fun findByUsername(username: String): Account?

    fun usernames(): List<String>

    /** 계정이 하나도 없을 때만 만든다. 이미 있으면 false. */
    fun createFirst(account: Account): Boolean

    /** 같은 아이디가 이미 있으면 false. */
    fun create(account: Account): Boolean
}
