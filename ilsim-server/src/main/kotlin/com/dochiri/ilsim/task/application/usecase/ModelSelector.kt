package com.dochiri.ilsim.task.application.usecase

import com.dochiri.ilsim.task.application.port.out.ComplexityAssessor
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.RoutingPolicy
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task

/**
 * 단계 실행에 쓸 모델 등급을 고른다. 같은 단계의 평가는 한 번만 하고 재시도에서는 저장된 값을 쓴다.
 * 평가를 쓸 수 없으면 규칙으로 되돌아간다.
 */
class ModelSelector(
    private val assessor: ComplexityAssessor,
    private val minimum: ModelTier,
) {
    fun select(task: Task, state: StageState, attempt: Int): ModelTier {
        val assessment = state.cachedAssessment() ?: assessor.assess(task, state, attempt)
        if (assessment == null) {
            val tier = RoutingPolicy.fallback(attempt)
            state.recordFallbackRoute(tier, attempt, if (assessor.isAvailable()) "JEV 평가 실패" else "JEV 키 없음")
            return tier
        }
        val tier = RoutingPolicy.choose(assessment, attempt, minimum)
        state.recordAssessedRoute(assessment, tier, attempt)
        return tier
    }
}
