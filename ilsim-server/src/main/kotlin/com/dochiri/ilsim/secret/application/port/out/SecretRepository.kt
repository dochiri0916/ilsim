package com.dochiri.ilsim.secret.application.port.out

import java.time.Instant

interface SecretRepository {
    fun find(name: String): StoredSecret?

    fun save(name: String, cipherText: String, updatedAt: Instant)

    fun delete(name: String)
}

data class StoredSecret(val cipherText: String, val updatedAt: Instant)
