package com.dochiri.ilsim.task.application.port.`in`

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent

/** 사람(웹)이 작업을 만들고 승인·거절·재시도한다. */
interface ManageTaskUseCase {
    fun create(projectId: String, feature: String?, requirement: String?): Task

    fun approve(taskId: String)

    fun reject(taskId: String, feedback: String?)

    fun retry(taskId: String)
}

interface QueryTaskUseCase {
    fun find(taskId: String): Task?

    fun list(): List<Task>

    fun events(taskId: String): List<TaskEvent>

    fun projects(): List<ProjectStatus>
}

/** Runner 가 일감을 받아 가고 진행과 결과를 보고한다. */
interface RunnerUseCase {
    fun poll(runnerId: String, reported: List<Project>): Assignment?

    fun addEvent(taskId: String, stage: String?, type: String?, data: String?)

    fun report(taskId: String, command: ReportStageCommand)
}
