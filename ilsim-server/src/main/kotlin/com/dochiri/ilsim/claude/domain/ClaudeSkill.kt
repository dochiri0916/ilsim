package com.dochiri.ilsim.claude.domain

import com.dochiri.ilsim.claude.domain.exception.InvalidClaudeRequestException
import java.time.Instant

data class ClaudeSkill(val name: String, val files: Map<String, String>, val updatedAt: Instant? = null) {

    companion object {
        const val ENTRY = "SKILL.md"
        const val MAX_FILES = 50
        const val MAX_CHARS = 500_000
        private val NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private val PATH = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*")
        private val RESERVED = setOf("synced")
        private val DESCRIPTION = Regex("""(?m)^description:\s*\S""")

        fun name(raw: String?): String {
            val name = raw?.trim().orEmpty()
            if (!NAME.matches(name) || name in RESERVED) throw InvalidClaudeRequestException("스킬 이름은 영문 소문자·숫자·하이픈 64자 이내여야 합니다: $name")
            return name
        }

        fun of(name: String?, files: Map<String, String>?, now: Instant): ClaudeSkill {
            val skillName = name(name)
            val content = files.orEmpty().mapKeys { it.key.trim() }
            if (content.size > MAX_FILES) throw InvalidClaudeRequestException("스킬 파일은 ${MAX_FILES}개까지입니다")
            if (content.values.sumOf { it.length } > MAX_CHARS) throw InvalidClaudeRequestException("스킬 내용이 너무 깁니다(최대 ${MAX_CHARS}자)")
            content.keys.forEach { if (!PATH.matches(it)) throw InvalidClaudeRequestException("스킬 파일 경로는 스킬 폴더 안의 상대 경로여야 합니다: $it") }
            val entry = content[ENTRY] ?: throw InvalidClaudeRequestException("$ENTRY 가 필요합니다")
            if (!entry.trimStart().startsWith("---") || !DESCRIPTION.containsMatchIn(entry)) {
                throw InvalidClaudeRequestException("$ENTRY 는 description 이 있는 YAML 머리말(---)로 시작해야 합니다")
            }
            content.forEach { (path, text) -> PortablePaths.require("$skillName/$path", text) }
            return ClaudeSkill(skillName, content.toSortedMap(), now)
        }
    }
}
