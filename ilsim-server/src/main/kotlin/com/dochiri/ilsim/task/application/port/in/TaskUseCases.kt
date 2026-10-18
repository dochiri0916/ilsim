package com.dochiri.ilsim.task.application.port.`in`

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.Stats
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent

/** 사람(웹)이 작업을 만들고 승인·거절·재시도한다. */
interface ManageTaskUseCase {
    fun create(projectId: String, feature: String?, requirement: String?): Task

    fun approve(taskId: String)

    /** 승인 전의 요구사항에 말을 건다. 같은 세션을 이어 문서를 고친다. */
    fun message(taskId: String, text: String?)

    fun reject(taskId: String, feedback: String?)

    fun retry(taskId: String)
}

interface QueryTaskUseCase {
    fun find(taskId: String): Task?

    fun list(): List<Task>

    fun events(taskId: String): List<TaskEvent>

    fun projects(): List<ProjectStatus>

    /** 모델별·단계별 사용 집계. [projectId]가 있으면 그 프로젝트만. */
    fun stats(projectId: String? = null): Stats
}

/** Runner 가 일감을 받아 가고 진행과 결과를 보고한다. */
interface RunnerUseCase {
    fun poll(runnerId: String, reported: List<Project>): Assignment?

    fun addEvent(taskId: String, stage: String?, type: String?, data: String?)

    fun report(taskId: String, command: ReportStageCommand)
}
