package com.dochiri.ilsim.auth.adapter.out.persistence

import com.dochiri.ilsim.auth.application.port.out.AccountRepository
import com.dochiri.ilsim.auth.domain.Account
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

/** MySQL `accounts` 테이블. 테이블은 처음 쓸 때 만든다. */
class JpaAccountRepository(
    private val jdbc: JdbcTemplate,
    private val accounts: AccountJpaRepository,
) : AccountRepository {

    private val ready = AtomicBoolean(false)

    private fun ensureTable() {
        if (ready.get()) return
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS accounts (
              id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
              version       BIGINT       NOT NULL DEFAULT 0,
              username      VARCHAR(50)  NOT NULL,
              password_hash VARCHAR(100) NOT NULL,
              created_at    TIMESTAMP(3) NOT NULL,
              CONSTRAINT uk_accounts_username UNIQUE (username)
            ) CHARACTER SET utf8mb4""",
        )
        ready.set(true)
    }

    override fun isEmpty(): Boolean {
        ensureTable()
        return accounts.count() == 0L
    }

    override fun findByUsername(username: String): Account? {
        ensureTable()
        return accounts.findByUsername(username)?.let { Account(it.username, it.passwordHash) }
    }

    override fun usernames(): List<String> {
        ensureTable()
        return accounts.findAllByOrderByIdAsc().map { it.username }
    }

    override fun createFirst(account: Account): Boolean {
        ensureTable()
        return accounts.insertFirst(account.username, account.passwordHash, Instant.now()) == 1
    }

    override fun create(account: Account): Boolean {
        ensureTable()
        return try {
            accounts.saveAndFlush(AccountEntity.of(account.username, account.passwordHash, Instant.now()))
            true
        } catch (e: DataIntegrityViolationException) {
            false
        }
    }
}
