package com.dochiri.ilsim.task.application.port.`in`

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.application.result.Cleanup
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.Stats
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent

interface ManageTaskUseCase {
    fun create(projectId: String, feature: String?, requirement: String?, continueFrom: String? = null): Task

    fun approve(taskId: String)

    fun message(taskId: String, text: String?)

    fun reject(taskId: String, feedback: String?)

    fun retry(taskId: String, instruction: String? = null)

    fun stop(taskId: String)

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

    fun stats(projectId: String? = null): Stats
    fun projectReviews(projectId: String): List<com.dochiri.ilsim.task.domain.AiUsage> = emptyList()
}

interface RunnerUseCase {
    fun poll(runnerId: String, reported: List<Project>): Assignment?

    fun reviewPolicy(projectId: String): com.dochiri.ilsim.task.domain.ReviewPolicySnapshot = com.dochiri.ilsim.task.domain.ReviewPolicySnapshot()

    fun addEvent(taskId: String, stage: String?, type: String?, data: String?)

    fun addIdentifiedEvent(taskId: String, stage: String?, type: String?, data: String?, eventId: String?) = addEvent(taskId, stage, type, data)

    fun report(taskId: String, command: ReportStageCommand)

    fun claimCleanups(projectIds: Collection<String>): List<Cleanup>

    fun shouldStop(taskId: String): Boolean
    fun shouldStop(taskId: String, executionId: String?): Boolean = shouldStop(taskId)

    fun reportModels(models: Map<String, String>): Map<String, String> = emptyMap()
}

interface RoutingUseCase {
    fun routing(projectId: String? = null): RoutingOverview

    fun adjust(items: List<com.dochiri.ilsim.task.domain.RoutingAdjustment>, note: String?): com.dochiri.ilsim.task.domain.RoutingAdjustments

    fun applyRecommended(projectId: String? = null): com.dochiri.ilsim.task.domain.RoutingAdjustments

    fun restore(version: String): com.dochiri.ilsim.task.domain.RoutingAdjustments
}

data class RoutingOverview(
    val report: com.dochiri.ilsim.task.domain.RoutingReport,
    val current: com.dochiri.ilsim.task.domain.RoutingAdjustments,
    val history: List<com.dochiri.ilsim.task.domain.RoutingAdjustments>,
    val models: Map<String, String> = emptyMap(),
    val upgrades: List<String> = emptyList(),
)
