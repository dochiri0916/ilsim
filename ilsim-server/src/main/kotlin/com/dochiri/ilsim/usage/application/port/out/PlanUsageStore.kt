package com.dochiri.ilsim.usage.application.port.out

import com.dochiri.ilsim.usage.domain.PlanUsage

/** Runner·계정(provider)별 최근 사용량 하나. */
interface PlanUsageStore {
    fun find(runnerId: String, provider: String): PlanUsage?

    fun findByRunner(runnerId: String): List<PlanUsage>

    fun findAll(): List<PlanUsage>

    fun save(usage: PlanUsage)
}
