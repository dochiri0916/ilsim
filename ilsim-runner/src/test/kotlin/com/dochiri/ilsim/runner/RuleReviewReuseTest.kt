package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.*
import com.dochiri.ilsim.runner.convention.*
import kotlin.test.*

class RuleReviewReuseTest {
    private val complete = """{"rules":[{"id":"a","verdict":"KEEP","reason":"근거 충분"},{"id":"b","verdict":"KEEP","reason":"근거 충분"}],"missing":[],"applicability":"근거 충족"}"""
    private fun rule(id: String) = ArchRule(id, "leaf-unit", id, mapOf("unit" to listOf(id)), "REVIEW", Evidence(5, 5))
    private val architecture = ArchitectureModel("p", 10, Layout("com.x", "FLAT"), emptyList(), emptyList(), emptyList(), emptyList(), listOf(rule("a"), rule("b")), emptyList())

    @Test
    fun `개별 관측만 바뀌면 해당 규칙을 재검토하고 전역 판단은 계속 요청한다`() {
        val once = ArchitectureReviewer.review(architecture, "sonnet") { complete }
        val changed = once.copy(rules = once.rules.map { if (it.id == "b") it.copy(evidence = Evidence(4, 5)) else it })
        var prompt = ""
        val twice = ArchitectureReviewer.review(changed, "sonnet") {
            prompt = it
            """{"rules":[{"id":"b","verdict":"SUSPECT","reason":"관측 불일치"}],"missing":["전역 누락"],"applicability":"새 근거 검토"}"""
        }
        assertTrue("개별 재검토 대상은 [b]" in prompt)
        assertTrue("전역 누락·충돌" in prompt)
        assertEquals("KEEP", twice.rules.first().review!!.verdict)
        assertEquals("SUSPECT", twice.rules.last().review!!.verdict)
        assertEquals(listOf("전역 누락"), twice.review!!.missingRules)
        assertEquals(ArchitectureReviewer.hash(twice, reviewer = "sonnet"), twice.review.modelHash)
    }

    @Test
    fun `공유 의존 정의가 달라지면 전체를 재검토하고 전역 검토는 재사용 의견도 수정할 수 있다`() {
        val once = ArchitectureReviewer.review(architecture, "sonnet") { complete }
        val changed = once.copy(rules = once.rules.map { if (it.id == "b") it.copy(params = mapOf("unit" to listOf("other"))) else it })
        ArchitectureReviewer.review(changed, "sonnet") { assertFalse("기존 의견 재사용" in it); complete }
        val counts = once.copy(sourceFiles = 11)
        ArchitectureReviewer.review(counts, "sonnet") { assertFalse("기존 의견 재사용" in it); complete }
        val b = once.copy(rules = once.rules.map { if (it.id == "b") it.copy(evidence = Evidence(4, 5)) else it })
        val revised = ArchitectureReviewer.review(b, "sonnet") { complete.replace("근거 충분", "전역 근거로 기존 의견 수정") }
        assertEquals("전역 근거로 기존 의견 수정", revised.rules.first().review!!.reason)
    }

    @Test
    fun `컨벤션의 전체 파일 수 변화는 개별 의견을 재사용하되 전역 검토를 생략하지 않는다`() {
        val model = ConventionModel("p", 10, 5, mapOf("kt" to 15), listOf(
            ConventionRule("a", "indent-style", "a", evidence = Evidence(10, 10)),
            ConventionRule("b", "annotation-suffix", "b", evidence = Evidence(5, 5))))
        val once = ConventionReviewer.review(model, "sonnet") { complete }
        val changed = once.copy(sourceFiles = 11, languages = mapOf("kt" to 16))
        val result = ConventionReviewer.review(changed, "sonnet") {
            assertTrue("개별 재검토 대상은 []" in it)
            """{"rules":[],"missing":[],"applicability":"전체 변화에도 유효"}"""
        }
        assertEquals(2, result.rules.count { it.review?.verdict == "KEEP" })
        assertEquals("전체 변화에도 유효", result.review!!.applicabilityOpinion)
    }

    @Test
    fun `미해결 규칙 또는 전역 필드 누락은 부분 완료로 저장하지 않는다`() {
        val once = ArchitectureReviewer.review(architecture, "sonnet") { complete }
        val changed = once.copy(rules = once.rules.map { if (it.id == "b") it.copy(evidence = Evidence(4, 5)) else it })
        assertFailsWith<IllegalArgumentException> { ArchitectureReviewer.review(changed, "sonnet") { """{"rules":[],"missing":[],"applicability":"ok"}""" } }
        assertFailsWith<IllegalArgumentException> { ArchitectureReviewer.review(changed, "sonnet") { """{"rules":[{"id":"b","verdict":"KEEP","reason":"ok"}],"applicability":"ok"}""" } }
        val incomplete = ArchitectureReviewer.review(architecture, "sonnet") { complete.replace("\"missing\":[],", "") }
        assertEquals("", incomplete.review!!.modelHash)
    }
}
