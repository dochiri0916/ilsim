package com.dochiri.ilsim.task.application.port.`in`

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.application.result.Cleanup
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.Stats
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent

/** 사람(웹)이 작업을 만들고 승인·거절·재시도한다. */
interface ManageTaskUseCase {
    /** [continueFrom]이 있으면 그 작업의 브랜치 위에서 시작한다(끝난 작업에 이어서 지시). */
    fun create(projectId: String, feature: String?, requirement: String?, continueFrom: String? = null): Task

    fun approve(taskId: String)

    /** 승인 전의 요구사항에 말을 건다. 같은 세션을 이어 문서를 고친다. */
    fun message(taskId: String, text: String?)

    fun reject(taskId: String, feedback: String?)

    /** 차단된 단계를 다시 시도한다. [instruction]은 실패 사유와 함께 Claude 에 넘길 추가 지시. */
    fun retry(taskId: String, instruction: String? = null)

    /** 실행 중이거나 대기 중인 단계를 멈춘다. */
    fun stop(taskId: String)

    /** 작업과 기록을 지운다. 실행 중이면 먼저 중지해야 한다. worktree 는 Runner 가 다음 확인 때 지운다. */
    fun delete(taskId: String)

    fun qualityFeedback(taskId: String, executionId: String, passed: Boolean, reason: String) {
        throw UnsupportedOperationException("실행 품질 피드백을 지원하지 않습니다")
    }
    fun reviewFeedback(taskId: String, usageId: String, quality: com.dochiri.ilsim.task.domain.ReviewQuality) {
        throw UnsupportedOperationException("검토 품질 피드백을 지원하지 않습니다")
    }
    fun projectReviewFeedback(projectId: String, usageId: String, quality: com.dochiri.ilsim.task.domain.ReviewQuality) {
        throw UnsupportedOperationException("분석 품질 피드백을 지원하지 않습니다")
    }
}

interface QueryTaskUseCase {
    fun find(taskId: String): Task?

    fun list(): List<Task>

    fun events(taskId: String): List<TaskEvent>

    fun projects(): List<ProjectStatus>

    /** 모델별·단계별 사용 집계. [projectId]가 있으면 그 프로젝트만. */
    fun stats(projectId: String? = null): Stats
    fun projectReviews(projectId: String): List<com.dochiri.ilsim.task.domain.AiUsage> = emptyList()
}

/** Runner 가 일감을 받아 가고 진행과 결과를 보고한다. */
interface RunnerUseCase {
    fun poll(runnerId: String, reported: List<Project>): Assignment?

    fun reviewPolicy(projectId: String): com.dochiri.ilsim.task.domain.ReviewPolicySnapshot = com.dochiri.ilsim.task.domain.ReviewPolicySnapshot()

    fun addEvent(taskId: String, stage: String?, type: String?, data: String?)

    fun addIdentifiedEvent(taskId: String, stage: String?, type: String?, data: String?, eventId: String?) = addEvent(taskId, stage, type, data)

    fun report(taskId: String, command: ReportStageCommand)

    /** 이 Runner 프로젝트에서 삭제된 작업의 worktree 정리 일감을 가져간다(가져가면 목록에서 빠진다). */
    fun claimCleanups(projectIds: Collection<String>): List<Cleanup>

    /** 실행 중인 단계를 그만둬야 하면 true(중지됐거나 더 이상 실행 중이 아님). */
    fun shouldStop(taskId: String): Boolean
    fun shouldStop(taskId: String, executionId: String?): Boolean = shouldStop(taskId)

    /**
     * Runner 가 Claude Code 별칭으로 확인한 등급별 최신 모델 ID(`haiku` → `claude-haiku-5-6`). 같은 계열의 더 높은 버전만 받아들이고
     * 받아들인 것만 돌려준다. Fable 계열은 받지 않는다.
     */
    fun reportModels(models: Map<String, String>): Map<String, String> = emptyMap()
}

/** JEV 의 모델·추론 강도 선택이 맞았는지 실행 이력으로 보고, 사람이 조정한다. 조정은 다음 선택부터 적용된다. */
interface RoutingUseCase {
    /** [projectId]가 있으면 그 프로젝트 작업만 집계한다(조정은 전체 공통). */
    fun routing(projectId: String? = null): RoutingOverview

    fun adjust(items: List<com.dochiri.ilsim.task.domain.RoutingAdjustment>, note: String?): com.dochiri.ilsim.task.domain.RoutingAdjustments

    /** 표본이 충분한 조합의 권장 보정값을 현재 조정에 덮어써 적용한다. */
    fun applyRecommended(projectId: String? = null): com.dochiri.ilsim.task.domain.RoutingAdjustments

    /** 이전 조정 버전을 다시 적용한다. "none" 은 조정 없음. */
    fun restore(version: String): com.dochiri.ilsim.task.domain.RoutingAdjustments
}

data class RoutingOverview(
    val report: com.dochiri.ilsim.task.domain.RoutingReport,
    val current: com.dochiri.ilsim.task.domain.RoutingAdjustments,
    /** 최근 것부터. */
    val history: List<com.dochiri.ilsim.task.domain.RoutingAdjustments>,
    /** 등급별 지금 쓰는 모델 ID. */
    val models: Map<String, String> = emptyMap(),
    /** 새 버전 도입 기록(최근 것부터). */
    val upgrades: List<String> = emptyList(),
)
