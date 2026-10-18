package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.jev.Jev
import kotlin.test.*

class ReviewRouterTest {
    private val prompt = "전체 변경 검토 근거"
    private val pass = """{"verdict":"PASS","findings":[]}"""
    private fun profile(eligible: Boolean) = ReviewProfile("CHANGE_AUDIT", "IMPLEMENTATION:small", ClaudeModels.HAIKU, "medium", 5, 5, 0, 0, 0.001, eligible)
    private fun grant(max: Double = 0.05) = ReviewSampleGrant("sample", "execution", ReviewRouter.POLICY, ClaudeModels.HAIKU, "low", max)

    @Test
    fun `외부 검증된 같은 목적과 작업군만 자동 채택하고 정책 철회는 캐시를 무효화한다`() {
        val defaultKey = ReviewRouter.policyKey
        ReviewContext.within(ReviewPolicySnapshot(profiles = listOf(profile(true))), "IMPLEMENTATION") {
            ReviewContext.forPurpose("CHANGE_AUDIT") {
                assertEquals(ReviewRouter.Choice(ClaudeModels.HAIKU, "medium"), ReviewRouter.select(prompt, Jev(null)))
                assertNotEquals(defaultKey, ReviewRouter.policyKey)
            }
            ReviewContext.forPurpose("RISK_REVIEW") { assertEquals(ClaudeModels.SONNET, ReviewRouter.select(prompt, Jev(null)).model) }
        }
        ReviewContext.within(ReviewPolicySnapshot(profiles = listOf(profile(false))), "IMPLEMENTATION") {
            ReviewContext.forPurpose("CHANGE_AUDIT") { assertEquals(ClaudeModels.SONNET, ReviewRouter.select(prompt, Jev(null)).model) }
        }
        assertEquals(defaultKey, ReviewRouter.policyKey)
    }

    @Test
    fun `유료 비교는 한 번만 실행하고 기준 응답과 비용 및 원문을 별도로 보존한다`() {
        val usage = mutableListOf<AiUsage>()
        val choices = mutableListOf<Pair<ReviewRouter.Choice, Double?>>()
        UsageScope.collect(usage) {
            ReviewContext.within(ReviewPolicySnapshot(sample = grant()), "IMPLEMENTATION") {
                ReviewContext.forPurpose("CHANGE_AUDIT") {
                    repeat(2) {
                        val result = ReviewRouter.review(prompt, Jev(null)) { _, choice, purpose, evidence, budget ->
                            choices += choice to budget
                            val call = AiUsage(evidence.id, purpose, choice.model, choice.effort, costUsd = 0.01, review = evidence.copy(response = pass))
                            UsageScope.record(call)
                            ClaudeCli.Answer(pass, 0.01, listOf(call))
                        }
                        assertEquals(pass, result.text)
                    }
                }
            }
        }
        assertEquals(3, choices.size)
        assertEquals(listOf(null, 0.05, null), choices.map { it.second })
        val sampled = usage.single { it.review!!.sampleId != null }
        assertEquals(usage.first().review!!.id, sampled.review!!.sampleOf)
        assertEquals(pass, sampled.review.response)
        assertNull(sampled.review.quality)
        assertEquals(0.03, usage.sumOf { it.costUsd!! }, 1e-9)
        val command = ClaudeCli.command(prompt, ClaudeModels.HAIKU, "low", 0.05)
        assertEquals("0.05", command[command.indexOf("--max-budget-usd") + 1])
    }

    @Test
    fun `비교 응답 오류는 기준 검토를 바꾸지 않고 형식 실패로 보존한다`() {
        val usage = mutableListOf<AiUsage>()
        UsageScope.collect(usage) {
            ReviewContext.within(ReviewPolicySnapshot(sample = grant()), "IMPLEMENTATION") {
                ReviewContext.forPurpose("CHANGE_AUDIT") {
                    val result = ReviewRouter.review(prompt, Jev(null)) { _, choice, purpose, evidence, _ ->
                        val text = if (evidence.sampleId == null) pass else "{}"
                        val call = AiUsage(evidence.id, purpose, choice.model, choice.effort, costUsd = 0.01, review = evidence.copy(response = text))
                        UsageScope.record(call)
                        ClaudeCli.Answer(text, 0.01, listOf(call))
                    }
                    assertEquals(pass, result.text)
                }
            }
        }
        assertFalse(usage.single { it.review!!.sampleId != null }.review!!.quality!!.passed)
        assertNull(usage.first().review!!.quality)
    }

    @Test
    fun `예산 부족이나 저장된 표본 이력이 있으면 비교 모델을 호출하지 않는다`() {
        for (alreadySampled in listOf(false, true)) {
            var calls = 0
            ReviewContext.within(ReviewPolicySnapshot(sample = grant(0.000001)), "IMPLEMENTATION", alreadySampled) {
                ReviewContext.forPurpose("CHANGE_AUDIT") {
                    ReviewRouter.review(prompt, Jev(null)) { _, _, _, _, _ -> calls++; ClaudeCli.Answer(pass, 0.01) }
                }
            }
            assertEquals(1, calls)
        }
    }
}
