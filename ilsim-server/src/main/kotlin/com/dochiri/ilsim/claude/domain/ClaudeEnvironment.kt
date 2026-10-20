package com.dochiri.ilsim.claude.domain

import com.dochiri.ilsim.claude.domain.exception.InvalidClaudeRequestException
import java.security.MessageDigest
import java.time.Instant

data class ClaudeEnvironment(val claudeMd: String = "", val settingsJson: String = "{}", val updatedAt: Instant? = null) {
    val version: String get() = MessageDigest.getInstance("SHA-256").digest("$claudeMd\u0000$settingsJson".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        const val MAX_CHARS = 200_000

        fun of(claudeMd: String?, settingsJson: String?, isJsonObject: (String) -> Boolean, now: Instant): ClaudeEnvironment {
            val md = claudeMd.orEmpty()
            val settings = settingsJson?.trim()?.ifEmpty { null } ?: "{}"
            if (md.length > MAX_CHARS || settings.length > MAX_CHARS) throw InvalidClaudeRequestException("내용이 너무 깁니다(최대 ${MAX_CHARS}자)")
            if (!isJsonObject(settings)) throw InvalidClaudeRequestException("settings.json 은 JSON 객체여야 합니다")
            return ClaudeEnvironment(md, settings, now)
        }
    }
}
