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
import com.dochiri.ilsim.task.domain.Stats
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.vo.ProjectId
import com.dochiri.ilsim.task.domain.vo.RunnerId
import com.dochiri.ilsim.task.domain.vo.TaskId
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
                val recovered = task.recoverAfterRestart()
                tasks[recovered.id.value] = recovered
            }
            if (tasks.isNotEmpty()) log.info("저장된 작업 {}건을 되살렸습니다", tasks.size)
        } catch (e: RuntimeException) {
            log.warn("저장된 작업을 읽지 못해 빈 상태로 시작합니다: {}", e.message)
        }
    }

    override fun create(projectId: String, feature: String?, requirement: String?): Task {
        if (!projects.containsKey(projectId)) throw InvalidTaskRequestException("등록되지 않은 프로젝트: $projectId")
        val task = Task.create(TaskId.of(UUID.randomUUID().toString()), clock.instant(), ProjectId.of(projectId), feature, requirement)
        tasks[task.id.value] = task
        events[task.id.value] = Collections.synchronizedList(ArrayList())
        persist(task)
        return task
    }

    override fun find(taskId: String): Task? = tasks[taskId]

    override fun stats(projectId: String?): Stats =
        Stats.of(if (projectId == null) tasks.values else tasks.values.filter { it.projectId.value == projectId })

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

        tasks.values.filter { it.hitRunLimit() }.forEach { update(it.id.value) { task -> task.stopForRunLimit() } }
        val chosen = tasks.values
            .filter { it.projectId.value in ids && it.isRunnable() }
            .minByOrNull { it.createdAt } ?: return null

        val assigned = chosen.assign(RunnerId.of(runnerId))
        val stage = checkNotNull(assigned.current())
        val decision = selector.select(assigned, stage, stage.attempt.value)
        val routed = assigned.updateCurrent(decision.state)
        tasks[routed.id.value] = routed
        persist(routed)
        val pending = decision.state.pendingMessage
        val prompt = if (pending != null) PromptTemplates.followUp(routed, pending, decision.state.feedback)
        else PromptTemplates.build(routed, stage.stage, decision.state.feedback)
        return Assignment(
            routed.id.value, routed.projectId.value, stage.stage.name, stage.attempt.value, routed.feature.value,
            decision.tier.modelId, effort, prompt, decision.state.sessionId.takeIf { pending != null },
        )
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
        update(taskId) { it.report(command.toOutcome()) }
    }

    @Synchronized
    override fun approve(taskId: String) {
        update(taskId) { it.approve() }
    }

    @Synchronized
    override fun message(taskId: String, text: String?) {
        val next = update(taskId) { it.message(text) }
        val stage = checkNotNull(next.current())
        addEvent(taskId, stage.stage.name, "message", stage.messages.last())
    }

    @Synchronized
    override fun reject(taskId: String, feedback: String?) {
        update(taskId) { it.reject(feedback) }
    }

    @Synchronized
    override fun retry(taskId: String) {
        update(taskId) { it.retry() }
    }

    /** 작업을 바꾼 새 인스턴스로 교체하고 저장한다. 없는 작업이면 [TaskNotFoundException]. */
    private fun update(taskId: String, change: (Task) -> Task): Task {
        val current = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        val next = change(current)
        tasks[taskId] = next
        persist(next)
        return next
    }

    private fun persist(task: Task) {
        try {
            store.saveTask(task)
        } catch (e: RuntimeException) {
            log.warn("작업 저장에 실패했습니다(메모리에서는 계속 동작): {}", e.message)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(TaskService::class.java)
        const val ONLINE_SECONDS = 15L
        const val EVENT_LOAD_LIMIT = 1000
        val PROJECT_SAVE_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
