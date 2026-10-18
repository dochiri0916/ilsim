package com.dochiri.ilsim.task.adapter.out.persistence

import com.dochiri.ilsim.task.application.port.out.StoredProject
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.AiUsage
import com.dochiri.ilsim.task.domain.ReviewSampleGrant
import java.time.Instant
import org.springframework.data.domain.Pageable
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** [TaskStore] 의 JPA(MySQL) 구현. 도메인 객체는 [TaskRecord] JSON 으로 바꿔 저장한다. */
open class JpaTaskStore(
    private val schema: TaskSchema,
    private val tasks: TaskJpaRepository,
    private val events: TaskEventJpaRepository,
    private val projects: ProjectJpaRepository,
) : TaskStore {

    @org.springframework.transaction.annotation.Transactional
    open override fun saveOutcome(task: Task, events: List<TaskEvent>) {
        saveTask(task)
        events.forEach { appendEvent(task.id.value, it) }
    }

    private val json: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    open override fun loadTasks(): List<Task> {
        schema.ensure()
        return tasks.findAllByOrderByCreatedAtAsc().map { json.readValue(it.snapshot, TaskRecord::class.java).toTask() }
    }

    open override fun saveTask(task: Task) {
        schema.ensure()
        val record = TaskRecord.from(task)
        val body = json.writeValueAsString(record)
        val entity = tasks.findById(record.id).orElse(null)
        if (entity == null) {
            tasks.save(TaskEntity.of(record.id, record.projectId, record.status, record.createdAt, Instant.now(), body))
        } else {
            entity.update(record.status, Instant.now(), body)
            tasks.save(entity)
        }
    }

    open override fun loadEvents(taskId: String, limit: Int): List<TaskEvent> {
        schema.ensure()
        return events.findByTaskIdOrderByIdDesc(taskId, Pageable.ofSize(limit))
            .map { TaskEvent(it.at, it.stage, it.type, it.data, it.eventId) }
            .reversed()
    }

    open override fun appendEvent(taskId: String, event: TaskEvent) {
        schema.ensure()
        if (event.eventId != null && events.existsByTaskIdAndEventId(taskId, event.eventId)) return
        events.save(TaskEventEntity.of(taskId, event.at, event.stage, event.type, event.data, event.eventId))
    }

    open override fun deleteTask(taskId: String) {
        schema.ensure()
        events.deleteAllOfTask(taskId)
        tasks.deleteById(taskId)
    }

    open override fun loadProjects(): Map<String, StoredProject> {
        schema.ensure()
        return projects.findAll().associate { entity ->
            val accounting = entity.usageSnapshot?.let { body ->
                if (json.readTree(body).isArray) ProjectAccounting(json.readValue(body, Array<AiUsage>::class.java).toList())
                else json.readValue(body, ProjectAccounting::class.java)
            } ?: ProjectAccounting()
            entity.id to StoredProject(entity.name, entity.runnerId, entity.lastSeen, accounting.usage, accounting.reviewSamples)
        }
    }

    open override fun saveProject(id: String, project: StoredProject) {
        schema.ensure()
        val entity = projects.findById(id).orElse(null)
        if (entity == null) {
            projects.save(ProjectEntity.of(id, project.name, project.runnerId, project.lastSeen).also { it.recordUsage(json.writeValueAsString(ProjectAccounting(project.usage, project.reviewSamples))) })
        } else {
            entity.update(project.name, project.runnerId, project.lastSeen)
            entity.recordUsage(json.writeValueAsString(ProjectAccounting(project.usage, project.reviewSamples)))
            projects.save(entity)
        }
    }

    data class ProjectAccounting(val usage: List<AiUsage> = emptyList(), val reviewSamples: List<ReviewSampleGrant> = emptyList())
}
