package com.dochiri.ilsim.runner.mcp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApprovalViewTest {
    @Test
    fun `요구사항 요약은 제목·요구사항 한 줄씩·영향 범위만 담는다`() {
        val spec = "# 슬러그\n\n## 요구사항\n- REQ-1: 소문자로 바꾼다\n  - 인수 조건: ABC는 abc\n- REQ-2: 공백은 하이픈\n\n## 예외 상황\n- 빈 문자열은 그대로\n\n## 영향 범위\n- src/Slug.java\n- src/SlugTest.java\n\n## 미결정 사항\nQ1. 숫자는?\n"

        val outline = ApprovalView.outline("REQUIREMENTS", spec)

        assertContains(outline, "# 슬러그")
        assertContains(outline, "요구사항:\n- REQ-1 소문자로 바꾼다\n- REQ-2 공백은 하이픈")
        assertContains(outline, "영향 범위:\n- src/Slug.java\n- src/SlugTest.java")
        assertFalse("빈 문자열은 그대로" in outline)
        assertFalse("Q1." in outline, "미결정 질문은 승인 안내가 따로 보인다")
    }

    @Test
    fun `테스트 케이스 요약은 TC 한 줄씩 담는다`() {
        val outline = ApprovalView.outline("TEST_CASES", "| ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |\n|---|---|---|---|---|\n| TC-1 | REQ-1 | 정상 | ABC | abc |\n| TC-2 | REQ-2 | 경계값 | 빈 값 | 빈 값 |\n")
        assertEquals("테스트 케이스:\n- TC-1 REQ-1 | 정상 | ABC | abc\n- TC-2 REQ-2 | 경계값 | 빈 값 | 빈 값", outline)
    }

    @Test
    fun `직전 제출본과 달라진 줄만 지움과 더함으로 보이고 사이의 같은 줄은 줄임표로 접는다`() {
        assertEquals("- b\n+ B\n…\n+ e", ApprovalView.diff("a\nb\nc\nd", "a\nB\nc\nd\ne"))
        assertEquals("(달라진 줄 없음)", ApprovalView.diff("a\nb", "a\nb\n"))
        assertContains(ApprovalView.diff("", (1..100).joinToString("\n"), max = 10), "…외 90줄")
    }
}
