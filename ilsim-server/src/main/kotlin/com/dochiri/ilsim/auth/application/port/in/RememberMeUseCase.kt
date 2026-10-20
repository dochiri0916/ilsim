package com.dochiri.ilsim.auth.application.port.`in`

interface RememberMeUseCase {
    fun issue(username: String): String

    fun resolve(cookie: String?): String?

    fun revoke(cookie: String?)

    fun revokeAll(username: String)
}
