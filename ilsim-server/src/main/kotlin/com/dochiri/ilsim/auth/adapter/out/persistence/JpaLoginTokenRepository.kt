package com.dochiri.ilsim.auth.adapter.out.persistence

import com.dochiri.ilsim.auth.application.port.out.LoginTokenRepository
import com.dochiri.ilsim.auth.domain.LoginToken
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.jdbc.core.JdbcTemplate

class JpaLoginTokenRepository(
    private val jdbc: JdbcTemplate,
    private val tokens: LoginTokenJpaRepository,
) : LoginTokenRepository {

    private val ready = AtomicBoolean(false)

    private fun ensureTable() {
        if (ready.get()) return
        jdbc.execute(
            """
            CREATE TABLE IF NOT EXISTS login_tokens (
              series      VARCHAR(64)  NOT NULL PRIMARY KEY,
              secret_hash VARCHAR(64)  NOT NULL,
              username    VARCHAR(50)  NOT NULL,
              created_at  TIMESTAMP(3) NOT NULL,
              expires_at  TIMESTAMP(3) NOT NULL,
              INDEX idx_login_tokens_username (username)
            ) CHARACTER SET utf8mb4""",
        )
        ready.set(true)
    }

    override fun save(token: LoginToken) {
        ensureTable()
        tokens.deleteExpired(Instant.now())
        tokens.save(LoginTokenEntity.of(token.series, token.secretHash, token.username, token.createdAt, token.expiresAt))
    }

    override fun find(series: String): LoginToken? {
        ensureTable()
        return tokens.findById(series).map { LoginToken(it.series, it.secretHash, it.username, it.createdAt, it.expiresAt) }.orElse(null)
    }

    override fun delete(series: String) {
        ensureTable()
        tokens.deleteById(series)
    }

    override fun deleteByUsername(username: String) {
        ensureTable()
        tokens.deleteByUsername(username)
    }
}
