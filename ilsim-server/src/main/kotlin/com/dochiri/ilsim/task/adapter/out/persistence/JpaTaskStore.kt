package com.dochiri.ilsim.task.adapter.out.persistence

import com.dochiri.ilsim.task.application.port.out.StoredProject
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import java.time.Instant
import org.springframework.data.domain.Pageable
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** [TaskStore] 의 JPA(MySQL) 구현. 도메인 객체는 [TaskRecord] JSON 으로 바꿔 저장한다. */
class JpaTaskStore(
    private val schema: TaskSchema,
    private val tasks: TaskJpaRepository,
    private val events: TaskEventJpaRepository,
    private val projects: ProjectJpaRepository,
) : TaskStore {

    private val json: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    override fun loadTasks(): List<Task> {
        schema.ensure()
        return tasks.findAllByOrderByCreatedAtAsc().map { json.readValue(it.snapshot, TaskRecord::class.java).toTask() }
    }

    override fun saveTask(task: Task) {
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

    override fun loadEvents(taskId: String, limit: Int): List<TaskEvent> {
        schema.ensure()
        return events.findByTaskIdOrderByIdDesc(taskId, Pageable.ofSize(limit))
            .map { TaskEvent(it.at, it.stage, it.type, it.data) }
            .reversed()
    }

    override fun appendEvent(taskId: String, event: TaskEvent) {
        schema.ensure()
        events.save(TaskEventEntity.of(taskId, event.at, event.stage, event.type, event.data))
    }

    override fun loadProjects(): Map<String, StoredProject> {
        schema.ensure()
        return projects.findAll().associate { it.id to StoredProject(it.name, it.runnerId, it.lastSeen) }
    }

    override fun saveProject(id: String, project: StoredProject) {
        schema.ensure()
        val entity = projects.findById(id).orElse(null)
        if (entity == null) {
            projects.save(ProjectEntity.of(id, project.name, project.runnerId, project.lastSeen))
        } else {
            entity.update(project.name, project.runnerId, project.lastSeen)
            projects.save(entity)
        }
    }
}
