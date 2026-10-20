package com.dochiri.ilsim.task.domain

import java.security.MessageDigest
import java.time.Instant
import kotlin.math.pow

data class RoutingAdjustment(val model: String, val effort: String, val enabled: Boolean = true, val successOffset: Double = 0.0) {
    val key: String get() = "$model/$effort"

    val familyKey: String get() = "${ModelTier.of(model)?.label ?: model}/$effort"

    fun normalized(): RoutingAdjustment = copy(successOffset = successOffset.takeIf { it.isFinite() }?.coerceIn(-MAX_OFFSET, MAX_OFFSET) ?: 0.0)

    val isDefault: Boolean get() = enabled && successOffset == 0.0

    companion object {
        const val MAX_OFFSET = 0.3
    }
}

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
        fun of(items: List<RoutingAdjustment>, note: String?, now: Instant): RoutingAdjustments =
            RoutingAdjustments(items.map { it.normalized().let { a -> a.copy(model = ModelTier.of(a.model)?.modelId ?: a.model) } }.associateBy { it.familyKey }.values.filterNot { it.isDefault }.sortedBy { it.key },
                note?.trim()?.take(500)?.ifEmpty { null }, now)
    }
}

enum class SelectionVerdict { FIT, OVER, UNDER, UNKNOWN }

data class ExecutionReview(
    val taskId: String, val stage: String, val executionId: String, val model: String, val effort: String, val workload: String,
    val predictedSuccess: Double?, val passed: Boolean, val failureKind: String?, val actualCostUsd: Double?, val expectedCostUsd: Double?,
    val verdict: SelectionVerdict, val cheaperOption: String? = null, val adjustmentVersion: String? = null,
)

data class OptionReport(
    val model: String, val effort: String, val executions: Int, val passed: Int, val passRate: Double?,
    val averagePredicted: Double?, val calibrationGap: Double?, val brier: Double?,
    val averageActualCostUsd: Double?, val averageExpectedCostUsd: Double?, val over: Int, val under: Int,
    val recommendedOffset: Double?,
)

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
        fun of(tasks: Collection<Task>, policy: CostRoutingPolicy, recentLimit: Int = 50): RoutingReport {
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
