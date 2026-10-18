package com.dochiri.ilsim.task.adapter.out.persistence

import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.AiUsage
import com.dochiri.ilsim.task.domain.ExecutionRecord
import com.dochiri.ilsim.task.domain.ProcessingDecision
import com.dochiri.ilsim.task.domain.vo.Attempt
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
    val baseBranch: String? = null,
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
        val sessionId: String? = null,
        val messages: List<String>? = null,
        val pendingMessage: String? = null,
        val executionId: String? = null,
        val executions: List<ExecutionRecord> = emptyList(),
        val usage: List<AiUsage> = emptyList(),
        val decisions: List<ProcessingDecision> = emptyList(),
    )

    fun toTask(): Task {
        val restored = stages.mapNotNull { r ->
            val stage = Stage.entries.firstOrNull { it.name == r.stage } ?: return@mapNotNull null
            StageState.restore(
                stage = stage,
                status = StageStatus.valueOf(r.status),
                attempt = Attempt.of(r.attempt),
                feedback = r.feedback,
                lastErrorKey = r.lastErrorKey,
                error = r.error,
                diff = r.diff,
                artifactHash = r.artifactHash,
                artifacts = r.artifacts.orEmpty(),
                validation = r.validation.orEmpty(),
                routing = r.routing.orEmpty(),
                routingLog = r.routingLog.orEmpty(),
                costUsd = r.costUsd,
                costByModel = r.costByModel.orEmpty(),
                sessionId = r.sessionId,
                messages = r.messages.orEmpty(),
                pendingMessage = r.pendingMessage,
                executionId = r.executionId,
                executions = r.executions,
                usage = r.usage,
                decisions = r.decisions,
            )
        }.associateBy { it.stage }
        // 저장본에 없는 단계는 새로 만든다(단계가 추가돼도 읽을 수 있게).
        val all = Stage.entries.map { restored[it] ?: StageState.initial(it) }
        return Task.restore(
            id = TaskId.of(id),
            createdAt = createdAt,
            projectId = ProjectId.of(projectId),
            feature = Feature.of(feature),
            requirement = Requirement.of(requirement),
            status = TaskStatus.valueOf(status),
            runnerId = runnerId?.let { RunnerId.of(it) },
            // 빈 브랜치는 예전 저장본에서 생길 수 있으므로 없는 값으로 읽는다.
            branch = branch?.takeIf { it.isNotBlank() }?.let { Branch.of(it) },
            runs = RunCount.of(runs),
            stages = all,
            baseBranch = baseBranch?.takeIf { it.isNotBlank() }?.let { Branch.of(it) },
        )
    }

    companion object {
        fun from(t: Task): TaskRecord = TaskRecord(
            t.id.value, t.projectId.value, t.feature.value, t.requirement.value, t.createdAt, t.status.name,
            t.runnerId?.value, t.branch?.value, t.runs.value,
            t.stages.map { s ->
                StageRecord(
                    s.stage.name, s.status.name, s.attempt.value, s.feedback, s.lastErrorKey, s.error, s.diff, s.artifactHash,
                    LinkedHashMap(s.artifacts), LinkedHashMap(s.validation), LinkedHashMap(s.routing), ArrayList(s.routingLog), s.costUsd, LinkedHashMap(s.costByModel),
                    s.sessionId, ArrayList(s.messages), s.pendingMessage, s.executionId, s.executions.toList(), s.usage.toList(), s.decisions.toList(),
                )
            },
            t.baseBranch?.value,
        )
    }
}
