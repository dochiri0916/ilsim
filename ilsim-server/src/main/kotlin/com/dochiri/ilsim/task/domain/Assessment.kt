package com.dochiri.ilsim.task.domain

import kotlin.math.max
import kotlin.math.min

/** 작업 난이도(0~3)와 변경 위험도(0~2)의 평가 결과. */
data class Assessment(val complexity: Double, val risk: Double)

/** 모델 선택 정책. 평가는 판단 근거일 뿐이고 최종 등급은 이 정책이 정한다. */
object RoutingPolicy {

    /** 평가로 기본 등급을 정하고, 최소 모델 아래로는 내리지 않으며, 재시도마다 한 단계씩 올린다. */
    fun choose(assessment: Assessment, attempt: Int, minimum: ModelTier): ModelTier {
        var tier = 0
        if (assessment.complexity >= 1.5 || assessment.risk >= 0.8) tier = 1
        if (assessment.complexity >= 2.5 || assessment.risk >= 1.5) tier = 2
        tier = max(tier, minimum.ordinal)
        tier = min(ModelTier.entries.size - 1, tier + max(0, attempt - 1))
        return ModelTier.entries[tier]
    }

    /** 평가를 쓸 수 없을 때의 규칙: 마지막 시도부터는 최상위 모델. */
    fun fallback(attempt: Int): ModelTier = if (attempt >= 3) ModelTier.OPUS else ModelTier.SONNET
}
