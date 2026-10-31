package com.dochiri.ilsim.runner.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReviewApprovalTest {
    private fun rule(id: String) = ArchRule(id, "leaf-unit", "d", mapOf("unit" to listOf("domain")), "BLOCK", Evidence(1, 1))
    private val model = ArchitectureModel(
        "p", 3, Layout("com.x", "FLAT"), emptyList(), emptyList(), emptyList(), emptyList(),
        listOf(rule("a"), rule("b"), rule("c")), emptyList(),
    )

    private val response = """
        설명 문장 {"rules":[{"id":"a","verdict":"KEEP","reason":"의도"},{"id":"b","verdict":"SUSPECT","reason":"우연"},
        {"id":"zzz","verdict":"KEEP"},{"id":"c","verdict":"BOGUS"}],"missing":["m1"],"applicability":"타당"} 끝
    """.trimIndent()

    @Test
    fun `AI 의견은 규칙에 주석으로만 붙고 승인 상태는 바꾸지 않는다`() {
        val r = ArchitectureReviewer.apply(model, response, "h", "sonnet")
        assertEquals("KEEP", r.rules.first { it.id == "a" }.review?.verdict)
        assertEquals("SUSPECT", r.rules.first { it.id == "b" }.review?.verdict)
        assertEquals(null, r.rules.first { it.id == "c" }.review)
        assertTrue(r.rules.none { it.approved || it.rejected })
        assertEquals(listOf("m1"), r.review!!.missingRules)
        assertEquals(listOf("a"), RuleApproval.keepIds(r))
    }

    @Test
    fun `같은 구조는 다시 검토하지 않고 구조가 바뀌면 다시 검토한다`() {
        var calls = 0
        val complete = """{"rules":[{"id":"a","verdict":"KEEP","reason":"충분"},{"id":"b","verdict":"SUSPECT","reason":"부족"},{"id":"c","verdict":"SUSPECT","reason":"부족"}],"missing":[],"applicability":"ok"}"""
        val ask = { _: String -> calls++; complete }
        val once = ArchitectureReviewer.review(model, "sonnet", ask = ask)
        ArchitectureReviewer.review(once, "sonnet", ask = ask)
        assertEquals(1, calls)
        ArchitectureReviewer.review(once, "sonnet", force = true, ask = ask)
        assertEquals(2, calls)
        ArchitectureReviewer.review(once, "haiku", ask = ask)
        assertEquals(3, calls)
        val incomplete = ArchitectureReviewer.review(model, "sonnet") { "{}" }
        assertEquals("", incomplete.review!!.modelHash)
        ArchitectureReviewer.review(incomplete, "sonnet", ask = ask)
        assertEquals(4, calls)
        assertEquals(ArchitectureReviewer.hash(model), ArchitectureReviewer.hash(RuleApproval.approve(model, listOf("a"))))
        assertNotEquals(ArchitectureReviewer.hash(model), ArchitectureReviewer.hash(model.copy(rules = model.rules.drop(1))))
    }

    @Test
    fun `JSON 이 없는 응답은 거부하고 입력 요약에 소스 경로가 없다`() {
        assertFailsWith<IllegalArgumentException> { ArchitectureReviewer.apply(model, "죄송합니다", "h", "m") }
        assertTrue(!ArchitectureReviewer.summary(model).contains(".kt"))
    }

    @Test
    fun `승인과 거절은 모델을 갱신하고 없는 id 는 거부한다`() {
        val m = RuleApproval.reject(RuleApproval.approve(model, listOf("a", "b")), listOf("b"))
        assertEquals(listOf(true, false, false), m.rules.map { it.approved })
        assertEquals(listOf(false, true, false), m.rules.map { it.rejected })
        assertEquals(listOf("c"), RuleApproval.undecidedIds(m))
        assertFailsWith<IllegalArgumentException> { RuleApproval.approve(model, listOf("nope")) }
    }

    @Test
    fun `검사기는 거절한 규칙을 제외하고 게이트 모드에서는 승인한 규칙만 적용한다`() {
        val files = listOf(
            SourceFile("m", "D.kt", "com.x.domain", listOf(ImportRef("com.x.service.S", 3))),
            SourceFile("m", "S.kt", "com.x.service", emptyList()),
        )
        val base = ArchitectureModel(
            "p", 2, Layout("com.x", "FLAT"), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(rule("leaf-unit:domain").copy(params = mapOf("unit" to listOf("domain")))), emptyList(),
        )
        assertEquals(1, ArchitectureChecker.check(files, base).size)
        assertEquals(0, ArchitectureChecker.check(files, base, approvedOnly = true).size)
        assertEquals(1, ArchitectureChecker.check(files, RuleApproval.approve(base, listOf("leaf-unit:domain")), approvedOnly = true).size)
        assertEquals(0, ArchitectureChecker.check(files, RuleApproval.reject(base, listOf("leaf-unit:domain"))).size)
    }

    @Test
    fun `AI 판정대로 KEEP 은 승인하고 SUSPECT 는 거절하며 사용자가 정한 규칙과 다시 분석한 결과의 결정은 유지한다`() {
        val reviewed = ArchitectureReviewer.apply(model, response, "h", "sonnet")
        val fresh = RuleApproval.autoApprove(reviewed)
        assertEquals(listOf(true, false, false), fresh.rules.map { it.approved })
        assertEquals(listOf(false, true, false), fresh.rules.map { it.rejected }, "의견 없는 c 는 판정 없이 꺼 둔다")
        assertEquals(listOf("ai-review", "ai-review", null), fresh.rules.map { it.decidedBy })
        assertEquals("BLOCK", fresh.rules.first().proposedSeverity)

        val violated = RuleApproval.autoApprove(reviewed, setOf("a"))
        assertEquals("REVIEW", violated.rules.first().proposedSeverity, "교차 검증에서 새 단위와 어긋나는 규칙은 검토로 낮춰 승인한다(기존 위반은 낮추지 않고 동결한다)")
        assertTrue(violated.rules.first().approved)

        val decided = RuleApproval.reject(reviewed, listOf("a"))
        assertEquals(false, RuleApproval.autoApprove(decided).rules.first().approved, "사용자가 거절한 규칙은 AI 판정으로 바꾸지 않는다")

        val userApproved = RuleApproval.approve(fresh, listOf("b"))
        assertEquals("user", userApproved.rules[1].decidedBy)
        val carried = RuleApproval.autoApprove(RuleApproval.carryOver(userApproved, model))
        assertEquals(listOf(true, true, false), carried.rules.map { it.approved })
        assertEquals("KEEP", carried.rules.first().review?.verdict)
        assertEquals("BLOCK", RuleApproval.carryOver(violated, model).rules.first().proposedSeverity, "AI 결정은 다시 분석할 때 새 위반 기준으로 다시 계산한다")
        val changedParams = model.copy(rules = listOf(rule("a").copy(params = mapOf("unit" to listOf("other")))) + model.rules.drop(1))
        assertEquals(false, RuleApproval.carryOver(userApproved, changedParams).rules.first().approved, "인자가 달라진 규칙은 새 규칙으로 본다")

        val triage = ArchitectureReport.triage(fresh, "p")
        assertTrue(triage.contains("자동 승인 1(차단 1)") && triage.contains("자동 거절 1") && triage.contains("판정 없음 1") && triage.contains("[의견 없음] c"), triage)
    }
}
