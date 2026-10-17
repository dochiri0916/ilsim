package com.dochiri.ilsim.rules.application.port.out

import com.dochiri.ilsim.rules.domain.Decision
import com.dochiri.ilsim.rules.domain.MemoryView
import com.dochiri.ilsim.rules.domain.RuleSet
import com.dochiri.ilsim.rules.domain.RuleView

/** Runner 가 쓰는 규칙·메모리 저장소. 같은 PC 의 파일이라 서버는 승인 상태만 바꾸고 나머지 내용은 그대로 둔다. */
interface KnowledgeStore {
    fun rules(projectId: String, set: RuleSet): List<RuleView>

    fun memory(projectId: String): List<MemoryView>

    /** [ids] 규칙의 승인·거절 상태를 바꾼다. */
    fun decideRules(projectId: String, set: RuleSet, ids: Collection<String>, decision: Decision)

    /** [ids] 메모리의 상태를 바꾼다. 이미 폐기(SUPERSEDED)된 항목은 건드리지 않는다. */
    fun decideMemory(projectId: String, ids: Collection<String>, decision: Decision)
}
