package com.dochiri.ilsim.rules.application.port.`in`

import com.dochiri.ilsim.rules.domain.Decision
import com.dochiri.ilsim.rules.domain.Knowledge
import com.dochiri.ilsim.rules.domain.RuleSet

interface KnowledgeUseCase {
    fun overview(projectId: String): Knowledge

    fun decideRules(projectId: String, set: RuleSet, ids: List<String>?, decision: Decision)

    fun decideMemory(projectId: String, ids: List<String>?, decision: Decision)
}
