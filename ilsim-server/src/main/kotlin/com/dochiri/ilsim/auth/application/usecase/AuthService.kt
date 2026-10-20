package com.dochiri.ilsim.auth.application.usecase

import com.dochiri.ilsim.auth.application.port.`in`.AuthUseCase
import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.application.port.out.PasswordHasher
import com.dochiri.ilsim.auth.domain.AccountRules
import com.dochiri.ilsim.auth.domain.LoginThrottle
import com.dochiri.ilsim.auth.domain.exception.LoginLockedException
import java.time.Clock

class AuthService(
    private val repository: AccountRepository,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val throttle: LoginThrottle = LoginThrottle(),
    private val accountThrottle: LoginThrottle = LoginThrottle(ACCOUNT_MAX_FAILURES, ACCOUNT_LOCK),
) : AuthUseCase {

    class Unavailable(cause: Throwable) : RuntimeException("계정 저장소(DB)를 사용할 수 없습니다", cause)

    private val dummyHash: String = hasher.hash("dummy-password")

    override fun login(clientKey: String, username: String?, password: String?): String? {
        val now = clock.instant()
        if (throttle.isLocked(clientKey, now)) throw LoginLockedException()
        val account = username?.trim()?.takeIf { it.isNotEmpty() }?.let { name -> guarded { repository.findByUsername(name) } }
        if (account != null && accountThrottle.isLocked(account.username, now)) throw LoginLockedException()
        val matches = hasher.matches(password.orEmpty(), account?.passwordHash ?: dummyHash)
        if (matches && account != null) {
            succeeded(clientKey, account.username)
            return account.username
        }
        failed(clientKey, account?.username, now)
        return null
    }

    override fun changePassword(clientKey: String, username: String, currentPassword: String?, newPassword: String?): Boolean {
        val now = clock.instant()
        if (throttle.isLocked(clientKey, now) || accountThrottle.isLocked(username, now)) throw LoginLockedException()
        val newHash = hasher.hash(AccountRules.password(newPassword))
        val account = guarded { repository.findByUsername(username) }
        val matches = hasher.matches(currentPassword.orEmpty(), account?.passwordHash ?: dummyHash)
        if (!matches || account == null) {
            failed(clientKey, account?.username, now)
            return false
        }
        succeeded(clientKey, account.username)
        return guarded { repository.updatePasswordHash(username, newHash) }
    }

    private fun failed(clientKey: String, accountName: String?, now: java.time.Instant) {
        throttle.recordFailure(clientKey, now)
        accountName?.let { accountThrottle.recordFailure(it, now) }
    }

    private fun succeeded(clientKey: String, accountName: String) {
        throttle.recordSuccess(clientKey)
        accountThrottle.recordSuccess(accountName)
    }

    companion object {
        const val ACCOUNT_MAX_FAILURES = 20
        val ACCOUNT_LOCK: java.time.Duration = java.time.Duration.ofMinutes(15)
    }

    private fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (e: RuntimeException) {
        throw Unavailable(e)
    }
}
