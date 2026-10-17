package com.dochiri.ilsim.task.application.usecase

import com.dochiri.ilsim.task.application.port.out.ComplexityAssessor
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.RoutingPolicy
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task

/** 고른 모델 등급과, 선택 이력을 반영한 새 단계 상태. */
data class RouteDecision(val tier: ModelTier, val state: StageState)

/**
 * 단계 실행에 쓸 모델 등급을 고른다. 같은 단계의 평가는 한 번만 하고 재시도에서는 저장된 값을 쓴다.
 * 평가를 쓸 수 없으면 규칙으로 되돌아간다. 단계 상태는 바꾸지 않고 새 상태를 돌려준다.
 */
class ModelSelector(
    private val assessor: ComplexityAssessor,
    private val minimum: ModelTier,
) {
    fun select(task: Task, state: StageState, attempt: Int): RouteDecision {
        val assessment = state.cachedAssessment() ?: assessor.assess(task, state, attempt)
        if (assessment == null) {
            val tier = RoutingPolicy.fallback(attempt)
            val reason = if (assessor.isAvailable()) "JEV 평가 실패" else "JEV 키 없음"
            return RouteDecision(tier, state.recordFallbackRoute(tier, attempt, reason))
        }
        val tier = RoutingPolicy.choose(assessment, attempt, minimum)
        return RouteDecision(tier, state.recordAssessedRoute(assessment, tier, attempt))
    }
}
