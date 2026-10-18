package com.dochiri.ilsim.usage.adapter.out.memory

import com.dochiri.ilsim.usage.application.port.out.PlanUsageStore
import com.dochiri.ilsim.usage.domain.PlanUsage
import java.util.concurrent.ConcurrentHashMap

/** 사용량은 Claude 실행마다 다시 보고되므로 서버를 다시 띄우면 사라져도 된다. */
class InMemoryPlanUsageStore : PlanUsageStore {
    private val usages = ConcurrentHashMap<String, PlanUsage>()

    override fun find(runnerId: String): PlanUsage? = usages[runnerId]

    override fun findAll(): List<PlanUsage> = usages.values.toList()

    override fun save(usage: PlanUsage) {
        usages[usage.runnerId] = usage
    }
}
