package com.dochiri.ilsim.task.adapter.`in`.web

import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.StageStatus
import java.time.Instant

data class StageResponse(
    val stage: String,
    val status: String,
    val attempt: Int,
    val feedback: String?,
    val lastErrorKey: String?,
    val routing: Map<String, String>,
    val routingLog: List<String>,
    val error: String?,
    val diff: String?,
    val artifactHash: String?,
    val artifacts: Map<String, String>,
    val validation: Map<String, String>,
    val costUsd: Double,
    val costByModel: Map<String, Double>,
    /** 요구사항 단계에서 사용자가 보낸 대화 메시지. */
    val messages: List<String>,
    /** 대화를 이어 갈 수 있는지(요구사항 단계가 승인을 기다리는 중이고 횟수가 남았을 때). */
    val conversable: Boolean,
) {
    companion object {
        fun from(s: StageState): StageResponse = StageResponse(
            s.stage.name, s.status.name, s.attempt.value, s.feedback, s.lastErrorKey, s.routing, s.routingLog.toList(),
            s.error, s.diff, s.artifactHash, s.artifacts, s.validation, s.costUsd, s.costByModel,
            s.messages, s.stage == Stage.REQUIREMENTS && s.status == StageStatus.WAITING_APPROVAL && s.messages.size < Task.MAX_MESSAGES,
        )
    }
}

data class TaskResponse(
    val id: String,
    val projectId: String,
    val feature: String,
    val requirement: String,
    val createdAt: Instant,
    val status: String,
    val runnerId: String?,
    val branch: String?,
    val runs: Int,
    val stages: List<StageResponse>,
    val costUsd: Double,
    val costByModel: Map<String, Double>,
) {
    companion object {
        fun from(t: Task): TaskResponse = TaskResponse(
            t.id.value, t.projectId.value, t.feature.value, t.requirement.value, t.createdAt, t.status.name,
            t.runnerId?.value, t.branch?.value, t.runs.value,
            t.stages.map(StageResponse::from), t.costUsd(), t.costByModel(),
        )
    }
}

data class EventResponse(val at: Instant, val stage: String?, val type: String?, val data: String?) {
    companion object {
        fun from(e: TaskEvent): EventResponse = EventResponse(e.at, e.stage, e.type, e.data)
    }
}

data class TaskDetailResponse(val task: TaskResponse, val events: List<EventResponse>)

data class ProjectResponse(val id: String, val name: String, val runnerId: String?, val lastSeen: Instant?, val online: Boolean) {
    companion object {
        fun from(p: ProjectStatus): ProjectResponse = ProjectResponse(p.project.id, p.project.name, p.project.runnerId, p.lastSeen, p.online)
    }
}
