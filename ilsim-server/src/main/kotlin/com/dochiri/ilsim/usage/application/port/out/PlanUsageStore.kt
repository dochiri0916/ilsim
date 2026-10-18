package com.dochiri.ilsim.usage.application.port.out

import com.dochiri.ilsim.usage.domain.PlanUsage

/** Runner 별 최근 사용량 하나. */
interface PlanUsageStore {
    fun find(runnerId: String): PlanUsage?

    fun findAll(): List<PlanUsage>

    fun save(usage: PlanUsage)
}
