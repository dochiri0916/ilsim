package com.dochiri.ilsim.claude.domain

import com.dochiri.ilsim.claude.domain.exception.InvalidClaudeRequestException

object PortablePaths {
    private val userHome = Regex("""(?<![\w.~])(/Users/[^/\s"'`)]+|/home/[^/\s"'`)]+|[A-Za-z]:\\Users\\[^\\\s"'`)]+)""")

    fun require(label: String, text: String) {
        val found = userHome.find(text) ?: return
        throw InvalidClaudeRequestException("$label 에 PC마다 다른 절대 경로가 있습니다: ${found.value}. ~ 또는 \$HOME 처럼 홈 기준 경로로 바꾸세요")
    }
}
