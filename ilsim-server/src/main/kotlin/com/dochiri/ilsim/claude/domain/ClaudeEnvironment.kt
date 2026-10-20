package com.dochiri.ilsim.claude.domain

import com.dochiri.ilsim.claude.domain.exception.InvalidClaudeRequestException
import java.security.MessageDigest
import java.time.Instant

data class ClaudeEnvironment(
    val claudeMd: String = "",
    val settingsJson: String = "{}",
    val updatedAt: Instant? = null,
    val skills: List<ClaudeSkill> = emptyList(),
) {
    val version: String get() = MessageDigest.getInstance("SHA-256").digest(fingerprint().toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    fun withSkills(skills: List<ClaudeSkill>): ClaudeEnvironment = copy(skills = skills.sortedBy { it.name })

    private fun fingerprint(): String = buildString {
        append(claudeMd).append('\u0000').append(settingsJson)
        skills.forEach { skill ->
            append('\u0001').append(skill.name)
            skill.files.forEach { (path, text) -> append('\u0000').append(path).append('\u0000').append(text) }
        }
    }

    companion object {
        const val MAX_CHARS = 200_000

        fun of(claudeMd: String?, settingsJson: String?, isJsonObject: (String) -> Boolean, now: Instant): ClaudeEnvironment {
            val md = claudeMd.orEmpty()
            val settings = settingsJson?.trim()?.ifEmpty { null } ?: "{}"
            if (md.length > MAX_CHARS || settings.length > MAX_CHARS) throw InvalidClaudeRequestException("내용이 너무 깁니다(최대 ${MAX_CHARS}자)")
            if (!isJsonObject(settings)) throw InvalidClaudeRequestException("settings.json 은 JSON 객체여야 합니다")
            PortablePaths.require("CLAUDE.md", md)
            PortablePaths.require("settings.json", settings)
            return ClaudeEnvironment(md, settings, now)
        }
    }
}
