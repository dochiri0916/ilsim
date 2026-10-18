package com.dochiri.ilsim.usage.domain

import java.time.Instant

/** 한 사용량 창(5시간·7일). [utilization]은 0~1 비율. */
data class UsageWindow(val utilization: Double, val resetsAt: Instant?)

/**
 * Runner 가 쓰는 Claude 구독 플랜의 사용량. Claude 실행이 남긴 값을 Runner 가 보고한다.
 * Runner(=PC)와 [provider](계정)마다 하나이며, 일심 밖에서 쓴 양도 포함되므로 [observedAt] 시점의 값이다.
 */
data class PlanUsage(
    val runnerId: String,
    val provider: String = CLAUDE,
    val status: String? = null,
    val fiveHour: UsageWindow? = null,
    val sevenDay: UsageWindow? = null,
    val observedAt: Instant? = null,
    /** 웹에서 새로 재 달라고 요청한 시각. Runner 가 가져가면 비운다. */
    val refreshRequestedAt: Instant? = null,
) {
    fun observe(status: String?, fiveHour: UsageWindow?, sevenDay: UsageWindow?, now: Instant): PlanUsage =
        copy(status = status, fiveHour = fiveHour, sevenDay = sevenDay, observedAt = now)

    fun requestRefresh(now: Instant): PlanUsage = copy(refreshRequestedAt = refreshRequestedAt ?: now)

    fun claimRefresh(): PlanUsage = copy(refreshRequestedAt = null)

    companion object {
        const val CLAUDE = "claude"
        private val PROVIDER = Regex("^claude(@[a-z0-9][a-z0-9-]{0,29})?$")

        /** claude(기본 로그인) 또는 claude@<계정>(일심에 등록한 Claude 계정). */
        fun validProvider(name: String): Boolean = PROVIDER.matches(name)
    }
}
