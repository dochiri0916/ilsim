package com.dochiri.ilsim.task.domain

import kotlin.math.max
import kotlin.math.min

/** 작업 난이도(0~3)와 변경 위험도(0~2)의 평가 결과. */
data class Assessment(val complexity: Double, val risk: Double, val candidates: List<CandidateAssessment> = emptyList())

data class CandidateAssessment(val model: String, val effort: String, val success: Double, val uncertainty: Double = 0.0)

/** 모델 선택 정책. 평가는 판단 근거일 뿐이고 최종 등급은 이 정책이 정한다. */
object RoutingPolicy {

    /** 기존 호출자의 난이도 기반 최소 등급. 재시도 횟수만으로 승격하지 않는다. */
    fun choose(assessment: Assessment, attempt: Int, minimum: ModelTier): ModelTier {
        var tier = 0
        if (assessment.complexity >= 1.5 || assessment.risk >= 0.8) tier = 1
        if (assessment.complexity >= 2.5 || assessment.risk >= 1.5) tier = 2
        tier = max(tier, minimum.ordinal)
        return ModelTier.entries[tier]
    }

    /** 평가 장애는 모델 능력 부족의 근거가 아니다. */
    fun fallback(attempt: Int): ModelTier = ModelTier.SONNET
}
