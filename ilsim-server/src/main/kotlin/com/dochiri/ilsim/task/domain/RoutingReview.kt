package com.dochiri.ilsim.task.domain

import java.security.MessageDigest
import java.time.Instant
import kotlin.math.pow

/**
 * 사람이 정한 모델·추론 강도 조정. [enabled]가 false 면 JEV 가 고르지 못하고, [successOffset]은 JEV 가 예측한 성공 가능성에 더한다
 * (예측이 실제보다 낙관적이면 음수). 실행 이력의 정책 버전은 바꾸지 않으므로 보정 표본은 그대로 이어진다.
 */
data class RoutingAdjustment(val model: String, val effort: String, val enabled: Boolean = true, val successOffset: Double = 0.0) {
    val key: String get() = "$model/$effort"

    /** 버전과 무관한 계열 키. 새 버전이 나와도 조정이 그대로 이어진다. */
    val familyKey: String get() = "${ModelTier.of(model)?.label ?: model}/$effort"

    fun normalized(): RoutingAdjustment = copy(successOffset = successOffset.takeIf { it.isFinite() }?.coerceIn(-MAX_OFFSET, MAX_OFFSET) ?: 0.0)

    val isDefault: Boolean get() = enabled && successOffset == 0.0

    companion object {
        const val MAX_OFFSET = 0.3
    }
}

/** 조정 묶음 하나. 바꿀 때마다 새 버전으로 쌓고, 이전 버전을 다시 적용해 되돌린다. */
data class RoutingAdjustments(val items: List<RoutingAdjustment> = emptyList(), val note: String? = null, val createdAt: Instant? = null) {
    private val byKey: Map<String, RoutingAdjustment> = items.associateBy { it.familyKey }

    val version: String get() = if (items.isEmpty()) "none" else MessageDigest.getInstance("SHA-256")
        .digest(items.sortedBy { it.key }.joinToString("\n") { "${it.key}:${it.enabled}:${it.successOffset}" }.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)

    private fun ModelOption.family() = "${tier.label}/$effort"

    fun of(option: ModelOption): RoutingAdjustment? = byKey[option.family()]

    fun allows(option: ModelOption): Boolean = byKey[option.family()]?.enabled != false

    fun adjust(option: ModelOption, success: Double): Double = (success + (byKey[option.family()]?.successOffset ?: 0.0)).coerceIn(0.0, 1.0)

    companion object {
        /** 기본값과 같은 항목은 버리고, 같은 조합이 여러 번 오면 마지막 것을 쓴다. */
        fun of(items: List<RoutingAdjustment>, note: String?, now: Instant): RoutingAdjustments =
            RoutingAdjustments(items.map { it.normalized().let { a -> a.copy(model = ModelTier.of(a.model)?.modelId ?: a.model) } }.associateBy { it.familyKey }.values.filterNot { it.isDefault }.sortedBy { it.key },
                note?.trim()?.take(500)?.ifEmpty { null }, now)
    }
}

/**
 * 완료된 작성 실행 하나에 대한 판정. 같은 작업군(단계·복잡도·위험)의 이력과 비교한다.
 * - OVER: 성공했지만 같은 작업군에서 더 싼 조합이 충분한 표본으로 품질 기준을 넘었다(과한 선택).
 * - UNDER: 실패했고 같은 단계의 다음 시도가 더 높은 조합으로 성공했다(부족한 선택).
 * - FIT: 그 밖의 성공. UNKNOWN: 환경 장애 등 모델 능력과 무관한 실패이거나 판단 근거가 없다.
 */
enum class SelectionVerdict { FIT, OVER, UNDER, UNKNOWN }

data class ExecutionReview(
    val taskId: String, val stage: String, val executionId: String, val model: String, val effort: String, val workload: String,
    val predictedSuccess: Double?, val passed: Boolean, val failureKind: String?, val actualCostUsd: Double?, val expectedCostUsd: Double?,
    val verdict: SelectionVerdict, val cheaperOption: String? = null, val adjustmentVersion: String? = null,
)

/** 모델·추론 강도 하나의 성적. [calibrationGap]은 예측 성공률 − 실제 성공률(양수면 JEV 가 낙관적). */
data class OptionReport(
    val model: String, val effort: String, val executions: Int, val passed: Int, val passRate: Double?,
    val averagePredicted: Double?, val calibrationGap: Double?, val brier: Double?,
    val averageActualCostUsd: Double?, val averageExpectedCostUsd: Double?, val over: Int, val under: Int,
    /** 표본이 충분할 때 권장 보정값. 예측과 실제 차이를 표본 수로 줄여(작은 표본은 덜 믿음) 계산한다. */
    val recommendedOffset: Double?,
)

/** 작업군별로 품질 기준을 넘은 조합 중 실측 비용이 가장 낮은 조합과, 실제로 가장 많이 쓴 조합. */
data class WorkloadReport(val workload: String, val executions: Int, val mostUsed: String?, val cheapestQualified: String?)

data class RoutingReport(
    val options: List<OptionReport>,
    val workloads: List<WorkloadReport>,
    val recent: List<ExecutionReview>,
    val verdicts: Map<SelectionVerdict, Int>,
    val minimumSamples: Int,
    val qualityThreshold: Double,
) {
    companion object {
        /** 비교 자체는 저장된 이력만 쓴다. 추가 AI 호출이 없다. */
        fun of(tasks: Collection<Task>, policy: CostRoutingPolicy, recentLimit: Int = 50): RoutingReport {
            // 같은 계열은 버전과 무관하게 지금 버전 ID 로 묶는다(새 버전이 이전 버전 성적을 이어받는다).
            val reviews = tasks.flatMap { task -> task.stages.flatMap { stage ->
                stage.executions.filter { it.completed }.map { task to stage to it.copy(model = ModelTier.of(it.model)?.modelId ?: it.model) }
            } }
            val countable = reviews.filter { (_, e) -> e.failureKind !in CostRoutingPolicy.ENVIRONMENT_FAILURES }.map { it.second }
            fun qualified(workload: String) = countable.filter { it.workload == workload }.groupBy { "${it.model}/${it.effort}" }
                .filter { (_, g) -> g.size >= policy.minimumSamples && g.count { it.passed }.toDouble() / g.size >= policy.qualityThreshold }
            fun costOf(key: String, group: List<ExecutionRecord>) = group.mapNotNull { it.actualCostUsd }.takeIf { it.isNotEmpty() }?.average()
                ?: CostRoutingPolicy.estimate(optionOf(key) ?: ModelOption(ModelTier.OPUS, "high"), 4000)

            val judged = reviews.map { (pair, e) ->
                val (task, stage) = pair
                val key = "${e.model}/${e.effort}"
                val verdict = when {
                    e.failureKind in CostRoutingPolicy.ENVIRONMENT_FAILURES -> SelectionVerdict.UNKNOWN to null
                    !e.passed -> {
                        val later = stage.executions.dropWhile { it.id != e.id }.drop(1).firstOrNull { it.completed && it.passed }
                            ?.let { it.copy(model = ModelTier.of(it.model)?.modelId ?: it.model) }
                        if (later != null && rank(later) > rank(e)) SelectionVerdict.UNDER to null else SelectionVerdict.UNKNOWN to null
                    }
                    else -> {
                        val own = costOf(key, countable.filter { "${it.model}/${it.effort}" == key && it.workload == e.workload })
                        val cheaper = qualified(e.workload).filterKeys { it != key }.map { (k, g) -> k to costOf(k, g) }.filter { it.second < own * 0.8 }.minByOrNull { it.second }
                        if (cheaper != null) SelectionVerdict.OVER to cheaper.first else SelectionVerdict.FIT to null
                    }
                }
                ExecutionReview(task.id.value, stage.stage.name, e.id, e.model, e.effort, e.workload, e.predictedSuccess, e.passed, e.failureKind,
                    e.actualCostUsd, e.expectedCostUsd, verdict.first, verdict.second, e.adjustmentVersion)
            }
            // 환경 장애는 모델 능력과 무관하므로 성적에서 뺀다.
            val options = judged.filter { it.failureKind !in CostRoutingPolicy.ENVIRONMENT_FAILURES }.groupBy { it.model to it.effort }.map { (k, g) ->
                val passRate = g.count { it.passed }.toDouble() / g.size
                val predicted = g.mapNotNull { it.predictedSuccess }.takeIf { it.isNotEmpty() }
                val gap = predicted?.average()?.minus(passRate)
                val brier = g.filter { it.predictedSuccess != null }.takeIf { it.isNotEmpty() }?.map { (it.predictedSuccess!! - if (it.passed) 1.0 else 0.0).pow(2) }?.average()
                val offset = if (gap != null && g.size >= policy.minimumSamples) (-gap * g.size / (g.size + 10.0)).coerceIn(-RoutingAdjustment.MAX_OFFSET, RoutingAdjustment.MAX_OFFSET) else null
                OptionReport(k.first, k.second, g.size, g.count { it.passed }, passRate, predicted?.average(), gap, brier,
                    g.mapNotNull { it.actualCostUsd }.takeIf { it.isNotEmpty() }?.average(), g.mapNotNull { it.expectedCostUsd }.takeIf { it.isNotEmpty() }?.average(),
                    g.count { it.verdict == SelectionVerdict.OVER }, g.count { it.verdict == SelectionVerdict.UNDER }, offset?.let { Math.round(it * 100) / 100.0 })
            }.sortedWith(compareBy({ optionOf("${it.model}/${it.effort}")?.let(::rankOf) ?: 99 }))
            val workloads = countable.groupBy { it.workload }.map { (w, g) ->
                WorkloadReport(w, g.size, g.groupingBy { "${it.model}/${it.effort}" }.eachCount().maxByOrNull { it.value }?.key,
                    qualified(w).map { (k, group) -> k to costOf(k, group) }.minByOrNull { it.second }?.first)
            }.sortedByDescending { it.executions }
            return RoutingReport(options, workloads, judged.takeLast(recentLimit).reversed(), judged.groupingBy { it.verdict }.eachCount(),
                policy.minimumSamples, policy.qualityThreshold)
        }

        private fun optionOf(key: String): ModelOption? = CostRoutingPolicy.options.firstOrNull { it.key == key }

        private fun rankOf(o: ModelOption): Int = o.tier.ordinal * 10 + CostRoutingPolicy.efforts.indexOf(o.effort)

        private fun rank(e: ExecutionRecord): Int = optionOf("${e.model}/${e.effort}")?.let(::rankOf) ?: -1
    }
}
