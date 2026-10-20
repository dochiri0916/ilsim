package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskRules
import io.github.gudcks0305.jev.NoulQuestion
import java.nio.file.Path

object JevRiskScreen {
    private const val MAX_CALLS = 20

    private val question = NoulQuestion.of("risk", "Can this risk candidate actually occur in the code excerpt?")
        .withCriteria(
            "Yes, the excerpt shows the risk and no protection (lock, unique constraint, idempotency key, transaction, circuit breaker, outbox) covering it.",
            "No, the excerpt shows an effective protection, or does not show the risk.",
        )

    fun of(jev: Jev, root: Path, maxCalls: Int = MAX_CALLS): (Violation) -> Double? {
        if (!jev.enabled) return { null }
        var calls = 0
        return { v ->
            if (calls >= maxCalls) null
            else {
                calls++
                val state = mapOf(
                    "ruleId" to v.ruleId,
                    "rule" to (RiskRules.byId[v.ruleId]?.title ?: v.ruleId),
                    "finding" to v.message,
                    "code" to RiskReviewer.excerpt(root, v),
                )
                jev.probability(state, question)
            }
        }
    }

    fun batched(jev: Jev, root: Path, findings: List<Violation>, maxCandidates: Int = MAX_CALLS): (Violation) -> Double? {
        val candidates = findings.take(maxCandidates)
        val answers by lazy {
            val state = candidates.mapIndexed { i, v -> mapOf("id" to "r$i", "ruleId" to v.ruleId, "finding" to v.message,
                "code" to RiskReviewer.excerpt(root, v), "remedy" to RiskRules.remedies[v.ruleId].orEmpty()) }
            val questions = candidates.indices.flatMap { i -> listOf(
                "r$i" to NoulQuestion.of("r$i", "후보 r${i}의 위험이 코드 근거에서 확인되는가? 보호 장치를 확인하지 못하면 불확실성을 반영하라.")
                    .withCriteria("위험과 보호 장치 부재가 근거에서 확인됨", "유효한 보호 장치가 근거에서 확인됨"),
                "e$i" to NoulQuestion.of("e$i", "후보 r${i}의 제공된 근거만으로 TRUE 또는 FALSE를 확정할 수 있는가? 호출자·DB·인프라 확인이 더 필요하면 No."),
            ) }.toMap()
            jev.probabilities(mapOf("candidates" to state), questions)
        }
        return { v -> candidates.indexOf(v).takeIf { it >= 0 }?.let { i ->
            if ((answers?.get("e$i") ?: 0.0) >= 0.9) answers?.get("r$i") else null
        } }
    }
}
