package com.dochiri.ilsim.usage.application.port.`in`

import com.dochiri.ilsim.usage.domain.PlanUsage
import com.dochiri.ilsim.usage.domain.UsageWindow

interface PlanUsageUseCase {
    fun all(): List<PlanUsage>

    fun report(runnerId: String, provider: String, status: String?, fiveHour: UsageWindow?, sevenDay: UsageWindow?): PlanUsage

    /** Runner 의 모든 계정을 새로 재 달라고 요청한다. 돌려주는 값은 Claude 항목. */
    fun requestRefresh(runnerId: String): PlanUsage

    /** 새로 재 달라는 요청이 있으면 비우고 true. */
    fun claimRefresh(runnerId: String): Boolean
}
