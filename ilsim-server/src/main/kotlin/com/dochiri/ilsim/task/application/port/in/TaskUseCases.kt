package com.dochiri.ilsim.task.application.port.`in`

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.Stats
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.TaskMetrics

interface RunnerUseCase {
    fun reviewPolicy(projectId: String): com.dochiri.ilsim.task.domain.ReviewPolicySnapshot = com.dochiri.ilsim.task.domain.ReviewPolicySnapshot()

    fun addEvent(taskId: String, stage: String?, type: String?, data: String?)

    fun addIdentifiedEvent(taskId: String, stage: String?, type: String?, data: String?, eventId: String?) = addEvent(taskId, stage, type, data)

    fun report(taskId: String, command: ReportStageCommand)

    fun register(runnerId: String, reported: List<Project>) {
        throw UnsupportedOperationException("프로젝트 등록을 지원하지 않습니다")
    }

    fun createForAgent(projectId: String, feature: String?, requirement: String?): Task {
        throw UnsupportedOperationException("Claude 앱 작업을 지원하지 않습니다")
    }

    fun claim(taskId: String, runnerId: String): Assignment? {
        throw UnsupportedOperationException("Claude 앱 작업을 지원하지 않습니다")
    }

    fun find(taskId: String): Task? = null

    fun metrics(projectId: String?): TaskMetrics = TaskMetrics.of(emptyList(), emptyMap())

    fun approve(taskId: String)

    fun reject(taskId: String, feedback: String?)

    fun retry(taskId: String, instruction: String? = null)

    fun reopen(taskId: String, stage: String?, feedback: String?)
}
