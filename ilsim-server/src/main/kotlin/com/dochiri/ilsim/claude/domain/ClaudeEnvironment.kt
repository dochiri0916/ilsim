package com.dochiri.ilsim.claude.domain

import com.dochiri.ilsim.claude.domain.exception.InvalidClaudeRequestException
import java.security.MessageDigest
import java.time.Instant

/**
 * 일심이 Claude 를 실행하는 모든 곳(모든 PC·계정)에 똑같이 까는 사용자 설정. Runner 가 계정 설정 디렉터리에
 * `CLAUDE.md` 와 `settings.json` 으로 써서, PC 마다 `~/.claude` 가 달라도 같은 조건으로 실행한다.
 */
data class ClaudeEnvironment(val claudeMd: String = "", val settingsJson: String = "{}", val updatedAt: Instant? = null) {
    /** Runner 가 바뀐 경우에만 다시 쓰도록 내용으로 만든 버전. */
    val version: String get() = MessageDigest.getInstance("SHA-256").digest("$claudeMd\u0000$settingsJson".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        const val MAX_CHARS = 200_000

        /** settings.json 은 JSON 객체여야 한다. 형식 검사는 바깥에서 받은 [isJsonObject] 로 한다(도메인은 JSON 라이브러리를 모른다). */
        fun of(claudeMd: String?, settingsJson: String?, isJsonObject: (String) -> Boolean, now: Instant): ClaudeEnvironment {
            val md = claudeMd.orEmpty()
            val settings = settingsJson?.trim()?.ifEmpty { null } ?: "{}"
            if (md.length > MAX_CHARS || settings.length > MAX_CHARS) throw InvalidClaudeRequestException("내용이 너무 깁니다(최대 ${MAX_CHARS}자)")
            if (!isJsonObject(settings)) throw InvalidClaudeRequestException("settings.json 은 JSON 객체여야 합니다")
            return ClaudeEnvironment(md, settings, now)
        }
    }
}
