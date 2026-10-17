package com.dochiri.ilsim.auth

import com.dochiri.ilsim.auth.adapter.out.security.BcryptPasswordHasher
import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.application.usecase.AuthService
import com.dochiri.ilsim.auth.domain.Account
import com.dochiri.ilsim.auth.domain.LoginThrottle
import com.dochiri.ilsim.auth.domain.exception.DuplicateUsernameException
import com.dochiri.ilsim.auth.domain.exception.SetupAlreadyDoneException
import com.dochiri.ilsim.auth.domain.exception.InvalidAccountException
import com.dochiri.ilsim.auth.domain.exception.LoginLockedException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuthServiceTest {

    private class Memory : AccountRepository {
        val accounts = LinkedHashMap<String, Account>()
        override fun isEmpty(): Boolean = accounts.isEmpty()
        override fun findByUsername(username: String): Account? = accounts[username]
        override fun usernames(): List<String> = accounts.keys.toList()
        override fun createFirst(account: Account): Boolean = isEmpty() && create(account)
        override fun create(account: Account): Boolean {
            if (account.username in accounts) return false
            accounts[account.username] = account
            return true
        }
    }

    private class Ticking : Clock() {
        var now: Instant = Instant.parse("2026-01-01T00:00:00Z")
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val repo = Memory()
    private val clock = Ticking()
    private val auth = AuthService(repo, BcryptPasswordHasher(), clock)

    @Test
    fun `처음에는 설정이 필요하고 한 번만 만들 수 있다`() {
        assertThat(auth.needsSetup()).isTrue()
        auth.setup("admin", "correct-horse")
        assertThat(auth.needsSetup()).isFalse()
        assertThatThrownBy { auth.setup("other", "another-pass") }.isInstanceOf(SetupAlreadyDoneException::class.java)
    }

    @Test
    fun `로그인한 뒤에는 계정을 더 만들 수 있고 같은 아이디는 거절한다`() {
        auth.setup("admin", "correct-horse")
        assertThat(auth.createAccount("second", "another-pass")).isEqualTo("second")
        assertThat(auth.usernames()).containsExactly("admin", "second")
        assertThat(auth.login("ip", "second", "another-pass")).isEqualTo("second")
        assertThatThrownBy { auth.createAccount("second", "third-pass-1") }.isInstanceOf(DuplicateUsernameException::class.java)
    }

    @Test
    fun `비밀번호는 해시로만 저장한다`() {
        auth.setup("admin", "correct-horse")
        assertThat(repo.accounts.getValue("admin").passwordHash).doesNotContain("correct-horse")
    }

    @Test
    fun `짧은 아이디와 비밀번호는 거절한다`() {
        assertThatThrownBy { auth.setup("ab", "correct-horse") }.isInstanceOf(InvalidAccountException::class.java)
        assertThatThrownBy { auth.setup("admin", "short") }.isInstanceOf(InvalidAccountException::class.java)
    }

    @Test
    fun `로그인은 아이디와 비밀번호가 모두 맞아야 한다`() {
        auth.setup("admin", "correct-horse")
        assertThat(auth.login("ip", "admin", "correct-horse")).isEqualTo("admin")
        assertThat(auth.login("ip", "admin", "wrong")).isNull()
        assertThat(auth.login("ip", "root", "correct-horse")).isNull()
    }

    @Test
    fun `연속 실패하면 잠기고 시간이 지나면 풀린다`() {
        auth.setup("admin", "correct-horse")
        repeat(LoginThrottle.MAX_FAILURES) { auth.login("ip", "admin", "wrong") }
        assertThatThrownBy { auth.login("ip", "admin", "correct-horse") }.isInstanceOf(LoginLockedException::class.java)
        assertThat(auth.login("other-ip", "admin", "correct-horse")).isEqualTo("admin")
        clock.now = clock.now.plus(LoginThrottle.LOCK).plusSeconds(1)
        assertThat(auth.login("ip", "admin", "correct-horse")).isEqualTo("admin")
    }
}
