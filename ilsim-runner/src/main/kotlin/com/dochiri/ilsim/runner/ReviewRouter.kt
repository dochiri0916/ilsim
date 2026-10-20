package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.jev.Jev
import io.github.gudcks0305.jev.NoulQuestion
import com.dochiri.ilsim.runner.audit.ChangeAudit
import java.util.UUID

object ReviewRouter {
    const val POLICY = "review-cost-v2-support-5.5"
    val policyKey: String get() = "${ReviewContext.policy.version}/${ReviewCache.digest(ReviewContext.policy.profiles.toString())}/${System.getenv("ILSIM_JEV_POLICY") ?: "jev-v2"}/rules=${System.getenv("ILSIM_JEV_RULE_COMPLETION") == "true"}/review=${System.getenv("ILSIM_REVIEW_ALLOW_UNVERIFIED") == "true"}"
    data class Choice(val model: String, val effort: String) {
        val key get() = "$model/$effort/$POLICY"
    }
    private val options get() = listOf(ClaudeModels.HAIKU, ClaudeModels.SONNET, ClaudeModels.OPUS).flatMap { m ->
        listOf("low", "medium", "high", "xhigh", "max").map { Choice(m, it) }
    }

    fun select(prompt: String, jev: Jev = Jev.fromEnvironment(), allowUnverified: Boolean = System.getenv("ILSIM_REVIEW_ALLOW_UNVERIFIED") == "true"): Choice {
        val fallback = Choice(ClaudeModels.REVIEW, ClaudeModels.EFFORT)
        val profiles = ReviewContext.policy.profiles.filter { it.purpose == ReviewContext.purposeName && it.workload == ReviewContext.workload(prompt.length) }
        val candidates = options.filter { it == fallback || allowUnverified || profiles.any { p -> p.eligible && p.model == it.model && p.effort == it.effort } }
        if (candidates.size == 1) return fallback
        val questions = candidates.mapIndexed { i, c -> "c$i" to NoulQuestion.of("c$i", "입력 근거의 독립 검토를 ${c.model}/${c.effort}가 누락·오탐 증가 없이 수행할 수 있는가? 근거가 부족하면 No.") }.toMap()
        val answers = if (jev.enabled) jev.probabilities(mapOf("policy" to policyKey, "reviewInput" to prompt), questions) else null
        return candidates.mapIndexedNotNull { i, c ->
            val profile = profiles.firstOrNull { it.model == c.model && it.effort == c.effort }
            val observed = profile?.takeIf { it.eligible && it.samples > 0 }?.let { it.passed.toDouble() / it.samples }
            val p = (answers?.get("c$i")?.takeIf { it.isFinite() && it in 0.97..1.0 }
                ?: if (answers == null && !allowUnverified) observed ?: if (c == fallback) 0.97 else null else null)
                ?: return@mapIndexedNotNull null
            val success = observed?.let { minOf(it, p) } ?: p
            val measured = profile?.meanCostUsd?.takeIf { it.isFinite() && it >= 0 }
            c to ((measured ?: estimate(c, prompt.length)) + (1 - success) * estimate(Choice(ClaudeModels.OPUS, "high"), prompt.length))
        }.minByOrNull { it.second }?.first ?: fallback
    }

    internal fun estimate(c: Choice, chars: Int): Double {
        val input = maxOf(1000.0, chars / 3.0)
        val rate = when (c.model) { ClaudeModels.HAIKU -> if (input <= 100_000) 0.1 else 0.5; ClaudeModels.SONNET -> 2.0; else -> 4.0 }
        val output = when (c.effort) { "low" -> 975; "medium" -> 1500; "high" -> 2550; "xhigh" -> 3750; else -> 6000 }
        return (input + output * 5) * rate / 1_000_000
    }

    fun askWithCost(prompt: String, model: String): ClaudeCli.Answer {
        return review(prompt)
    }

    internal fun review(prompt: String, jev: Jev = Jev.fromEnvironment(),
        call: (String, Choice, String, ReviewEvidence, Double?) -> ClaudeCli.Answer = { text, choice, purpose, evidence, budget ->
            ClaudeCli.askWithCost(text, choice.model, choice.effort, purpose, evidence, budget)
        }): ClaudeCli.Answer {
        val choice = select(prompt, jev)
        val purpose = ReviewContext.purposeName
        val evidence = ReviewEvidence(UUID.randomUUID().toString(), ReviewContext.workload(prompt.length), ReviewContext.policy.version, ReviewCache.digest(prompt))
        DecisionScope.record(purpose, "AGENT", "독립 검토 ${choice.model}/${choice.effort}; 검증 이력·예상 완료 비용 선택", prompt, ReviewContext.policy.version)
        val answer = call(prompt, choice, purpose, evidence, null)
        val baseline = if (purpose == "CHANGE_AUDIT") try { ChangeAudit.parse(answer.text) } catch (e: Exception) {
            UsageScope.reviewQuality(evidence.id, ReviewQuality(false, reason = "응답 형식 검증 실패: ${e.message}"))
            throw e
        } else return answer
        val grant = ReviewContext.sample() ?: return answer
        val candidate = Choice(grant.model, grant.effort)
        if (choice != Choice(ClaudeModels.SONNET, "medium") || candidate !in options || candidate == choice ||
            grant.policyVersion != ReviewContext.policy.version || estimate(candidate, prompt.length) > grant.maxCostUsd) {
            DecisionScope.record(purpose, "SAMPLE_SKIPPED", "예약된 비교 표본의 기준 모델·지원 조합·입력 예산 조건 불충족", prompt)
            return answer
        }
        val sample = evidence.copy(id = UUID.randomUUID().toString(), sampleId = grant.id, sampleOf = evidence.id)
        try {
            val result = call(prompt, candidate, purpose, sample, grant.maxCostUsd)
            val parsed = try { ChangeAudit.parse(result.text) } catch (e: Exception) {
                UsageScope.reviewQuality(sample.id, ReviewQuality(false, reason = "표본 응답 형식 검증 실패: ${e.message}"))
                throw e
            }
            DecisionScope.record(purpose, "SAMPLE", "${candidate.model}/${candidate.effort} 비교 ${if (parsed == baseline) "일치" else "차이 있음"}; 정확도는 외부 근거로 확인 필요", prompt)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            DecisionScope.record(purpose, "SAMPLE_FAILED", e.message.orEmpty(), prompt)
        }
        return answer
    }
}
