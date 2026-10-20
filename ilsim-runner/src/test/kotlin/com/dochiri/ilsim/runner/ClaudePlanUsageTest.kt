package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudePlanUsageTest {
    @Test
    fun `rate_limit_event 에서 5시간·7일 사용률과 재설정 시각을 읽는다`() {
        val usage = ClaudePlanUsage.parse(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1792257600,"rateLimitType":"five_hour","unifiedWindows":{"five_hour":{"utilization":0.22,"resetsAt":1792257600},"seven_day":{"utilization":0.34,"resetsAt":1792666800}}},"session_id":"s"}""",
        )
        assertEquals(PlanUsageReport(status = "allowed", fiveHour = UsageWindowReport(0.22, 1792257600), sevenDay = UsageWindowReport(0.34, 1792666800)), usage)
    }

    @Test
    fun `다른 이벤트나 창 정보가 없으면 무시한다`() {
        assertNull(ClaudePlanUsage.parse("""{"type":"result","total_cost_usd":0.1}"""))
        assertNull(ClaudePlanUsage.parse("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}"""))
        assertNull(ClaudePlanUsage.parse("rate_limit_event 깨진 줄"))
    }

    @Test
    fun `토큰이 들지 않는 usage 명령 출력에서 사용률과 재설정 시각을 읽는다`() {
        val text = "You are currently using your subscription to power your Claude Code usage\n\n" +
            "Current session: 28% used · resets Oct 18 at 2:20am (Asia/Seoul)\n" +
            "Current week (all models): 35% used · resets Oct 22 at 8pm (Asia/Seoul)\n\nLast 24h · 1025 requests"
        val usage = UsageCommand.parse(text, java.time.Instant.parse("2026-10-17T13:30:00Z"))
        assertEquals(PlanUsageReport(
            fiveHour = UsageWindowReport(0.28, java.time.Instant.parse("2026-10-17T17:20:00Z").epochSecond),
            sevenDay = UsageWindowReport(0.35, java.time.Instant.parse("2026-10-22T11:00:00Z").epochSecond),
        ), usage)
    }

    @Test
    fun `연도가 넘어가거나 날짜가 없거나 시각을 못 읽어도 다룬다`() {
        val now = java.time.Instant.parse("2026-12-31T14:00:00Z")
        assertEquals(java.time.Instant.parse("2027-01-02T11:00:00Z").epochSecond,
            UsageCommand.parse("Current week (all models): 5% used · resets Jan 2 at 8pm (Asia/Seoul)", now)?.sevenDay?.resetsAt)
        assertEquals(java.time.Instant.parse("2026-12-31T17:20:00Z").epochSecond,
            UsageCommand.parse("Current session: 1% used · resets 2:20am (Asia/Seoul)", now)?.fiveHour?.resetsAt)
        assertEquals(UsageWindowReport(0.5, null), UsageCommand.parse("Current session: 50% used · resets soon", now)?.fiveHour)
        assertNull(UsageCommand.parse("You are using an API key", now))
    }
}
