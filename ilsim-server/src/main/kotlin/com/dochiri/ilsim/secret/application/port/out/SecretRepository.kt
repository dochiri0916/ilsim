package com.dochiri.ilsim.secret.application.port.out

import java.time.Instant

/** 암호문만 저장한다. 평문과 마스터 키는 이 계층에 오지 않는다. */
interface SecretRepository {
    fun find(name: String): StoredSecret?

    fun save(name: String, cipherText: String, updatedAt: Instant)

    fun delete(name: String)
}

data class StoredSecret(val cipherText: String, val updatedAt: Instant)
