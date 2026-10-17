package com.dochiri.ilsim.task.domain

import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import java.time.Instant

/** 요구사항 1건과 그 진행 상태. 단계 전이 규칙(승인, 재시도, 실행 횟수 상한)은 모두 이 집합체가 지킨다. */
class Task(
    val id: String,
    val createdAt: Instant,
    val projectId: String,
    feature: String?,
    val requirement: String,
    status: TaskStatus = TaskStatus.ACTIVE,
    runnerId: String? = null,
    branch: String? = null,
    runs: Int = 0,
    val stages: List<StageState> = Stage.entries.map { StageState(it) },
) {
    val feature: String = if (feature.isNullOrBlank()) "feat-${id.take(8)}" else feature

    @Volatile var status: TaskStatus = status
        private set
    @Volatile var runnerId: String? = runnerId
        private set
    @Volatile var branch: String? = branch
        private set

    /** 사람이 개입(거절·재시도)한 뒤로 Runner 에 배정된 횟수. 자동 재시도가 끝없이 이어지지 않게 하는 전체 상한에 쓴다. */
    @Volatile var runs: Int = runs
        private set

    /** 아직 통과하지 못한 첫 단계. 모두 통과했으면 null. */
    fun current(): StageState? = stages.firstOrNull { it.status != StageStatus.PASSED }

    fun stage(stage: Stage): StageState = stages.first { it.stage == stage }

    /** Runner 에 배정할 수 있는 상태인지. */
    /** 작업 전체에서 쓴 Claude 비용(USD)과 모델별 내역. */
    fun costUsd(): Double = stages.sumOf { it.costUsd }

    fun costByModel(): Map<String, Double> =
        stages.flatMap { it.costByModel.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

    fun isRunnable(): Boolean = status == TaskStatus.ACTIVE && current()?.status == StageStatus.PENDING

    fun hitRunLimit(): Boolean = isRunnable() && runs >= MAX_TASK_RUNS

    fun stopForRunLimit() {
        val stage = current() ?: return
        stage.fail("작업 전체 실행 횟수 상한($MAX_TASK_RUNS)에 도달해 멈췄습니다")
        status = TaskStatus.BLOCKED
    }

    /** 현재 단계를 실행 중으로 넘기고, 배정된 단계를 돌려준다. */
    fun assign(runnerId: String): StageState {
        val stage = current()
        if (!isRunnable() || stage == null) throw InvalidTaskStateException("배정할 수 있는 단계가 없습니다")
        stage.start()
        this.runnerId = runnerId
        runs++
        return stage
    }

    fun report(outcome: StageOutcome) {
        val stage = current()
        if (stage == null || stage.status != StageStatus.RUNNING) throw InvalidTaskStateException("실행 중인 단계가 없습니다")
        outcome.branch?.let { branch = it }
        outcome.costUsd?.takeIf { it.isFinite() && it > 0 }?.let { stage.addCost(it, outcome.model ?: UNKNOWN_MODEL) }
        stage.record(outcome.diff, outcome.error, outcome.validation)
        when {
            outcome.passed -> {
                stage.pass(outcome.artifacts ?: emptyMap(), awaitApproval = stage.stage.approval)
                if (current() == null) status = TaskStatus.COMPLETED
            }
            outcome.retryable && stage.attempt < MAX_ATTEMPTS -> stage.scheduleRetry(outcome.error, MAX_ATTEMPTS)
            else -> {
                stage.fail()
                status = TaskStatus.BLOCKED
            }
        }
    }

    fun approve() {
        val stage = waitingStage()
        stage.approve()
        if (current() == null) status = TaskStatus.COMPLETED
    }

    fun reject(feedback: String?) {
        val stage = waitingStage()
        if (feedback.isNullOrBlank()) throw InvalidTaskRequestException("거절 사유가 필요합니다")
        stage.restart(feedback)
        runs = 0
    }

    /** 차단된 작업의 실패 단계를 처음부터 다시 시도한다. */
    fun retry() {
        val stage = current()
        if (status != TaskStatus.BLOCKED || stage == null) throw InvalidTaskStateException("차단된 작업이 아닙니다")
        stage.restart(stage.feedback)
        runs = 0
        status = TaskStatus.ACTIVE
    }

    /** 서버를 다시 띄우면 Runner 와의 연결이 끊겼으므로 실행 중이던 단계는 대기로 되돌린다. */
    fun recoverAfterRestart() {
        stages.forEach { it.recoverInterrupted() }
    }

    private fun waitingStage(): StageState {
        val stage = current()
        if (stage == null || stage.status != StageStatus.WAITING_APPROVAL) throw InvalidTaskStateException("승인 대기 중인 단계가 없습니다")
        return stage
    }

    companion object {
        const val MAX_ATTEMPTS: Int = 3

        const val UNKNOWN_MODEL: String = "unknown"

        /** 사람이 개입하지 않은 채 한 작업에 배정할 수 있는 전체 횟수(단계 4개 × 시도 3회). */
        const val MAX_TASK_RUNS: Int = 12

        private val FEATURE = Regex("[a-z0-9][a-z0-9-]{0,40}")

        fun create(id: String, now: Instant, projectId: String, feature: String?, requirement: String?): Task {
            if (requirement.isNullOrBlank()) throw InvalidTaskRequestException("요구사항이 비어 있습니다")
            if (!feature.isNullOrBlank() && !FEATURE.matches(feature)) {
                throw InvalidTaskRequestException("feature는 소문자·숫자·하이픈만 사용할 수 있습니다")
            }
            return Task(id, now, projectId, feature, requirement)
        }
    }
}
