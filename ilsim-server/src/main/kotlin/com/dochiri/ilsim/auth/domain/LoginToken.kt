package com.dochiri.ilsim.auth.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

data class LoginToken(val series: String, val secretHash: String, val username: String, val createdAt: Instant, val expiresAt: Instant) {

    fun matches(secret: String, now: Instant): Boolean =
        now.isBefore(expiresAt) && MessageDigest.isEqual(hash(secret).toByteArray(), secretHash.toByteArray())

    companion object {
        val LIFETIME: Duration = Duration.ofDays(30)
        private val random = SecureRandom()

        fun issue(username: String, now: Instant): Pair<LoginToken, String> {
            val series = randomText(12)
            val secret = randomText(32)
            return LoginToken(series, hash(secret), username, now, now.plus(LIFETIME)) to "$series:$secret"
        }

        fun parse(cookie: String?): Pair<String, String>? {
            val parts = cookie?.split(':')?.takeIf { it.size == 2 && it.all { p -> p.length in 8..64 } } ?: return null
            return parts[0] to parts[1]
        }

        private fun randomText(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        private fun hash(secret: String): String =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
