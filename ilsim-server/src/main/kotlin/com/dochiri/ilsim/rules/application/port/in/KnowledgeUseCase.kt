package com.dochiri.ilsim.rules.application.port.`in`

import com.dochiri.ilsim.rules.domain.Decision
import com.dochiri.ilsim.rules.domain.Knowledge
import com.dochiri.ilsim.rules.domain.RuleSet

interface KnowledgeUseCase {
    fun overview(projectId: String): Knowledge

    /** 모르는 id 가 하나라도 있으면 아무것도 바꾸지 않고 거절한다. */
    fun decideRules(projectId: String, set: RuleSet, ids: List<String>?, decision: Decision)

    fun decideMemory(projectId: String, ids: List<String>?, decision: Decision)
}
