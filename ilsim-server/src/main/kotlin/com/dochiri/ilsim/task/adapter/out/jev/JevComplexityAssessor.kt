package com.dochiri.ilsim.task.adapter.out.jev

import com.dochiri.ilsim.jev.api.JevGateway
import com.dochiri.ilsim.jev.api.JevQuestion
import com.dochiri.ilsim.task.application.port.out.ComplexityAssessor
import com.dochiri.ilsim.task.domain.Assessment
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task

/** JEV 로 요구사항의 난이도와 변경 위험을 점수로 받는다. */
class JevComplexityAssessor(private val jev: JevGateway) : ComplexityAssessor {

    override fun isAvailable(): Boolean = jev.enabled()

    override fun assess(task: Task, state: StageState, attempt: Int): Assessment? {
        if (!jev.enabled()) return null
        val situation = linkedMapOf<String, Any?>(
            "stage" to state.stage.name,
            "attempt" to attempt,
            "requirement" to task.requirement.take(REQUIREMENT_LIMIT),
        )
        state.feedback?.let { situation["previousFailure"] = it.take(FAILURE_LIMIT) }
        val answers = jev.evaluate(situation, mapOf("complexity" to COMPLEXITY, "risk" to RISK)) ?: return null
        val complexity = answers["complexity"]?.score ?: return null
        val risk = answers["risk"]?.score ?: return null
        return Assessment(complexity, risk)
    }

    private companion object {
        const val REQUIREMENT_LIMIT = 4000
        const val FAILURE_LIMIT = 1500

        val COMPLEXITY = JevQuestion.score(
            "요구사항을 구현하는 작업의 난이도를 평가하라.",
            listOf(
                "trivial: 한두 줄 수정",
                "simple: 한 파일 안의 작은 변경",
                "moderate: 여러 파일이나 새 로직",
                "complex: 여러 모듈이나 동시성·트랜잭션·보안과 얽힘",
            ),
        )
        val RISK = JevQuestion.score(
            "이 변경이 기존 동작을 깨뜨릴 위험을 평가하라.",
            listOf(
                "low: 기존 동작에 영향 없음",
                "medium: 일부 기존 동작에 영향",
                "high: 결제·데이터·인증 같은 핵심 흐름에 영향",
            ),
        )
    }
}
