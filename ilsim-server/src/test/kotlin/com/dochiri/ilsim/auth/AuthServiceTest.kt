package com.dochiri.ilsim.auth

import com.dochiri.ilsim.auth.adapter.out.security.BcryptPasswordHasher
import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.application.usecase.AuthService
import com.dochiri.ilsim.auth.domain.Account
import com.dochiri.ilsim.auth.domain.LoginThrottle
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
        override fun findByUsername(username: String): Account? = accounts[username]
        override fun updatePasswordHash(username: String, passwordHash: String): Boolean {
            if (username !in accounts) return false
            accounts[username] = Account(username, passwordHash)
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
    private val hasher = BcryptPasswordHasher()
    private val auth = AuthService(repo, hasher, clock)

    private fun seed(name: String = "admin", password: String = "correct-horse") {
        repo.accounts[name] = Account(name, hasher.hash(password))
    }

    @Test
    fun `현재 비밀번호가 맞으면 바꾸고 새 비밀번호로만 로그인된다`() {
        seed()
        assertThat(auth.changePassword("ip", "admin", "correct-horse", "brand-new-pass")).isTrue()
        assertThat(repo.accounts.getValue("admin").passwordHash).doesNotContain("brand-new-pass")
        assertThat(auth.login("ip", "admin", "correct-horse")).isNull()
        assertThat(auth.login("ip", "admin", "brand-new-pass")).isEqualTo("admin")
    }

    @Test
    fun `현재 비밀번호가 틀리면 바꾸지 않고 실패로 센다`() {
        seed()
        assertThat(auth.changePassword("ip", "admin", "wrong", "brand-new-pass")).isFalse()
        assertThat(auth.login("ip", "admin", "correct-horse")).isEqualTo("admin")
        repeat(LoginThrottle.MAX_FAILURES) { auth.changePassword("ip2", "admin", "wrong", "brand-new-pass") }
        assertThatThrownBy { auth.changePassword("ip2", "admin", "correct-horse", "brand-new-pass") }
            .isInstanceOf(LoginLockedException::class.java)
    }

    @Test
    fun `새 비밀번호가 짧으면 거절한다`() {
        seed()
        assertThatThrownBy { auth.changePassword("ip", "admin", "correct-horse", "short") }.isInstanceOf(InvalidAccountException::class.java)
    }

    @Test
    fun `DB 에서 직접 넣은 해시로 로그인된다`() {
        repo.accounts["ops"] = Account("ops", hasher.hash("from-database"))
        assertThat(auth.login("ip", "ops", "from-database")).isEqualTo("ops")
    }

    @Test
    fun `로그인은 아이디와 비밀번호가 모두 맞아야 한다`() {
        seed()
        assertThat(auth.login("ip", "admin", "correct-horse")).isEqualTo("admin")
        assertThat(auth.login("ip", "admin", "wrong")).isNull()
        assertThat(auth.login("ip", "root", "correct-horse")).isNull()
    }

    @Test
    fun `연속 실패하면 잠기고 시간이 지나면 풀린다`() {
        seed()
        repeat(LoginThrottle.MAX_FAILURES) { auth.login("ip", "admin", "wrong") }
        assertThatThrownBy { auth.login("ip", "admin", "correct-horse") }.isInstanceOf(LoginLockedException::class.java)
        assertThat(auth.login("other-ip", "admin", "correct-horse")).isEqualTo("admin")
        clock.now = clock.now.plus(LoginThrottle.LOCK).plusSeconds(1)
        assertThat(auth.login("ip", "admin", "correct-horse")).isEqualTo("admin")
    }

    @Test
    fun `접속처를 바꿔 가며 한 계정을 추측해도 계정 기준으로 잠긴다`() {
        seed()
        repeat(AuthService.ACCOUNT_MAX_FAILURES) { auth.login("ip-$it", "admin", "wrong") }
        assertThatThrownBy { auth.login("fresh-ip", "admin", "correct-horse") }.isInstanceOf(LoginLockedException::class.java)
        clock.now = clock.now.plus(AuthService.ACCOUNT_LOCK).plusSeconds(1)
        assertThat(auth.login("fresh-ip", "admin", "correct-horse")).isEqualTo("admin")
    }

    @Test
    fun `없는 아이디로 틀려도 다른 계정은 잠기지 않는다`() {
        seed()
        repeat(AuthService.ACCOUNT_MAX_FAILURES) { auth.login("ip-$it", "ghost", "wrong") }
        assertThat(auth.login("fresh-ip", "admin", "correct-horse")).isEqualTo("admin")
    }
}
