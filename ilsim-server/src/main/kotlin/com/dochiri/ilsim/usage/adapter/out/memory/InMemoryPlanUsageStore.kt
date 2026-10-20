package com.dochiri.ilsim.usage.adapter.out.memory

import com.dochiri.ilsim.usage.application.port.out.PlanUsageStore
import com.dochiri.ilsim.usage.domain.PlanUsage
import java.util.concurrent.ConcurrentHashMap

class InMemoryPlanUsageStore : PlanUsageStore {
    private val usages = ConcurrentHashMap<Pair<String, String>, PlanUsage>()

    override fun find(runnerId: String, provider: String): PlanUsage? = usages[runnerId to provider]

    override fun findByRunner(runnerId: String): List<PlanUsage> = usages.values.filter { it.runnerId == runnerId }

    override fun findAll(): List<PlanUsage> = usages.values.toList()

    override fun save(usage: PlanUsage) {
        usages[usage.runnerId to usage.provider] = usage
    }
}
