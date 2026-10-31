package com.dochiri.ilsim.runner.jev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestCodeCheckTest {
    @Test
    fun `테스트 케이스 문서의 표와 목록에서 TC를 읽고 정적 검사 TC를 가린다`() {
        val cases = TestCodeCheck.cases(
            """
            | ID | 요구사항 | 분류 | 시나리오 | 기대 결과 |
            |----|----------|------|----------|-----------|
            | TC-1 | REQ-1 | 정상 | `of("A")` | `"a"` |
            | TC-2 | REQ-2 | 정상(정적 검사) | 구현 확인 | `System.out` 이 없다 |

            ## 요구사항 연결
            - REQ-1: TC-1

            - TC-3 (REQ-3, 예외): 빈 문자열이면 예외
            - **TC-4** 코드 확인: 상수를 쓴다
            """.trimIndent(),
        )
        assertEquals(listOf("TC-1", "TC-2", "TC-3", "TC-4"), cases.map { it.id })
        assertFalse(cases[0].static)
        assertTrue(cases[1].static)
        assertFalse(cases[2].static)
        assertTrue(cases[3].static)
        assertEquals("REQ-1 | 정상 | `of(\"A\")` | `\"a\"`", cases[0].text)
    }

    @Test
    fun `기존 테스트를 그대로 돌려 확인하는 회귀 TC는 새 테스트를 요구하지 않는다`() {
        val cases = TestCodeCheck.cases(
            """
            | TC-17 | REQ-5 | 기존 기능 회귀 | 기존 `CenterIntegrationTest`의 목록 테스트 실행(바꾸지 않음) | 그대로 통과한다 |
            | TC-19 | REQ-6 | 기존 기능 회귀 | `./gradlew build`로 구조 규칙 테스트 실행 | 통과한다 |
            | TC-21 | REQ-6 | 기존 기능 회귀 | 이름 없이 조회하면 예전처럼 전부 나온다 | 전부 나온다 |
            | TC-22 | REQ-1 | 정상 | 새 조회 테스트 | 한 건 |
            """.trimIndent(),
        )
        assertEquals(listOf(true, true, false, false), cases.map { it.exempt }, "새 시나리오를 적은 회귀 TC는 테스트가 필요하다")
    }
}
