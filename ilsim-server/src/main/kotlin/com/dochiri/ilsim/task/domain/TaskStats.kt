package com.dochiri.ilsim.task.domain

import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.vo.TaskStatus

data class ModelUsage(val stages: Int, val costUsd: Double)

data class StageUsage(val stage: Stage, val stages: Int, val averageAttempts: Double, val costUsd: Double)

data class CallUsage(val calls: Int, val costUsd: Double, val unknownCalls: Int, val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long, val cacheWriteTokens: Long, val toolCalls: Int, val questions: Int)

data class CombinationUsage(val model: String, val effort: String, val stage: String, val executions: Int, val successRate: Double?, val costUsd: Double, val unknownCalls: Int)

data class Stats(
    val tasks: Int,
    val costUsd: Double,
    val byModel: Map<String, ModelUsage>,
    val byStage: List<StageUsage>,
    val escalatedStages: Int,
    val unknownCalls: Int = 0,
    val costIsLowerBound: Boolean = false,
    val byPurpose: Map<String, CallUsage> = emptyMap(),
    val byCombination: List<CombinationUsage> = emptyList(),
    val analysisCostUsd: Double = 0.0,
    val decisionsByHandler: Map<String, Int> = emptyMap(),
    val runningExecutions: Int = 0,
    val legacyCostUsd: Double = 0.0,
    val costIsUnverified: Boolean = false,
    val reviewProfiles: List<ReviewProfile> = emptyList(),
    val reviewSamplesReserved: Int = 0,
    val reviewSampleBudgetReservedUsd: Double = 0.0,
    val completedTasks: Int = 0,
) {
    companion object {
        fun of(tasks: Collection<Task>, projectUsage: List<AiUsage> = emptyList()): Stats {
            val stages = tasks.flatMap { it.stages }.filter { it.costByModel.isNotEmpty() || it.usage.isNotEmpty() || it.executions.any { e -> e.completed } || it.status != StageStatus.PENDING }
            val byModel = stages.flatMap { s -> s.costByModel.map { (model, usd) -> model to usd } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, costs) -> ModelUsage(costs.size, costs.sum()) }
            val byStage = Stage.entries.mapNotNull { stage ->
                val used = stages.filter { it.stage == stage }
                if (used.isEmpty()) null
                else StageUsage(stage, used.size, used.map { it.attempt.value }.average(), used.sumOf { it.costUsd })
            }
            val calls = stages.flatMap { it.usage } + projectUsage
            val unknown = calls.count { it.chargedUsd == null }
            val purpose = calls.groupBy { it.purpose }.mapValues { (_, group) -> CallUsage(group.size, group.sumOf { it.chargedUsd ?: 0.0 }, group.count { it.chargedUsd == null },
                group.sumOf { it.countedInputTokens }, group.sumOf { it.countedOutputTokens }, group.sumOf { it.countedCacheReadTokens }, group.sumOf { it.countedCacheWriteTokens },
                group.sumOf { it.toolCalls }, group.sumOf { it.questions }) }
            val combinations = stages.flatMap { s -> s.usage.map { Triple(s, it.model, it.effort) } }.distinct().map { (s, model, effort) ->
                val group = s.usage.filter { it.model == model && it.effort == effort }
                val executions = s.executions.filter { it.completed && it.model == model && it.effort == effort }
                CombinationUsage(model, effort, s.stage.name, executions.size, executions.takeIf { it.isNotEmpty() }?.let { e -> e.count { it.passed }.toDouble() / e.size },
                    group.sumOf { it.chargedUsd ?: 0.0 }, group.count { it.chargedUsd == null })
            }.groupBy { Triple(it.model, it.effort, it.stage) }.map { (_, group) ->
                val first = group.first()
                val count = group.sumOf { it.executions }
                first.copy(executions = count, successRate = if (count == 0) null else group.sumOf { (it.successRate ?: 0.0) * it.executions } / count,
                    costUsd = group.sumOf { it.costUsd }, unknownCalls = group.sumOf { it.unknownCalls })
            }
            val escalated = stages.count { s -> s.executions.filter { e -> e.completed && (s.usage.isEmpty() || s.usage.any { u -> u.purpose == "WRITE" && u.executionId == e.id }) }.zipWithNext().any { (a, b) ->
                fun tier(model: String) = ModelTier.of(model)?.ordinal ?: -1
                tier(a.model) >= 0 && tier(b.model) > tier(a.model)
            } }
            val analysisCost = projectUsage.sumOf { it.chargedUsd ?: 0.0 }
            val models = byModel.toMutableMap()
            projectUsage.groupBy { it.model }.forEach { (model, group) ->
                val old = models[model] ?: ModelUsage(0, 0.0)
                models[model] = old.copy(costUsd = old.costUsd + group.sumOf { it.chargedUsd ?: 0.0 })
            }
            val running = stages.count { it.status == StageStatus.RUNNING }
            val legacyCost = stages.sumOf { s -> (s.costUsd - s.usage.sumOf { it.chargedUsd ?: 0.0 }).coerceAtLeast(0.0) }.takeIf { it > 1e-9 } ?: 0.0
            return Stats(tasks.size, tasks.sumOf { it.costUsd() } + analysisCost, models, byStage, escalated, unknown, legacyCost == 0.0 && (unknown > 0 || running > 0), purpose, combinations, analysisCost,
                stages.flatMap { it.decisions }.groupingBy { it.handler }.eachCount(), running, legacyCost, legacyCost > 0.0,
                completedTasks = tasks.count { it.status == TaskStatus.COMPLETED })
        }
    }
}
