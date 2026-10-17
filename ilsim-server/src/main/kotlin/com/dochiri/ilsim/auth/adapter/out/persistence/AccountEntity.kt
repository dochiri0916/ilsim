package com.dochiri.ilsim.auth.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "accounts", uniqueConstraints = [UniqueConstraint(name = "uk_accounts_username", columnNames = ["username"])])
final class AccountEntity private constructor(
    username: String,
    passwordHash: String,
    createdAt: Instant,
) {

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @field:Version
    @field:Column(name = "version", nullable = false)
    final var version: Long? = null
        private set

    @field:Column(name = "username", nullable = false, length = 50)
    final var username: String = username
        private set

    @field:Column(name = "password_hash", nullable = false, length = 100)
    final var passwordHash: String = passwordHash
        private set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    final var createdAt: Instant = createdAt
        private set

    companion object {
        fun of(username: String, passwordHash: String, createdAt: Instant): AccountEntity =
            AccountEntity(username, passwordHash, createdAt)
    }
}
