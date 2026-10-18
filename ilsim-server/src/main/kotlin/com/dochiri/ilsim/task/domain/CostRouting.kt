package com.dochiri.ilsim.task.domain

import kotlin.math.max

data class ModelOption(val tier: ModelTier, val effort: String) {
    val key: String get() = "${tier.modelId}/$effort"
}

data class CostRoute(val option: ModelOption, val expectedCostUsd: Double, val success: Double, val reason: String)

/** 비용·품질 정책 버전은 실행 이력에 남는다. 이전 버전 선택으로 보정 적용을 되돌릴 수 있다. */
data class CostRoutingPolicy(
    val version: String = "cost-v1",
    val minimumSamples: Int = 5,
    val historyLimit: Int = 100,
    val qualityThreshold: Double = 0.9,
    val calibrate: Boolean = true,
    val allowUnverified: Boolean = false,
    val comparisonEvery: Int = 5,
    val comparisonCandidates: Int = 15,
) {
    fun choose(assessment: Assessment, minimum: ModelTier, chars: Int, history: List<ExecutionRecord>, workload: String, failed: Set<String> = emptySet()): CostRoute {
        // 저장된 실행 이력을 주기적으로 비교한다. 비교 자체에는 추가 모델 호출이 없다.
        val cutoff = history.size / comparisonEvery.coerceAtLeast(1) * comparisonEvery.coerceAtLeast(1)
        val recent = history.take(cutoff).filter { it.workload == workload && it.completed && it.failureKind !in ENVIRONMENT_FAILURES }.takeLast(historyLimit)
        val control = recent.filter { sample -> options.firstOrNull { it.tier.modelId == sample.model && it.effort == sample.effort }?.let { conservative(assessment, it) } == true }
        val options = options.filter { it.tier.ordinal >= minimum.ordinal }.sortedBy { estimate(it, chars) }.take(comparisonCandidates.coerceAtLeast(1))
        val candidates = options.mapNotNull { option ->
            if (option.key in failed) return@mapNotNull null
            val evaluation = assessment.candidates.firstOrNull { it.model == option.tier.modelId && it.effort == option.effort }
            if (evaluation != null && (!evaluation.success.isFinite() || evaluation.success !in 0.0..1.0 || !evaluation.uncertainty.isFinite() || evaluation.uncertainty !in 0.0..1.0)) return@mapNotNull null
            val conservative = conservative(assessment, option)
            // 근거가 없는 조합은 검증된 보수적 범위만 허용한다.
            var success = evaluation?.let { (it.success - it.uncertainty).coerceIn(0.0, 1.0) } ?: if (conservative) 0.95 else 0.0
            val samples = recent.filter { it.model == option.tier.modelId && it.effort == option.effort && it.policyVersion == version }
            if (!allowUnverified && !conservative && samples.count { it.passed } < minimumSamples) return@mapNotNull null
            if (!allowUnverified && !conservative && (control.size < minimumSamples ||
                    samples.count { it.passed }.toDouble() / samples.size < control.count { it.passed }.toDouble() / control.size)) return@mapNotNull null
            if (calibrate && samples.size >= minimumSamples) {
                val observed = (samples.count { it.passed } + success * 10) / (samples.size + 10.0)
                success = observed
            }
            if (success < qualityThreshold) return@mapNotNull null
            val measured = samples.mapNotNull { it.actualCostUsd }.takeIf { it.size >= minimumSamples }?.average()
            val call = measured ?: estimate(option, chars)
            val fallback = estimate(ModelOption(ModelTier.OPUS, "high"), chars)
            CostRoute(option, call + (1 - success) * fallback, success, if (measured == null) "JEV·지원 조합·토큰 단가 추정" else "최근 검증 결과·실측 완료 비용 보정")
        }
        return candidates.minByOrNull { it.expectedCostUsd } ?: run {
            val option = ModelOption(if (assessment.risk >= 1.5 || assessment.complexity >= 2.5) ModelTier.OPUS else maxOf(minimum, ModelTier.SONNET), "high")
            CostRoute(option, estimate(option, chars), 0.95, "불확실한 후보 제외 후 보수적 fallback")
        }
    }

    private fun conservative(a: Assessment, o: ModelOption): Boolean = when {
        a.risk >= 1.5 || a.complexity >= 2.5 -> o.tier == ModelTier.OPUS && o.effort in setOf("high", "xhigh", "max")
        else -> o.tier.ordinal >= ModelTier.SONNET.ordinal && o.effort in setOf("medium", "high", "xhigh", "max")
    }

    companion object {
        val ENVIRONMENT_FAILURES = setOf("ENVIRONMENT", "NETWORK", "AUTH", "STOPPED", "SESSION_MISSING")
        val efforts = listOf("low", "medium", "high", "xhigh", "max")
        // Claude Code 2.1.302 / 5.5 세대 지원 정보. 지원 정보 변경 시 캐시와 정책 버전도 바꾼다.
        val options = ModelTier.entries.flatMap { tier -> efforts.map { ModelOption(tier, it) } }
        const val SUPPORT_VERSION = "claude-5.5-cli-2.1.302-v1"

        fun estimate(option: ModelOption, chars: Int): Double {
            val input = max(1000.0, chars / 3.0)
            val output = 1500.0 * when (option.effort) { "low" -> 0.65; "medium" -> 1.0; "high" -> 1.7; "xhigh" -> 2.5; else -> 4.0 }
            val rate = when (option.tier) { ModelTier.HAIKU -> if (input <= 100_000) 0.1 else 0.5; ModelTier.SONNET -> 2.0; ModelTier.OPUS -> 4.0 }
            return (input + output * 5) * rate / 1_000_000
        }
    }
}
