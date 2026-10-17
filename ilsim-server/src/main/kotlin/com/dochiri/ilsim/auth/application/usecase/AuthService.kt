package com.dochiri.ilsim.auth.application.usecase

import com.dochiri.ilsim.auth.application.port.`in`.AuthUseCase
import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.application.port.out.PasswordHasher
import com.dochiri.ilsim.auth.domain.AccountRules
import com.dochiri.ilsim.auth.domain.LoginThrottle
import com.dochiri.ilsim.auth.domain.exception.LoginLockedException
import java.time.Clock

/** 로그인 확인과 비밀번호 변경. 계정 생성은 관리자가 DB 에서 직접 한다. 저장소를 쓸 수 없으면 [Unavailable]. */
class AuthService(
    private val repository: AccountRepository,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val throttle: LoginThrottle = LoginThrottle(),
    private val accountThrottle: LoginThrottle = LoginThrottle(ACCOUNT_MAX_FAILURES, ACCOUNT_LOCK),
) : AuthUseCase {

    class Unavailable(cause: Throwable) : RuntimeException("계정 저장소(DB)를 사용할 수 없습니다", cause)

    // 계정이 없을 때도 같은 시간이 걸리게 해서 계정 존재 여부가 시간으로 드러나지 않게 한다.
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

    // 접속처(IP) 기준 제한은 한 곳에서 오는 추측을 막고, 계정 기준 제한은 여러 곳에서 나눠 오는 추측을 막는다.
    // 계정 기준은 실제 있는 계정에만 센다. 없는 아이디를 무한히 보내도 메모리가 늘지 않게 한다.
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
