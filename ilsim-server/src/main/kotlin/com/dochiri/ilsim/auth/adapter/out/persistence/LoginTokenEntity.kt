package com.dochiri.ilsim.auth.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "login_tokens")
final class LoginTokenEntity private constructor(
    series: String,
    secretHash: String,
    username: String,
    createdAt: Instant,
    expiresAt: Instant,
) {

    @field:Id
    @field:Column(name = "series", nullable = false, length = 64)
    final var series: String = series
        private set

    @field:Column(name = "secret_hash", nullable = false, length = 64)
    final var secretHash: String = secretHash
        private set

    @field:Column(name = "username", nullable = false, length = 50)
    final var username: String = username
        private set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    final var createdAt: Instant = createdAt
        private set

    @field:Column(name = "expires_at", nullable = false)
    final var expiresAt: Instant = expiresAt
        private set

    companion object {
        fun of(series: String, secretHash: String, username: String, createdAt: Instant, expiresAt: Instant): LoginTokenEntity =
            LoginTokenEntity(series, secretHash, username, createdAt, expiresAt)
    }
}
