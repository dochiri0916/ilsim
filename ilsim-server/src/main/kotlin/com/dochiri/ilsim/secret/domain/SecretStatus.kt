package com.dochiri.ilsim.secret.domain

import java.time.Instant

/** last4 는 확인용으로만 쓴다. 값 자체는 어떤 응답에도 담지 않는다. */
data class SecretStatus(val configured: Boolean, val last4: String?, val updatedAt: Instant?)

object SecretValue {
    private const val MAX_LENGTH = 512

    fun validate(plain: String?): String {
        if (plain.isNullOrBlank() || plain.length > MAX_LENGTH || plain.any { it.isWhitespace() }) {
            throw com.dochiri.ilsim.secret.domain.exception.InvalidSecretException("키 형식이 올바르지 않습니다")
        }
        return plain
    }

    fun last4(plain: String): String = plain.takeLast(4)
}
