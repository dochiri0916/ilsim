package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SelfTestReportTest {
    @Test
    fun `자체 시험은 승인 여부와 등급으로 제출 검사에서 막히는지·검토로 가는지·못 잡는지를 나눈다`() {
        val rules = listOf(
            RuleState("block", "막는 규칙", approved = true, blocking = true),
            RuleState("review", "검토 규칙", approved = true, blocking = false),
            RuleState("missed", "못 잡는 규칙", approved = true, blocking = true),
            RuleState("untested", "위반을 못 만드는 규칙", approved = true, blocking = true),
            RuleState("pending", "승인 전 규칙", approved = false, blocking = true),
        )

        val report = selfTestReport(rules, caught = listOf("block", "review", "pending"), missed = listOf("missed"), untested = listOf("untested"), reasons = mapOf("untested" to "대상이 지금 코드에 없음"))
        assertContains(report, "? untested  위반을 못 만드는 규칙  [대상이 지금 코드에 없음]")

        assertContains(report, "승인 규칙 4개 중 막음 1, 막지 않고 기록 1, 못 잡음 1, 위반을 못 만듦 1. 승인 전·꺼 둔 규칙 1개는 강제하지 않음")
        assertContains(report, "✗ missed  못 잡는 규칙")
        assertContains(report, "? untested  위반을 못 만드는 규칙")
        assertFalse("pending" in report.lines().drop(1).joinToString("\n"), "승인 전 규칙은 목록에 넣지 않는다")
    }
}
