package com.dochiri.ilsim.auth.domain

import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Totp {
    private const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun newSecret(random: SecureRandom = SecureRandom()): String = base32(ByteArray(20).also(random::nextBytes))

    fun uri(issuer: String, account: String, secret: String): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    fun step(now: Instant): Long = now.epochSecond / STEP_SECONDS

    fun code(secret: String, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(decode(secret), "HmacSHA1")) }
        val hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val bin = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(DIGITS, '0')
    }

    fun verify(secret: String, code: String?, now: Instant, after: Long = Long.MIN_VALUE): Long? {
        val input = code?.filter { it.isDigit() }?.takeIf { it.length == DIGITS } ?: return null
        val current = step(now)
        return (current - 1..current + 1).firstOrNull { it > after && java.security.MessageDigest.isEqual(code(secret, it).toByteArray(), input.toByteArray()) }
    }

    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun decode(secret: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in secret.uppercase().filter { it != '=' && !it.isWhitespace() }) {
            val v = ALPHABET.indexOf(c).takeIf { it >= 0 } ?: throw IllegalArgumentException("Base32 형식이 아닙니다")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
