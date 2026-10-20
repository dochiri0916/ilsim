package com.dochiri.ilsim.usage.application.port.`in`

import com.dochiri.ilsim.usage.domain.PlanUsage
import com.dochiri.ilsim.usage.domain.UsageWindow

interface PlanUsageUseCase {
    fun all(): List<PlanUsage>

    fun report(runnerId: String, provider: String, status: String?, fiveHour: UsageWindow?, sevenDay: UsageWindow?): PlanUsage

    fun requestRefresh(runnerId: String): PlanUsage

    fun claimRefresh(runnerId: String): Boolean
}
