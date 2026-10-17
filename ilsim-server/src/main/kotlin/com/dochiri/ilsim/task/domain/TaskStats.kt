package com.dochiri.ilsim.task.domain

import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus

/**
 * 모든 작업을 가로지르는 모델 사용 집계. 단계마다 쓴 모델별 비용이 이미 있으므로 새로 저장하지 않고 계산한다.
 * [ModelUsage.stages] 는 그 모델이 한 번이라도 일한 단계 수, [Stats.escalatedStages] 는 둘 이상의 모델이 거쳐 간 단계 수(모델 승격이 있었던 단계)다.
 */
data class ModelUsage(val stages: Int, val costUsd: Double)

data class StageUsage(val stage: Stage, val stages: Int, val averageAttempts: Double, val costUsd: Double)

data class Stats(
    val tasks: Int,
    val costUsd: Double,
    val byModel: Map<String, ModelUsage>,
    val byStage: List<StageUsage>,
    val escalatedStages: Int,
) {
    companion object {
        fun of(tasks: Collection<Task>): Stats {
            val stages = tasks.flatMap { it.stages }.filter { it.costByModel.isNotEmpty() || it.status != StageStatus.PENDING }
            val byModel = stages.flatMap { s -> s.costByModel.map { (model, usd) -> model to usd } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, costs) -> ModelUsage(costs.size, costs.sum()) }
            val byStage = Stage.entries.mapNotNull { stage ->
                val used = stages.filter { it.stage == stage }
                if (used.isEmpty()) null
                else StageUsage(stage, used.size, used.map { it.attempt.value }.average(), used.sumOf { it.costUsd })
            }
            return Stats(tasks.size, tasks.sumOf { it.costUsd() }, byModel, byStage, stages.count { it.costByModel.size > 1 })
        }
    }
}
