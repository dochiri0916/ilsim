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

/**
 * 요구사항 1건과 그 진행 상태. 불변 객체이며 상태를 바꾸는 메서드는 바뀐 새 [Task] 를 돌려준다.
 * 단계 전이 규칙(승인, 재시도, 실행 횟수 상한)은 모두 이 집합체가 지킨다.
 */
class Task private constructor(
    val id: TaskId,
    val createdAt: Instant,
    val projectId: ProjectId,
    val feature: Feature,
    val requirement: Requirement,
    val status: TaskStatus,
    val runnerId: RunnerId?,
    val branch: Branch?,
    /** 사람이 개입(거절·재시도)한 뒤로 Runner 에 배정된 횟수. 자동 재시도가 끝없이 이어지지 않게 하는 전체 상한에 쓴다. */
    val runs: RunCount,
    val stages: List<StageState>,
    /** 이어서 지시한 작업이면 직전 작업의 브랜치. Runner 가 HEAD 대신 이 브랜치에서 worktree 를 만든다. */
    val baseBranch: Branch? = null,
) {
    /** 아직 통과하지 못한 첫 단계. 모두 통과했으면 null. */
    fun current(): StageState? = stages.firstOrNull { it.status != StageStatus.PASSED }

    fun stage(stage: Stage): StageState = stages.first { it.stage == stage }

    /** 작업 전체에서 쓴 Claude 비용(USD)과 모델별 내역. */
    fun costUsd(): Double = stages.sumOf { it.costUsd }

    fun costByModel(): Map<String, Double> =
        stages.flatMap { it.costByModel.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

    /** Runner 에 배정할 수 있는 상태인지. */
    fun isRunnable(): Boolean = status == TaskStatus.ACTIVE && current()?.status == StageStatus.PENDING

    fun hitRunLimit(): Boolean = isRunnable() && runs.value >= MAX_TASK_RUNS

    fun stopForRunLimit(): Task {
        val stage = current() ?: return this
        val failed = stage.fail("작업 전체 실행 횟수 상한($MAX_TASK_RUNS)에 도달해 멈췄습니다")
        return copy(stages = replaceStage(failed), status = TaskStatus.BLOCKED)
    }

    /** 현재 단계를 실행 중으로 넘기고 Runner 를 배정한 새 작업을 돌려준다. */
    fun assign(runnerId: RunnerId): Task {
        val stage = current()
        if (!isRunnable() || stage == null) throw InvalidTaskStateException("배정할 수 있는 단계가 없습니다")
        return copy(stages = replaceStage(stage.start()), runnerId = runnerId, runs = runs.increment())
    }

    /** 모델 선택 등으로 바뀐 현재 단계 상태를 반영한 새 작업을 돌려준다. 단계 식별자는 바꿀 수 없다. */
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
        // 중지한 뒤 늦게 도착한 결과는 버리고 이미 쓴 비용만 남긴다.
        if (stage != null && stage.status != StageStatus.RUNNING && stage.error == STOPPED_MESSAGE) {
            return copy(stages = replaceStage(withCost(stage, outcome).recordSession(outcome.sessionId)))
        }
        if (stage == null || (stage.status != StageStatus.RUNNING && !(outcome.executionId != null && stage.status == StageStatus.PENDING))) throw InvalidTaskStateException("실행 중인 단계가 없습니다")
        val withBranch = copy(branch = outcome.branch?.takeIf { it.isNotBlank() }?.let { Branch.of(it) } ?: branch)
        val costed = withCost(stage, outcome).completeExecution(outcome)
        val withSession = costed.recordSession(outcome.sessionId)
        val recorded = withSession.record(outcome.diff, outcome.error, outcome.validation)
        return when {
            // 질문에 답만 했으면 문서·테스트·구현 단계 없이 끝낸다. 남은 단계는 실행하지 않은 채(PENDING) 둔다.
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

    /** 승인 전의 요구사항에 사용자가 말을 건다. 같은 세션을 이어서 기존 문서를 고치게 한다. */
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

    /**
     * 차단된 작업의 실패 단계를 다시 시도한다. 사용자가 [instruction]을 주면 직전 Claude 세션을 이어 그 말을 전하고(CLI 에서 답하듯),
     * 없으면 마지막 실패 사유를 피드백으로 새로 시작한다. 사용자가 중지한 단계는 중지 문구 대신 그 전 시도의 피드백을 넘긴다.
     */
    fun retry(instruction: String? = null): Task {
        val stage = current()
        if (status != TaskStatus.BLOCKED || stage == null) throw InvalidTaskStateException("차단된 작업이 아닙니다")
        val reason = if (stage.error == STOPPED_MESSAGE) stage.feedback else stage.error ?: stage.feedback
        val text = instruction?.trim()?.takeIf { it.isNotEmpty() }
        val next = if (text == null) stage.restart(reason) else stage.continueWith(text, reason)
        return copy(stages = replaceStage(next), runs = RunCount.ZERO, status = TaskStatus.ACTIVE)
    }

    /** 실행 중이거나 대기 중인 단계를 멈추고 작업을 차단한다. 실행 중이던 Runner 는 이를 보고 프로세스를 끝낸다. */
    fun stop(): Task {
        val stage = current()
        if (status != TaskStatus.ACTIVE || stage == null || stage.status !in STOPPABLE) {
            throw InvalidTaskStateException("실행 중이거나 대기 중인 단계가 없습니다")
        }
        return copy(stages = replaceStage(stage.fail(STOPPED_MESSAGE)), status = TaskStatus.BLOCKED)
    }

    /** Runner 가 실행 중인 단계를 계속해야 하는지. 중지되거나 서버 재시작으로 실행 상태가 풀렸으면 false. */
    fun isRunning(): Boolean = current()?.status == StageStatus.RUNNING

    /** 서버를 다시 띄우면 Runner 와의 연결이 끊겼으므로 실행 중이던 단계는 대기로 되돌린다. */
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
    ): Task = Task(id, createdAt, projectId, feature, requirement, status, runnerId, branch, runs, stages, baseBranch)

    companion object {
        const val MAX_ATTEMPTS: Int = 3

        /** 요구사항 한 건에 사용자가 보낼 수 있는 대화 횟수. 대화가 길어질수록 비용이 커지므로 제한한다. */
        const val MAX_MESSAGES: Int = 10

        const val UNKNOWN_MODEL: String = "unknown"

        const val STOPPED_MESSAGE: String = "사용자가 중지했습니다"

        private val STOPPABLE = setOf(StageStatus.PENDING, StageStatus.RUNNING)

        /** 사람이 개입하지 않은 채 한 작업에 배정할 수 있는 전체 횟수(단계 4개 × 시도 3회). */
        const val MAX_TASK_RUNS: Int = 12

        fun create(id: TaskId, now: Instant, projectId: ProjectId, feature: String?, requirement: String?, baseBranch: Branch? = null): Task =
            Task(
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
            )

        /** 저장본에서 되살린다. 검증은 각 VO 가 맡는다. */
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
        ): Task = Task(id, createdAt, projectId, feature, requirement, status, runnerId, branch, runs, stages, baseBranch)
    }
}
