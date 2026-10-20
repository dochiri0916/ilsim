package com.dochiri.ilsim.auth.application.usecase

import com.dochiri.ilsim.auth.application.port.`in`.RememberMeUseCase
import com.dochiri.ilsim.auth.application.port.out.LoginTokenRepository
import com.dochiri.ilsim.auth.domain.LoginToken
import java.time.Clock

class RememberMeService(private val tokens: LoginTokenRepository, private val clock: Clock) : RememberMeUseCase {

    override fun issue(username: String): String {
        val (token, cookie) = LoginToken.issue(username, clock.instant())
        tokens.save(token)
        return cookie
    }

    override fun resolve(cookie: String?): String? {
        val (series, secret) = LoginToken.parse(cookie) ?: return null
        val token = tokens.find(series) ?: return null
        if (token.matches(secret, clock.instant())) return token.username
        tokens.delete(series)
        return null
    }

    override fun revoke(cookie: String?) {
        LoginToken.parse(cookie)?.let { tokens.delete(it.first) }
    }

    override fun revokeAll(username: String) = tokens.deleteByUsername(username)
}
