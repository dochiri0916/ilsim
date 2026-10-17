package com.dochiri.ilsim.auth.application.usecase

import com.dochiri.ilsim.auth.application.port.`in`.AuthUseCase
import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.application.port.out.PasswordHasher
import com.dochiri.ilsim.auth.domain.Account
import com.dochiri.ilsim.auth.domain.AccountRules
import com.dochiri.ilsim.auth.domain.LoginThrottle
import com.dochiri.ilsim.auth.domain.exception.DuplicateUsernameException
import com.dochiri.ilsim.auth.domain.exception.LoginLockedException
import com.dochiri.ilsim.auth.domain.exception.SetupAlreadyDoneException
import java.time.Clock

/** 계정 생성과 로그인 확인. 저장소를 쓸 수 없으면 [Unavailable]. */
class AuthService(
    private val repository: AccountRepository,
    private val hasher: PasswordHasher,
    private val clock: Clock,
    private val throttle: LoginThrottle = LoginThrottle(),
) : AuthUseCase {

    class Unavailable(cause: Throwable) : RuntimeException("계정 저장소(DB)를 사용할 수 없습니다", cause)

    // 계정이 없을 때도 같은 시간이 걸리게 해서 계정 존재 여부가 시간으로 드러나지 않게 한다.
    private val dummyHash: String = hasher.hash("dummy-password")

    override fun needsSetup(): Boolean = guarded { repository.isEmpty() }

    override fun setup(username: String?, password: String?): String {
        val account = newAccount(username, password)
        if (!guarded { repository.createFirst(account) }) throw SetupAlreadyDoneException()
        return account.username
    }

    override fun createAccount(username: String?, password: String?): String {
        val account = newAccount(username, password)
        if (!guarded { repository.create(account) }) throw DuplicateUsernameException()
        return account.username
    }

    override fun usernames(): List<String> = guarded { repository.usernames() }

    override fun login(clientKey: String, username: String?, password: String?): String? {
        val now = clock.instant()
        if (throttle.isLocked(clientKey, now)) throw LoginLockedException()
        val account = username?.trim()?.takeIf { it.isNotEmpty() }?.let { name -> guarded { repository.findByUsername(name) } }
        val matches = hasher.matches(password.orEmpty(), account?.passwordHash ?: dummyHash)
        if (matches && account != null) {
            throttle.recordSuccess(clientKey)
            return account.username
        }
        throttle.recordFailure(clientKey, now)
        return null
    }

    private fun newAccount(username: String?, password: String?): Account {
        val name = AccountRules.username(username)
        return Account(name, hasher.hash(AccountRules.password(password)))
    }

    private fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (e: RuntimeException) {
        throw Unavailable(e)
    }
}
