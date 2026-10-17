package com.dochiri.ilsim.task.adapter.`in`.web

import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
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
) {
    companion object {
        fun from(s: StageState): StageResponse = StageResponse(
            s.stage.name, s.status.name, s.attempt, s.feedback, s.lastErrorKey, s.routing, s.routingLog.toList(),
            s.error, s.diff, s.artifactHash, s.artifacts, s.validation,
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
) {
    companion object {
        fun from(t: Task): TaskResponse = TaskResponse(
            t.id, t.projectId, t.feature, t.requirement, t.createdAt, t.status.name, t.runnerId, t.branch, t.runs,
            t.stages.map(StageResponse::from),
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
