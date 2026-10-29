package com.dochiri.ilsim.runner

import kotlin.test.Test
import kotlin.test.assertContains

class MetricsReportTest {
    @Test
    fun `단계별 실패 원인과 작업별 비용·병합 여부를 보여 준다`() {
        val metrics = TaskMetrics(2, 1, 0, 1,
            listOf(StageMetrics("TEST_CODE", 3, 2, mapOf("guidelines" to 1, "test-cases" to 1), 2, 0.004, 0.06)),
            listOf(TaskSummary("aaaaaaaa-1", "slug", "COMPLETED", "ilsim/slug-aaaaaaaa", 3, 2, 2, 0.064), TaskSummary("bbbbbbbb-2", "greet", "ACTIVE")))
        val text = MetricsReport.render("p", metrics, mapOf("aaaaaaaa-1" to true))
        assertContains(text, "작업 2개(완료 1 · 차단 0 · 진행 1)")
        assertContains(text, "완료 작업 중 현재 브랜치에 병합 1/1")
        assertContains(text, "TEST_CODE | 3 | 2 | guidelines 1, test-cases 1 | 2 | $0.0040 | $0.0600")
        assertContains(text, "제출 3회 중 실패 2회, Claude 호출 2회(제출당 0.67)")
        assertContains(text, "slug (aaaaaaaa) | COMPLETED | 3 | 2 | 2 | $0.0640 | 병합")
        assertContains(text, "greet (bbbbbbbb) | ACTIVE | 0 | 0 | 0 | $0.0000 | -")
    }
}
