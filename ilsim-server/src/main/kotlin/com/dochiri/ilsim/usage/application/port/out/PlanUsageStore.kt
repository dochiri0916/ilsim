package com.dochiri.ilsim.usage.application.port.out

import com.dochiri.ilsim.usage.domain.PlanUsage

interface PlanUsageStore {
    fun find(runnerId: String, provider: String): PlanUsage?

    fun findByRunner(runnerId: String): List<PlanUsage>

    fun findAll(): List<PlanUsage>

    fun save(usage: PlanUsage)
}
