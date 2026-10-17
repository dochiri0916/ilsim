package com.dochiri.ilsim.rules.application.usecase

import com.dochiri.ilsim.rules.application.port.`in`.KnowledgeUseCase
import com.dochiri.ilsim.rules.application.port.out.KnowledgeStore
import com.dochiri.ilsim.rules.domain.Decision
import com.dochiri.ilsim.rules.domain.Knowledge
import com.dochiri.ilsim.rules.domain.RuleSet
import com.dochiri.ilsim.rules.domain.exception.InvalidRuleRequestException

class KnowledgeService(private val store: KnowledgeStore) : KnowledgeUseCase {

    override fun overview(projectId: String): Knowledge = Knowledge(
        store.rules(projectId, RuleSet.ARCHITECTURE),
        store.rules(projectId, RuleSet.CONVENTION),
        store.memory(projectId),
    )

    override fun decideRules(projectId: String, set: RuleSet, ids: List<String>?, decision: Decision) {
        val wanted = requireIds(ids)
        requireKnown(wanted, store.rules(projectId, set).map { it.id }, "규칙")
        store.decideRules(projectId, set, wanted, decision)
    }

    override fun decideMemory(projectId: String, ids: List<String>?, decision: Decision) {
        val wanted = requireIds(ids)
        requireKnown(wanted, store.memory(projectId).map { it.id }, "메모리")
        store.decideMemory(projectId, wanted, decision)
    }

    private fun requireIds(ids: List<String>?): Set<String> {
        val wanted = ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) throw InvalidRuleRequestException("대상 id 가 비어 있습니다")
        return wanted
    }

    private fun requireKnown(wanted: Set<String>, known: List<String>, label: String) {
        val unknown = wanted - known.toSet()
        if (unknown.isNotEmpty()) throw InvalidRuleRequestException("알 수 없는 $label id: ${unknown.joinToString()}")
    }
}
