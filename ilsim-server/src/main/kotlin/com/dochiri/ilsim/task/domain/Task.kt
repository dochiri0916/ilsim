package com.dochiri.ilsim.task.domain

import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import com.dochiri.ilsim.task.domain.vo.Branch
import com.dochiri.ilsim.task.domain.vo.Feature
import com.dochiri.ilsim.task.domain.vo.ProjectId
import com.dochiri.ilsim.task.domain.vo.Requirement
import com.dochiri.ilsim.task.domain.vo.RunCount
import com.dochiri.ilsim.task.domain.vo.RunnerId
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.vo.TaskId
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import java.time.Instant

class Task private constructor(
    val id: TaskId,
    val createdAt: Instant,
    val projectId: ProjectId,
    val feature: Feature,
    val requirement: Requirement,
    val status: TaskStatus,
    val runnerId: RunnerId?,
    val branch: Branch?,
    val runs: RunCount,
    val stages: List<StageState>,
    val baseBranch: Branch? = null,
    val agent: String = RUNNER,
) {
    fun current(): StageState? = stages.firstOrNull { it.status != StageStatus.PASSED }

    fun stage(stage: Stage): StageState = stages.first { it.stage == stage }

    fun costUsd(): Double = stages.sumOf { it.costUsd }

    fun costByModel(): Map<String, Double> =
        stages.flatMap { it.costByModel.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

    fun isRunnable(): Boolean = status == TaskStatus.ACTIVE && current()?.status == StageStatus.PENDING

    fun runsOnRunner(): Boolean = agent == RUNNER

    fun hitRunLimit(): Boolean = isRunnable() && runs.value >= MAX_TASK_RUNS

    fun stopForRunLimit(): Task {
        val stage = current() ?: return this
        val failed = stage.fail("작업 전체 실행 횟수 상한($MAX_TASK_RUNS)에 도달해 멈췄습니다")
        return copy(stages = replaceStage(failed), status = TaskStatus.BLOCKED)
    }

    fun assign(runnerId: RunnerId): Task {
        val stage = current()
        if (!isRunnable() || stage == null) throw InvalidTaskStateException("배정할 수 있는 단계가 없습니다")
        return copy(stages = replaceStage(stage.start()), runnerId = runnerId, runs = runs.increment())
    }

    fun updateCurrent(updated: StageState): Task {
        val stage = current()
        if (stage == null || stage.stage != updated.stage) throw InvalidTaskStateException("현재 단계가 아닙니다")
        return copy(stages = replaceStage(updated))
    }

    fun qualityFeedback(executionId: String, passed: Boolean, reason: String): Task {
        if (reason.isBlank()) throw InvalidTaskRequestException("품질 평가 근거가 필요합니다")
        val stage = stages.firstOrNull { s -> s.executions.any { it.id == executionId && it.completed } }
            ?: throw InvalidTaskRequestException("완료된 실행을 찾을 수 없습니다")
        return copy(stages = replaceStage(stage.recordQualityFeedback(executionId, passed, reason.trim())))
    }

    fun reviewFeedback(usageId: String, quality: ReviewQuality): Task {
        quality.validate()
        val stage = stages.firstOrNull { s -> s.usage.any { it.id == usageId && it.review != null } }
            ?: throw InvalidTaskRequestException("검토 호출을 찾을 수 없습니다")
        val reviewId = stage.usage.first { it.id == usageId }.review!!.id
        return copy(stages = replaceStage(stage.reviewFeedback(reviewId, quality)))
    }

    fun report(outcome: StageOutcome): Task {
        val stage = if (outcome.executionId != null) stages.firstOrNull { s -> s.executions.any { it.id == outcome.executionId } }
            ?: throw InvalidTaskStateException("알 수 없는 실행 ID입니다") else current()
        if (outcome.executionId != null && stage != null) {
            if (outcome.stage != stage.stage.name) throw InvalidTaskStateException("실행 단계가 다릅니다")
            if (stage.executions.any { it.id == outcome.executionId && it.completed }) return this
            if (stage.executionId != outcome.executionId || stage.status !in setOf(StageStatus.RUNNING, StageStatus.PENDING)) {
                val costed = withCost(stage, outcome).completeExecution(outcome)
                return copy(stages = replaceStage(if (stage.executionId == outcome.executionId) costed.recordSession(outcome.sessionId) else costed))
            }
        }
        if (stage != null && stage.status != StageStatus.RUNNING && stage.error == STOPPED_MESSAGE) {
            return copy(stages = replaceStage(withCost(stage, outcome).recordSession(outcome.sessionId)))
        }
        if (stage == null || (stage.status != StageStatus.RUNNING && !(outcome.executionId != null && stage.status == StageStatus.PENDING))) throw InvalidTaskStateException("실행 중인 단계가 없습니다")
        val withBranch = copy(branch = outcome.branch?.takeIf { it.isNotBlank() }?.let { Branch.of(it) } ?: branch)
        val costed = withCost(stage, outcome).completeExecution(outcome)
        val withSession = costed.recordSession(outcome.sessionId)
        val recorded = withSession.record(outcome.diff, outcome.error, outcome.validation)
        return when {
            outcome.passed && outcome.answered && stage.stage == Stage.REQUIREMENTS -> withBranch
                .copy(stages = withBranch.replaceStage(recorded.pass(emptyMap(), awaitApproval = false)), status = TaskStatus.COMPLETED)
            outcome.passed -> withBranch
                .copy(stages = withBranch.replaceStage(recorded.pass(outcome.artifacts.orEmpty(), awaitApproval = stage.stage.approval)))
                .completedIfDone()
            outcome.retryable && stage.attempt.value < MAX_ATTEMPTS ->
                withBranch.copy(stages = replaceStage(recorded.scheduleRetry(outcome.error, MAX_ATTEMPTS)))
            else -> withBranch.copy(stages = replaceStage(recorded.fail()), status = TaskStatus.BLOCKED)
        }
    }

    fun approve(): Task {
        val stage = waitingStage()
        return copy(stages = replaceStage(stage.approve())).completedIfDone()
    }

    fun message(text: String?): Task {
        val stage = waitingStage()
        if (stage.stage != Stage.REQUIREMENTS) throw InvalidTaskStateException("요구사항 단계에서만 대화할 수 있습니다")
        if (text.isNullOrBlank()) throw InvalidTaskRequestException("메시지가 비어 있습니다")
        if (stage.messages.size >= MAX_MESSAGES) throw InvalidTaskStateException("대화는 최대 ${MAX_MESSAGES}번까지 할 수 있습니다. 승인하거나 거절해 새로 시작하세요")
        return copy(stages = replaceStage(stage.converse(text.trim())), runs = RunCount.ZERO)
    }

    fun reject(feedback: String?): Task {
        val stage = waitingStage()
        if (feedback.isNullOrBlank()) throw InvalidTaskRequestException("거절 사유가 필요합니다")
        return copy(stages = replaceStage(stage.restart(feedback)), runs = RunCount.ZERO)
    }

    fun retry(instruction: String? = null): Task {
        val stage = current()
        if (status != TaskStatus.BLOCKED || stage == null) throw InvalidTaskStateException("차단된 작업이 아닙니다")
        val reason = if (stage.error == STOPPED_MESSAGE) stage.feedback else stage.error ?: stage.feedback
        val text = instruction?.trim()?.takeIf { it.isNotEmpty() }
        val next = if (text == null) stage.restart(reason) else stage.continueWith(text, reason)
        return copy(stages = replaceStage(next), runs = RunCount.ZERO, status = TaskStatus.ACTIVE)
    }

    fun reopen(target: Stage, feedback: String?): Task {
        if (feedback.isNullOrBlank()) throw InvalidTaskRequestException("되돌리는 사유가 필요합니다")
        if (target.approval) throw InvalidTaskStateException("승인 단계는 되돌릴 수 없습니다")
        val stage = current()
        if (stage == null || target.ordinal >= stage.stage.ordinal || stage(target).status != StageStatus.PASSED) {
            throw InvalidTaskStateException("진행 중인 단계보다 앞의 통과한 코드 단계만 되돌릴 수 있습니다")
        }
        val reopened = stages.map {
            when {
                it.stage == target -> it.restart(feedback.trim())
                it.stage.ordinal > target.ordinal -> it.restart(null)
                else -> it
            }
        }
        return copy(stages = reopened, runs = RunCount.ZERO, status = TaskStatus.ACTIVE)
    }

    fun stop(): Task {
        val stage = current()
        if (status != TaskStatus.ACTIVE || stage == null || stage.status !in STOPPABLE) {
            throw InvalidTaskStateException("실행 중이거나 대기 중인 단계가 없습니다")
        }
        return copy(stages = replaceStage(stage.fail(STOPPED_MESSAGE)), status = TaskStatus.BLOCKED)
    }

    fun isRunning(): Boolean = current()?.status == StageStatus.RUNNING

    fun recoverAfterRestart(): Task = copy(stages = stages.map { it.recoverInterrupted() })

    private fun withCost(stage: StageState, outcome: StageOutcome): StageState {
        val recorded = stage.recordDecisions(outcome.decisions)
        if (outcome.usage.isNotEmpty()) return recorded.account(outcome.usage.map { it.copy(executionId = it.executionId ?: outcome.executionId) })
        return listOf(
        outcome.costUsd to (outcome.model ?: UNKNOWN_MODEL),
        outcome.reviewCostUsd to (outcome.reviewModel ?: UNKNOWN_MODEL),
    ).fold(recorded) { state, (usd, model) ->
        if (usd != null && usd.isFinite() && usd > 0) state.addCost(usd, model) else state
    }
    }

    private fun waitingStage(): StageState {
        val stage = current()
        if (stage == null || stage.status != StageStatus.WAITING_APPROVAL) throw InvalidTaskStateException("승인 대기 중인 단계가 없습니다")
        return stage
    }

    private fun replaceStage(updated: StageState): List<StageState> =
        stages.map { if (it.stage == updated.stage) updated else it }

    private fun completedIfDone(): Task = if (current() == null) copy(status = TaskStatus.COMPLETED) else this

    private fun copy(
        status: TaskStatus = this.status,
        runnerId: RunnerId? = this.runnerId,
        branch: Branch? = this.branch,
        runs: RunCount = this.runs,
        stages: List<StageState> = this.stages,
    ): Task = Task(id, createdAt, projectId, feature, requirement, status, runnerId, branch, runs, stages, baseBranch, agent)

    companion object {
        const val RUNNER: String = "runner"

        const val CLAUDE_APP: String = "claude-app"

        private val AGENTS = setOf(RUNNER, CLAUDE_APP)

        const val MAX_ATTEMPTS: Int = 3

        const val MAX_MESSAGES: Int = 10

        const val UNKNOWN_MODEL: String = "unknown"

        const val STOPPED_MESSAGE: String = "사용자가 중지했습니다"

        private val STOPPABLE = setOf(StageStatus.PENDING, StageStatus.RUNNING)

        const val MAX_TASK_RUNS: Int = 12

        fun create(id: TaskId, now: Instant, projectId: ProjectId, feature: String?, requirement: String?, baseBranch: Branch? = null, agent: String = RUNNER): Task {
            if (agent !in AGENTS) throw InvalidTaskRequestException("알 수 없는 실행 주체: $agent")
            return Task(
                id = id,
                createdAt = now,
                projectId = projectId,
                feature = if (feature.isNullOrBlank()) Feature.defaultFor(id) else Feature.of(feature),
                requirement = Requirement.of(requirement),
                status = TaskStatus.ACTIVE,
                runnerId = null,
                branch = null,
                runs = RunCount.ZERO,
                stages = Stage.entries.map { StageState.initial(it) },
                baseBranch = baseBranch,
                agent = agent,
            )
        }

        fun restore(
            id: TaskId,
            createdAt: Instant,
            projectId: ProjectId,
            feature: Feature,
            requirement: Requirement,
            status: TaskStatus,
            runnerId: RunnerId?,
            branch: Branch?,
            runs: RunCount,
            stages: List<StageState>,
            baseBranch: Branch? = null,
            agent: String = RUNNER,
        ): Task = Task(id, createdAt, projectId, feature, requirement, status, runnerId, branch, runs, stages, baseBranch, agent)
    }
}
