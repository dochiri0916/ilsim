package com.dochiri.ilsim.runner.jev

import kotlin.test.Test
import kotlin.test.assertEquals

class GuidelineItemsTest {

    @Test
    fun `목록 항목은 앞 문단을 붙여 하나씩 나누고 목록 뒤 문단은 따로 둔다`() {
        val text = """
            ## Rule Development

            새 validation rule을 추가할 때 다음을 함께 검토한다.

            - 실제 rule 구현
            - negative fixture

            규칙 하나를 추가하면서 다른 규칙을 임의로 약화하지 않는다.
        """.trimIndent()

        assertEquals(listOf(
            "새 validation rule을 추가할 때 다음을 함께 검토한다.\n- 실제 rule 구현",
            "새 validation rule을 추가할 때 다음을 함께 검토한다.\n- negative fixture",
            "규칙 하나를 추가하면서 다른 규칙을 임의로 약화하지 않는다.",
        ), Guidelines.items(text))
    }

    @Test
    fun `들여 쓴 하위 항목과 코드 블록은 앞 항목에 붙고 표는 머리글을 붙여 행마다 나눈다`() {
        val spec = """
            ## 요구사항

            - REQ-1: 따옴표 안은 명령이 아니다.
              - 인수 조건: `echo "a > b"`는 허용된다.
            - REQ-2: 따옴표 밖 쓰기는 막는다.
        """.trimIndent()
        assertEquals(listOf("- REQ-1: 따옴표 안은 명령이 아니다.\n  - 인수 조건: `echo \"a > b\"`는 허용된다.", "- REQ-2: 따옴표 밖 쓰기는 막는다."), Guidelines.items(spec))

        val completion = "## Completion\n\n완료 전에 실행한다.\n\n```bash\n./gradlew clean check\n```\n\n실패하면 원인을 고친다."
        assertEquals(listOf("완료 전에 실행한다.\n```bash\n./gradlew clean check\n```", "실패하면 원인을 고친다."), Guidelines.items(completion))

        val table = "## 케이스\n\n| ID | 기대 |\n|---|---|\n| TC-1 | 성공 |\n| TC-2 | 실패 |"
        assertEquals(listOf("| ID | 기대 |\n| TC-1 | 성공 |", "| ID | 기대 |\n| TC-2 | 실패 |"), Guidelines.items(table))
    }

    @Test
    fun `항목이 하나뿐이면 구간을 그대로 쓴다`() {
        val text = "## 금지\n\n- `System.out` 출력 금지."
        assertEquals(listOf(text), Guidelines.items(text))
    }
}
