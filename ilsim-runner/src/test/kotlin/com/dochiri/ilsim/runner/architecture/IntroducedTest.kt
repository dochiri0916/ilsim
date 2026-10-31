package com.dochiri.ilsim.runner.architecture

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class IntroducedTest {
    private fun v(rule: String, file: String, line: Int) = Violation(rule, "BLOCK", file, line, "m")

    @Test
    fun `같은 규칙·파일·줄 내용의 위반은 개수만큼 기존 위반으로 보고 나머지만 새 위반으로 본다`() {
        val before = listOf("import a.S", "class D").let { lines -> mapOf("D.kt" to lines) }
        val after = mapOf("D.kt" to listOf("import a.S", "import a.T", "class D", "import a.S"))
        val old = listOf(v("r", "D.kt", 1))
        val now = listOf(v("r", "D.kt", 1), v("r", "D.kt", 2), v("r", "D.kt", 4))

        val fresh = Introduced.filter(now, old, { after[it] }, { before[it] })

        assertEquals(listOf(2, 4), fresh.map { it.line }, "줄이 밀려도 내용으로 맞추고, 같은 내용이 하나 더 생기면 새 위반이다")
        assertEquals(3, Introduced.filter(now, old, { after[it] }, { null }).size, "기존 파일 내용을 모르면 모두 새 위반이다")
        assertEquals(listOf(1, 2, 4), Introduced.filter(now, listOf(v("other", "D.kt", 1)), { after[it] }, { before[it] }).map { it.line }, "규칙이 다르면 같은 줄이라도 새 위반이다")
    }

    @Test
    fun `동결 수와 지난 분석 대비 증감을 알린다`() {
        val frozen = Introduced.frozen(listOf(v("a", "x", 1), v("a", "y", 2), v("b", "x", 3)))
        assertEquals(mapOf("a" to 2, "b" to 1), frozen)
        assertContains(Introduced.trend(frozen, null), "승인 규칙 2개에 3건")
        assertContains(Introduced.trend(frozen, mapOf("a" to 4, "c" to 1)), "지난 분석 5건에서 해소 3건·늘어남 1건")
    }
}
