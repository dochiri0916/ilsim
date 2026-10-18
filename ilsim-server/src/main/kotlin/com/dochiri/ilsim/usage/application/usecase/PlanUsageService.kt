package com.dochiri.ilsim.usage.application.usecase

import com.dochiri.ilsim.usage.application.port.`in`.PlanUsageUseCase
import com.dochiri.ilsim.usage.application.port.out.PlanUsageStore
import com.dochiri.ilsim.usage.domain.PlanUsage
import com.dochiri.ilsim.usage.domain.UsageWindow
import com.dochiri.ilsim.usage.domain.exception.InvalidUsageRequestException
import java.time.Clock

class PlanUsageService(private val store: PlanUsageStore, private val clock: Clock = Clock.systemUTC()) : PlanUsageUseCase {

    override fun all(): List<PlanUsage> = store.findAll().sortedWith(compareBy({ it.runnerId }, { it.provider != PlanUsage.CLAUDE }, { it.provider }))

    @Synchronized
    override fun report(runnerId: String, provider: String, status: String?, fiveHour: UsageWindow?, sevenDay: UsageWindow?): PlanUsage =
        current(runnerId, provider).observe(status, fiveHour, sevenDay, clock.instant()).also(store::save)

    @Synchronized
    override fun requestRefresh(runnerId: String): PlanUsage {
        val now = clock.instant()
        val claude = current(runnerId, PlanUsage.CLAUDE).requestRefresh(now).also(store::save)
        store.findByRunner(claude.runnerId).filter { it.provider != PlanUsage.CLAUDE }.forEach { store.save(it.requestRefresh(now)) }
        return claude
    }

    @Synchronized
    override fun claimRefresh(runnerId: String): Boolean {
        val requested = store.findByRunner(runnerId.trim()).filter { it.refreshRequestedAt != null }
        requested.forEach { store.save(it.claimRefresh()) }
        return requested.isNotEmpty()
    }

    private fun current(runnerId: String, provider: String): PlanUsage {
        val id = runnerId.trim()
        if (id.isEmpty()) throw InvalidUsageRequestException("Runner id 가 비어 있습니다")
        val name = provider.trim().lowercase()
        if (!PlanUsage.validProvider(name)) throw InvalidUsageRequestException("알 수 없는 사용량 계정: $provider")
        return store.find(id, name) ?: PlanUsage(id, name)
    }
}
