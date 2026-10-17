package com.dochiri.ilsim.runner.jev

import com.dochiri.ilsim.runner.architecture.Violation
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskRules
import io.github.gudcks0305.jev.NoulQuestion
import java.nio.file.Path

/**
 * 위험 후보를 코드 발췌와 함께 JEV 에 물어 실제 위험일 확률을 얻는다. Claude 보다 훨씬 싸서 먼저 걸러 내는 용도이며,
 * 확실하지 않은 후보만 Claude 가 이어서 본다(RiskReviewer). 한 번 실행에 부르는 횟수는 [maxCalls] 로 제한한다.
 */
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
}
