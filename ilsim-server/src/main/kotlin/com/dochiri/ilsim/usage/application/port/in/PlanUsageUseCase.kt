package com.dochiri.ilsim.usage.application.port.`in`

import com.dochiri.ilsim.usage.domain.PlanUsage
import com.dochiri.ilsim.usage.domain.UsageWindow

interface PlanUsageUseCase {
    fun all(): List<PlanUsage>

    fun report(runnerId: String, status: String?, fiveHour: UsageWindow?, sevenDay: UsageWindow?): PlanUsage

    fun requestRefresh(runnerId: String): PlanUsage

    /** 새로 재 달라는 요청이 있으면 비우고 true. */
    fun claimRefresh(runnerId: String): Boolean
}
