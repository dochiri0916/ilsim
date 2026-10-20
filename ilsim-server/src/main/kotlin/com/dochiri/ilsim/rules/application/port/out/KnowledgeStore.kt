package com.dochiri.ilsim.rules.application.port.out

import com.dochiri.ilsim.rules.domain.Decision
import com.dochiri.ilsim.rules.domain.MemoryView
import com.dochiri.ilsim.rules.domain.RuleSet
import com.dochiri.ilsim.rules.domain.RuleView

interface KnowledgeStore {
    fun rules(projectId: String, set: RuleSet): List<RuleView>

    fun memory(projectId: String): List<MemoryView>

    fun decideRules(projectId: String, set: RuleSet, ids: Collection<String>, decision: Decision)

    fun decideMemory(projectId: String, ids: Collection<String>, decision: Decision)
}
