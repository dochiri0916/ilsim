package com.dochiri.ilsim.task.application.usecase

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.port.`in`.ManageTaskUseCase
import com.dochiri.ilsim.task.application.port.`in`.QueryTaskUseCase
import com.dochiri.ilsim.task.application.port.`in`.RunnerUseCase
import com.dochiri.ilsim.task.application.port.out.StoredProject
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.PromptTemplates
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.exception.TaskNotFoundException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * 작업 목록을 메모리에 들고 있고, 변경될 때마다 [TaskStore] 에 저장한다. 저장에 실패해도 메모리에서는 계속 동작한다.
 * 단계 전이 규칙은 [Task] 가 지키고, 여기서는 배정 순서·프로젝트 등록·저장만 맡는다.
 */
class TaskService(
    private val selector: ModelSelector,
    private val store: TaskStore,
    private val clock: Clock,
    private val effort: String,
) : ManageTaskUseCase, QueryTaskUseCase, RunnerUseCase {

    private val tasks = ConcurrentHashMap<String, Task>()
    private val events = ConcurrentHashMap<String, MutableList<TaskEvent>>()
    private val projects = ConcurrentHashMap<String, Project>()
    private val seen = ConcurrentHashMap<String, Instant>()
    private val savedSeen = ConcurrentHashMap<String, Instant>()

    /** 저장된 작업과 프로젝트를 되살린다. 저장소를 못 읽으면 빈 상태로 시작한다. */
    fun restore() {
        try {
            store.loadProjects().forEach { (id, p) ->
                projects[id] = Project(id, p.name, p.runnerId)
                seen[id] = p.lastSeen
                savedSeen[id] = p.lastSeen
            }
            store.loadTasks().forEach { task ->
                task.recoverAfterRestart()
                tasks[task.id] = task
            }
            if (tasks.isNotEmpty()) log.info("저장된 작업 {}건을 되살렸습니다", tasks.size)
        } catch (e: RuntimeException) {
            log.warn("저장된 작업을 읽지 못해 빈 상태로 시작합니다: {}", e.message)
        }
    }

    override fun create(projectId: String, feature: String?, requirement: String?): Task {
        if (!projects.containsKey(projectId)) throw InvalidTaskRequestException("등록되지 않은 프로젝트: $projectId")
        val task = Task.create(UUID.randomUUID().toString(), clock.instant(), projectId, feature, requirement)
        tasks[task.id] = task
        events[task.id] = Collections.synchronizedList(ArrayList())
        persist(task)
        return task
    }

    override fun find(taskId: String): Task? = tasks[taskId]

    override fun list(): List<Task> = tasks.values.sortedByDescending { it.createdAt }

    override fun projects(): List<ProjectStatus> {
        val now = clock.instant()
        return projects.values.sortedBy { it.id }.map { p ->
            val last = seen[p.id]
            ProjectStatus(p, last, last != null && now.minusSeconds(ONLINE_SECONDS).isBefore(last))
        }
    }

    override fun events(taskId: String): List<TaskEvent> {
        val list = eventList(taskId) ?: return emptyList()
        return synchronized(list) { ArrayList(list) }
    }

    /** 이벤트 목록. 서버를 다시 띄운 뒤 처음 열 때 저장소에서 최근 것을 읽어 온다. */
    private fun eventList(taskId: String): MutableList<TaskEvent>? {
        if (!tasks.containsKey(taskId)) return null
        return events.computeIfAbsent(taskId) { id ->
            try {
                Collections.synchronizedList(ArrayList(store.loadEvents(id, EVENT_LOAD_LIMIT)))
            } catch (e: RuntimeException) {
                log.warn("이벤트를 읽지 못했습니다: {}", e.message)
                Collections.synchronizedList(ArrayList())
            }
        }
    }

    /** Runner 가 보고한 프로젝트를 등록하고, 실행 대기 중인 단계 하나를 RUNNING 으로 넘긴다. */
    @Synchronized
    override fun poll(runnerId: String, reported: List<Project>): Assignment? {
        val now = clock.instant()
        reported.forEach { register(it, runnerId, now) }
        val ids = reported.map { it.id }.toSet()

        tasks.values.filter { it.hitRunLimit() }.forEach {
            it.stopForRunLimit()
            persist(it)
        }
        val task = tasks.values
            .filter { it.projectId in ids && it.isRunnable() }
            .minByOrNull { it.createdAt } ?: return null

        val stage = task.assign(runnerId)
        val tier = selector.select(task, stage, stage.attempt)
        val assignment = Assignment(
            task.id, task.projectId, stage.stage.name, stage.attempt, task.feature,
            tier.modelId, effort, PromptTemplates.build(task, stage.stage, stage.feedback),
        )
        persist(task)
        return assignment
    }

    private fun register(project: Project, runnerId: String, now: Instant) {
        val previous = projects.put(project.id, Project(project.id, project.name, runnerId))
        seen[project.id] = now
        val saved = savedSeen[project.id]
        val changed = previous == null || previous.name != project.name || previous.runnerId != runnerId
        if (changed || saved == null || saved.plus(PROJECT_SAVE_INTERVAL).isBefore(now)) {
            try {
                store.saveProject(project.id, StoredProject(project.name, runnerId, now))
                savedSeen[project.id] = now
            } catch (e: RuntimeException) {
                log.warn("프로젝트 저장에 실패했습니다: {}", e.message)
            }
        }
    }

    override fun addEvent(taskId: String, stage: String?, type: String?, data: String?) {
        val list = eventList(taskId) ?: return
        val event = TaskEvent(clock.instant(), stage, type, data)
        list.add(event)
        try {
            store.appendEvent(taskId, event)
        } catch (e: RuntimeException) {
            log.warn("이벤트 저장에 실패했습니다: {}", e.message)
        }
    }

    @Synchronized
    override fun report(taskId: String, command: ReportStageCommand) {
        val task = require(taskId)
        task.report(command.toOutcome())
        persist(task)
    }

    @Synchronized
    override fun approve(taskId: String) {
        val task = require(taskId)
        task.approve()
        persist(task)
    }

    @Synchronized
    override fun reject(taskId: String, feedback: String?) {
        val task = require(taskId)
        task.reject(feedback)
        persist(task)
    }

    @Synchronized
    override fun retry(taskId: String) {
        val task = require(taskId)
        task.retry()
        persist(task)
    }

    private fun persist(task: Task) {
        try {
            store.saveTask(task)
        } catch (e: RuntimeException) {
            log.warn("작업 저장에 실패했습니다(메모리에서는 계속 동작): {}", e.message)
        }
    }

    private fun require(id: String): Task = tasks[id] ?: throw TaskNotFoundException(id)

    private companion object {
        val log = LoggerFactory.getLogger(TaskService::class.java)
        const val ONLINE_SECONDS = 15L
        const val EVENT_LOAD_LIMIT = 1000
        val PROJECT_SAVE_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
