package com.dochiri.ilsim.runner.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeAuditTest {

    @Test
    fun `프롬프트는 네 가지만 보게 하고 설계 평가를 금지하며 입력 크기를 제한한다`() {
        val p = ChangeAudit.prompt(
            "IMPLEMENTATION", "REQ-1 주문 생성", "TC-1 REQ-1", "## 이 프로젝트의 기존 규칙\n- domain 은 의존하지 않는다",
            mapOf("architecture" to "PASSED", "mutation" to "LOW"), "x".repeat(50_000),
        )
        assertTrue(p.contains("설계가 좋은지 나쁜지는 판단하지 마라"))
        for (t in listOf("OMISSION", "UNRELATED", "TEST_BYPASS", "RULE")) assertTrue(p.contains(t))
        assertTrue(p.contains("REQ-1 주문 생성") && p.contains("domain 은 의존하지 않는다") && p.contains("- mutation: LOW"))
        assertTrue(p.length < 40_000, "diff 는 앞부분만 보낸다")
        assertTrue(p.contains("전체 50000자"))
    }

    @Test
    fun `응답에서 유효한 지적만 남기고 지적이 없으면 PASS 로 본다`() {
        val r = ChangeAudit.parse("""설명 {"verdict":"PASS","findings":[{"type":"OMISSION","target":"REQ-2","reason":"주문 취소가 구현되지 않음"},{"type":"STYLE","reason":"무시"},{"type":"RULE","target":"A.kt:3","reason":""}]} 끝""")
        assertEquals("CONCERN", r.verdict, "verdict 가 PASS 라도 유효한 지적이 있으면 CONCERN")
        assertEquals(listOf("OMISSION"), r.findings.map { it.type })
        assertEquals("PASS", ChangeAudit.parse("""{"verdict":"CONCERN","findings":[]}""").verdict)
        assertTrue(ChangeAudit.render(r).contains("[OMISSION] REQ-2"))
        assertFailsWith<IllegalArgumentException> { ChangeAudit.parse("판단 불가") }
    }

    @Test
    fun `살아남은 변이를 입력에 넣고 테스트 약화 지적은 Agent 에게 되돌리지 않는다`() {
        val p = ChangeAudit.prompt("IMPLEMENTATION", null, null, null, emptyMap(), "diff", listOf("Order.kt:12 removed call (SURVIVED)"))
        assertTrue(p.contains("살아남은 변이") && p.contains("Order.kt:12") && p.contains("WEAK_TEST"))
        assertTrue(!ChangeAudit.prompt("IMPLEMENTATION", null, null, null, emptyMap(), "diff").contains("WEAK_TEST:"))

        val r = ChangeAudit.parse("""{"findings":[{"type":"WEAK_TEST","target":"Order.kt:12","reason":"취소 금액 계산을 검증하지 않음"},{"type":"OMISSION","target":"REQ-2","reason":"누락"}]}""")
        assertEquals(2, r.findings.size)
        assertEquals(listOf("OMISSION"), ChangeAudit.fixable(r).map { it.type }, "구현 단계에서는 테스트를 고칠 수 없으므로 WEAK_TEST 는 승인자에게만 보인다")
        assertEquals(emptyList(), ChangeAudit.fixable(ChangeAudit.parse("""{"findings":[{"type":"WEAK_TEST","reason":"x"}]}""")))
    }

    @Test
    fun `커밋된 테스트가 있으면 프롬프트에 포함하고 길이를 제한하며 없으면 넣지 않는다`() {
        val with = ChangeAudit.prompt("IMPLEMENTATION", "REQ-1", "TC-1", null, emptyMap(), "diff", committedTests = mapOf("src/test/A.java" to "x".repeat(20_000)))
        assertTrue(with.contains("기존 테스트") && with.contains("src/test/A.java"))
        assertTrue(with.length < 20_000)
        assertFalse(ChangeAudit.prompt("IMPLEMENTATION", "REQ-1", "TC-1", null, emptyMap(), "diff").contains("기존 테스트"))
    }
}
