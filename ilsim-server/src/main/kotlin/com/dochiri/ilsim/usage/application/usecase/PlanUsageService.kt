package com.dochiri.ilsim.usage.application.usecase

import com.dochiri.ilsim.usage.application.port.`in`.PlanUsageUseCase
import com.dochiri.ilsim.usage.application.port.out.PlanUsageStore
import com.dochiri.ilsim.usage.domain.PlanUsage
import com.dochiri.ilsim.usage.domain.UsageWindow
import com.dochiri.ilsim.usage.domain.exception.InvalidUsageRequestException
import java.time.Clock

class PlanUsageService(private val store: PlanUsageStore, private val clock: Clock = Clock.systemUTC()) : PlanUsageUseCase {

    override fun all(): List<PlanUsage> = store.findAll().sortedBy { it.runnerId }

    @Synchronized
    override fun report(runnerId: String, status: String?, fiveHour: UsageWindow?, sevenDay: UsageWindow?): PlanUsage =
        current(runnerId).observe(status, fiveHour, sevenDay, clock.instant()).also(store::save)

    @Synchronized
    override fun requestRefresh(runnerId: String): PlanUsage = current(runnerId).requestRefresh(clock.instant()).also(store::save)

    @Synchronized
    override fun claimRefresh(runnerId: String): Boolean {
        val usage = store.find(runnerId.trim()) ?: return false
        if (usage.refreshRequestedAt == null) return false
        store.save(usage.claimRefresh())
        return true
    }

    private fun current(runnerId: String): PlanUsage {
        val id = runnerId.trim()
        if (id.isEmpty()) throw InvalidUsageRequestException("Runner id 가 비어 있습니다")
        return store.find(id) ?: PlanUsage(id)
    }
}
