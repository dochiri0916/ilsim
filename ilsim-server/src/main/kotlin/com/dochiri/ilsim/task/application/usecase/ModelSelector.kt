package com.dochiri.ilsim.task.application.usecase

import com.dochiri.ilsim.task.application.port.out.ComplexityAssessor
import com.dochiri.ilsim.task.domain.*
import com.dochiri.ilsim.task.domain.vo.StageStatus
import java.security.MessageDigest

data class RouteDecision(val tier: ModelTier, val state: StageState, val effort: String = "medium")

class ModelSelector(
    private val assessor: ComplexityAssessor,
    private val minimum: ModelTier,
    val policy: CostRoutingPolicy = CostRoutingPolicy(),
) {
    private var history: List<ExecutionRecord> = emptyList()

    @Volatile var adjustments: RoutingAdjustments = RoutingAdjustments()

    fun assessmentAvailable(): Boolean = assessor.isAvailable()

    fun learn(tasks: Collection<Task>) {
        history = tasks.sortedBy { it.createdAt }.flatMap { task -> task.stages.flatMap { stage ->
            stage.executions.filter { execution -> execution.completed && (!execution.passed || stage.status == StageStatus.PASSED &&
                (stage.stage.approval || execution.validation["tests"] in setOf("PASSED", "RED") && execution.validation["ai-audit"] == "PASSED")) }
        } }
    }

    fun select(task: Task, state: StageState, attempt: Int, fallbackEffort: String? = null): RouteDecision {
        val evidence = listOf(task.requirement.value, state.stage.name, state.messages.joinToString("\n"), state.feedback.orEmpty(), state.diff.orEmpty(),
            task.stages.joinToString { it.artifactHash.orEmpty() }, policy.toString(), CostRoutingPolicy.SUPPORT_VERSION).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(evidence.toByteArray()).joinToString("") { "%02x".format(it) }
        val assessment = state.cachedAssessment()?.takeIf { state.routing["contextHash"] == hash } ?: assessor.assess(task, state, attempt)
        val failed = state.executions.filter { it.completed && !it.passed && it.failureKind !in CostRoutingPolicy.ENVIRONMENT_FAILURES }
            .map { "${it.model}/${it.effort}" }.toSet()
        val a = assessment ?: Assessment(2.0, 1.0)
        val workload = "${state.stage.name}:${a.complexity.toInt()}:${a.risk.toInt()}"
        val selected = policy.choose(a, minimum, evidence.length, history, workload, failed, adjustments)
        val route = if (assessment == null && fallbackEffort != null) {
            val option = selected.option.copy(effort = ReasoningEffort.parse(fallbackEffort))
            selected.copy(option = option, expectedCostUsd = CostRoutingPolicy.estimate(option, evidence.length) +
                (1 - selected.success) * CostRoutingPolicy.estimate(ModelOption(ModelTier.OPUS, "high"), evidence.length))
        } else selected
        val reason = if (assessment == null) "${route.reason}, ${if (assessor.isAvailable()) "JEV 평가 실패" else "JEV 키 없음"}" else route.reason
        return RouteDecision(route.option.tier, state.account(assessor.drainUsage()).recordCostRoute(assessment, route.copy(reason = reason), attempt, hash, policy.version), route.option.effort)
    }
}
