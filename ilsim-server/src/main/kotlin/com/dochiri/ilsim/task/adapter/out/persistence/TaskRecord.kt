package com.dochiri.ilsim.task.adapter.out.persistence

import com.dochiri.ilsim.task.domain.Stage
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.StageStatus
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskStatus
import java.time.Instant

/** 작업 한 건의 저장용 사본(JSON). 저장소는 이 사본만 알고 도메인 객체는 모른다. */
data class TaskRecord(
    val id: String,
    val projectId: String,
    val feature: String,
    val requirement: String,
    val createdAt: Instant,
    val status: String,
    val runnerId: String? = null,
    val branch: String? = null,
    val runs: Int = 0,
    val stages: List<StageRecord> = emptyList(),
) {
    data class StageRecord(
        val stage: String,
        val status: String,
        val attempt: Int = 1,
        val feedback: String? = null,
        val lastErrorKey: String? = null,
        val error: String? = null,
        val diff: String? = null,
        val artifactHash: String? = null,
        val artifacts: Map<String, String>? = null,
        val validation: Map<String, String>? = null,
        val routing: Map<String, String>? = null,
        val routingLog: List<String>? = null,
        val costUsd: Double = 0.0,
        val costByModel: Map<String, Double>? = null,
    )

    fun toTask(): Task {
        val restored = stages.mapNotNull { r ->
            val stage = Stage.entries.firstOrNull { it.name == r.stage } ?: return@mapNotNull null
            StageState(
                stage, StageStatus.valueOf(r.status), r.attempt, r.feedback, r.lastErrorKey, r.error, r.diff, r.artifactHash,
                r.artifacts.orEmpty(), r.validation.orEmpty(), r.routing.orEmpty(), r.routingLog.orEmpty(), r.costUsd, r.costByModel.orEmpty(),
            )
        }.associateBy { it.stage }
        // 저장본에 없는 단계는 새로 만든다(단계가 추가돼도 읽을 수 있게).
        val all = Stage.entries.map { restored[it] ?: StageState(it) }
        return Task(id, createdAt, projectId, feature, requirement, TaskStatus.valueOf(status), runnerId, branch, runs, all)
    }

    companion object {
        fun from(t: Task): TaskRecord = TaskRecord(
            t.id, t.projectId, t.feature, t.requirement, t.createdAt, t.status.name, t.runnerId, t.branch, t.runs,
            t.stages.map { s ->
                StageRecord(
                    s.stage.name, s.status.name, s.attempt, s.feedback, s.lastErrorKey, s.error, s.diff, s.artifactHash,
                    LinkedHashMap(s.artifacts), LinkedHashMap(s.validation), LinkedHashMap(s.routing), ArrayList(s.routingLog), s.costUsd, LinkedHashMap(s.costByModel),
                )
            },
        )
    }
}
