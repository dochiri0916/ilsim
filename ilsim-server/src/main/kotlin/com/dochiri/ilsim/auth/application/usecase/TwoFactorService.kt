package com.dochiri.ilsim.auth.application.usecase

import com.dochiri.ilsim.auth.application.port.`in`.TwoFactorUseCase
import com.dochiri.ilsim.auth.application.port.out.TotpSecretStore
import com.dochiri.ilsim.auth.domain.LoginThrottle
import com.dochiri.ilsim.auth.domain.Totp
import com.dochiri.ilsim.auth.domain.exception.LoginLockedException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

class TwoFactorService(
    private val secrets: TotpSecretStore,
    private val clock: Clock,
    private val issuer: String = "ilsim",
    private val throttle: LoginThrottle = LoginThrottle(MAX_FAILURES),
) : TwoFactorUseCase {

    private val usedStep = ConcurrentHashMap<String, Long>()

    override fun enabled(username: String): Boolean = secrets.find(username) != null

    override fun setup(username: String): TwoFactorUseCase.Setup {
        val secret = Totp.newSecret()
        return TwoFactorUseCase.Setup(secret, Totp.uri(issuer, username, secret))
    }

    override fun enable(clientKey: String, username: String, secret: String, code: String?): Boolean {
        if (!check(clientKey, username, secret, code)) return false
        secrets.save(username, secret)
        return true
    }

    override fun disable(clientKey: String, username: String, code: String?): Boolean {
        val secret = secrets.find(username) ?: return true
        if (!check(clientKey, username, secret, code)) return false
        secrets.delete(username)
        return true
    }

    override fun verify(clientKey: String, username: String, code: String?): Boolean {
        val secret = secrets.find(username) ?: return false
        return check(clientKey, username, secret, code)
    }

    private fun check(clientKey: String, username: String, secret: String, code: String?): Boolean {
        val now = clock.instant()
        if (throttle.isLocked(clientKey, now) || throttle.isLocked(ACCOUNT + username, now)) throw LoginLockedException()
        val step = Totp.verify(secret, code, now, usedStep[username] ?: Long.MIN_VALUE)
        if (step == null) {
            throttle.recordFailure(clientKey, now)
            throttle.recordFailure(ACCOUNT + username, now)
            return false
        }
        usedStep[username] = step
        throttle.recordSuccess(clientKey)
        throttle.recordSuccess(ACCOUNT + username)
        return true
    }

    private companion object {
        const val MAX_FAILURES = 5
        const val ACCOUNT = "account:"
    }
}
