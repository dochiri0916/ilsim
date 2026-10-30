package com.dochiri.ilsim.runner.mcp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
}
