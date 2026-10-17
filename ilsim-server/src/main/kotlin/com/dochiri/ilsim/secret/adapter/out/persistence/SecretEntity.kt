package com.dochiri.ilsim.secret.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "secrets")
final class SecretEntity private constructor(
    name: String,
    cipherText: String,
    updatedAt: Instant,
) {

    @field:Id
    @field:Column(name = "name", nullable = false, length = 100)
    final var name: String = name
        private set

    @field:Column(name = "cipher_text", nullable = false, columnDefinition = "TEXT")
    final var cipherText: String = cipherText
        private set

    @field:Column(name = "updated_at", nullable = false)
    final var updatedAt: Instant = updatedAt
        private set

    fun update(cipherText: String, updatedAt: Instant) {
        this.cipherText = cipherText
        this.updatedAt = updatedAt
    }

    companion object {
        fun of(name: String, cipherText: String, updatedAt: Instant): SecretEntity = SecretEntity(name, cipherText, updatedAt)
    }
}
