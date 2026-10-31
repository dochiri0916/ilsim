package com.dochiri.ilsim.runner.mcp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LedgerTest {
    @Test
    fun `통과로 확인되지 않은 검사를 뜻과 함께 모은다`() {
        val validation = linkedMapOf("tests" to "PASSED", "mutation" to "NOT_CONFIGURED", "ai-audit" to "SKIPPED", "requirements" to "UNCERTAIN", "guidelines" to "NONE", "risk" to "UNKNOWN")
        assertEquals(listOf("mutation=NOT_CONFIGURED(설정 없음)", "ai-audit=SKIPPED(생략)", "requirements=UNCERTAIN(불확실)", "risk=UNKNOWN(판정 못 함)"), Ledger.unconfirmed(validation))
    }

    @Test
    fun `요구사항마다 판정 확률과 테스트 위치를 표로 남기고 빈칸을 따로 알린다`() {
        val text = Ledger.render(
            "IMPLEMENTATION", linkedMapOf("tests" to "PASSED", "requirements" to "PASSED"), "요구사항",
            listOf(
                Ledger.Row("REQ-1", "주문 | 합계", 0.97, listOf("TC-1"), listOf("OrderTest.kt:12")),
                Ledger.Row("REQ-2", "취소", null, emptyList(), emptyList()),
            ),
            "조건 충족으로 생략",
        )
        assertContains(text, "| REQ-1 주문 \\| 합계 | 0.97 | TC-1 | OrderTest.kt:12 |")
        assertContains(text, "| REQ-2 취소 | - | - | - |")
        assertContains(text, "- 확인 못 한 항목: 없음")
        assertContains(text, "- 테스트 위치를 찾지 못한 항목: REQ-2")
    }

    @Test
    fun `문서 지침은 판정한 구간과 관련 없어 제외한 구간을 모두 남긴다`() {
        val text = Ledger.render(
            "IMPLEMENTATION", linkedMapOf("guidelines" to "UNCERTAIN"), null, emptyList(), "실행",
            listOf(
                Ledger.GuidelineRow("AGENTS.md:3", "주석 금지", Ledger.PASSED, 0.05),
                Ledger.GuidelineRow("AGENTS.md:9", "로그", Ledger.UNCERTAIN, 0.5),
                Ledger.GuidelineRow("docs/a.md:1", "배포", Ledger.EXCLUDED),
            ),
        )
        assertContains(text, "구간 3개: 판정 대상 2개, 이 작업과 관련 없어 제외 1개")
        assertContains(text, "| AGENTS.md:3 주석 금지 | 통과 | 0.05 |")
        assertContains(text, "| AGENTS.md:9 로그 | 불확실 | 0.50 |")
        assertContains(text, "관련 없어 제외한 구간(잘못 빠졌으면 사용자가 알려 주세요): docs/a.md:1")
        val brief = Ledger.brief(text)
        assertContains(brief, "| AGENTS.md:9 로그 | 불확실 | 0.50 |")
        assertContains(brief, "- 확인 못 한 항목: guidelines=UNCERTAIN(불확실)")
        assertFalse("주석 금지" in brief || "관련 없어 제외한 구간(" in brief || "| 지침 |" in brief)
    }
}
