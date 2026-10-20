package com.dochiri.ilsim.usage.domain

import java.time.Instant

data class UsageWindow(val utilization: Double, val resetsAt: Instant?)

data class PlanUsage(
    val runnerId: String,
    val provider: String = CLAUDE,
    val status: String? = null,
    val fiveHour: UsageWindow? = null,
    val sevenDay: UsageWindow? = null,
    val observedAt: Instant? = null,
    val refreshRequestedAt: Instant? = null,
) {
    fun observe(status: String?, fiveHour: UsageWindow?, sevenDay: UsageWindow?, now: Instant): PlanUsage =
        copy(status = status, fiveHour = fiveHour, sevenDay = sevenDay, observedAt = now)

    fun requestRefresh(now: Instant): PlanUsage = copy(refreshRequestedAt = refreshRequestedAt ?: now)

    fun claimRefresh(): PlanUsage = copy(refreshRequestedAt = null)

    companion object {
        const val CLAUDE = "claude"
        private val PROVIDER = Regex("^claude(@[a-z0-9][a-z0-9-]{0,29})?$")

        fun validProvider(name: String): Boolean = PROVIDER.matches(name)
    }
}
