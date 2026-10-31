package com.dochiri.ilsim.task.application.usecase

import com.dochiri.ilsim.task.application.command.ReportStageCommand
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
import com.dochiri.ilsim.task.domain.TaskMetrics
import com.dochiri.ilsim.task.domain.AttemptRecord
import com.dochiri.ilsim.task.domain.ExecutionRecord
import com.dochiri.ilsim.task.domain.AiUsage
import com.dochiri.ilsim.task.domain.ReviewCostPolicy
import com.dochiri.ilsim.task.domain.ReviewPolicySnapshot
import com.dochiri.ilsim.task.domain.ReviewSampleGrant
import com.dochiri.ilsim.task.domain.ReviewQuality
import com.dochiri.ilsim.task.domain.vo.ProjectId
import com.dochiri.ilsim.task.domain.vo.RunnerId
import com.dochiri.ilsim.task.domain.vo.Stage
import com.dochiri.ilsim.task.domain.vo.TaskId
import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.exception.TaskNotFoundException
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

class TaskService(
    private val store: TaskStore,
    private val clock: Clock,
    private val costAlertUsd: Double = 0.0,
    private val reviewCostPolicy: ReviewCostPolicy = ReviewCostPolicy(),
) : RunnerUseCase {

    private val tasks = ConcurrentHashMap<String, Task>()
    private val events = ConcurrentHashMap<String, MutableList<TaskEvent>>()
    private val projects = ConcurrentHashMap<String, Project>()
    private val seen = ConcurrentHashMap<String, Instant>()
    private val savedSeen = ConcurrentHashMap<String, Instant>()
    private val projectUsage = ConcurrentHashMap<String, List<AiUsage>>()
    private val projectSamples = ConcurrentHashMap<String, List<ReviewSampleGrant>>()

    fun restore() {
        try {
            store.loadProjects().forEach { (id, p) ->
                projects[id] = Project(id, p.name, p.runnerId)
                seen[id] = p.lastSeen
                savedSeen[id] = p.lastSeen
                projectUsage[id] = p.usage
                projectSamples[id] = p.reviewSamples
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

    override fun find(taskId: String): Task? = tasks[taskId]

    fun stats(projectId: String? = null): Stats {
        val selected = if (projectId == null) tasks.values else tasks.values.filter { it.projectId.value == projectId }
        val analysis = if (projectId == null) projectUsage.values.flatten() else projectUsage[projectId].orEmpty()
        val reservations = (if (projectId == null) projectSamples.values.flatten() else projectSamples[projectId].orEmpty()).filter { it.policyVersion == reviewCostPolicy.version }
        return Stats.of(selected, analysis).copy(reviewProfiles = reviewCostPolicy.snapshot(selected.flatMap { task -> task.stages.flatMap { it.usage } } + analysis).profiles,
            reviewSamplesReserved = reservations.size, reviewSampleBudgetReservedUsd = reservations.sumOf { it.maxCostUsd })
    }

    override fun metrics(projectId: String?): TaskMetrics {
        val selected = if (projectId == null) tasks.values.toList() else tasks.values.filter { it.projectId.value == projectId }
        return TaskMetrics.of(selected, selected.associate { task ->
            task.id.value to events(task.id.value).filter { it.type == AttemptRecord.EVENT_TYPE }.mapNotNull { AttemptRecord.parse(it.stage, it.data) }
        })
    }

    fun list(): List<Task> = tasks.values.sortedByDescending { it.createdAt }

    fun projects(): List<ProjectStatus> {
        val now = clock.instant()
        return projects.values.sortedBy { it.id }.map { p ->
            val last = seen[p.id]
            ProjectStatus(p, last, last != null && now.minusSeconds(ONLINE_SECONDS).isBefore(last))
        }
    }

    fun events(taskId: String): List<TaskEvent> {
        val list = eventList(taskId) ?: return emptyList()
        return synchronized(list) { ArrayList(list) }
    }

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

    @Synchronized
    override fun register(runnerId: String, reported: List<Project>) {
        val now = clock.instant()
        reported.forEach { register(it, runnerId, now) }
    }

    @Synchronized
    override fun createForAgent(projectId: String, feature: String?, requirement: String?): Task {
        if (!projects.containsKey(projectId)) throw InvalidTaskRequestException("등록되지 않은 프로젝트: $projectId")
        val task = Task.create(TaskId.of(UUID.randomUUID().toString()), clock.instant(), ProjectId.of(projectId), feature, requirement, agent = Task.CLAUDE_APP)
        store.saveTask(task)
        tasks[task.id.value] = task
        events[task.id.value] = Collections.synchronizedList(ArrayList())
        return task
    }

    @Synchronized
    override fun claim(taskId: String, runnerId: String): Assignment? {
        val task = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        if (task.runsOnRunner()) throw InvalidTaskStateException("Runner가 실행하는 작업입니다")
        val stage = task.current() ?: return null
        if (task.status != TaskStatus.ACTIVE) return null
        val running = when (stage.status) {
            StageStatus.RUNNING -> task
            StageStatus.PENDING -> {
                if (task.hitRunLimit()) {
                    update(taskId) { it.stopForRunLimit() }
                    return null
                }
                val assigned = task.assign(RunnerId.of(runnerId))
                val execution = ExecutionRecord(UUID.randomUUID().toString(), Task.CLAUDE_APP, AGENT_EFFORT, Task.CLAUDE_APP, workload = "${stage.stage.name}:${Task.CLAUDE_APP}")
                val next = assigned.updateCurrent(checkNotNull(assigned.current()).recordExecution(execution))
                store.saveTask(next)
                tasks[taskId] = next
                next
            }
            else -> return null
        }
        val current = checkNotNull(running.current())
        val sample = if (stage.status == StageStatus.PENDING) grantSample(running, current) else
            projectSamples[running.projectId.value].orEmpty().firstOrNull { it.executionId == current.executionId }
        val pending = current.pendingMessage
        val prompt = if (pending != null) PromptTemplates.followUp(running, current.stage, pending, current.feedback)
        else PromptTemplates.build(running, current.stage, current.feedback)
        return Assignment(
            running.id.value, running.projectId.value, current.stage.name, current.attempt.value, running.feature.value,
            Task.CLAUDE_APP, AGENT_EFFORT, prompt, executionId = current.executionId, baseBranch = running.baseBranch?.value,
            reviewPolicy = reviewPolicy(running.projectId.value).copy(sample = sample),
        )
    }

    private fun grantSample(task: Task, stage: com.dochiri.ilsim.task.domain.StageState): ReviewSampleGrant? {
        if (stage.stage.name !in setOf("TEST_CODE", "IMPLEMENTATION")) return null
        val executionId = stage.executionId ?: return null
        val projectId = task.projectId.value
        val codeExecutions = tasks.values.filter { it.projectId == task.projectId }.sumOf { t ->
            t.stages.filter { it.stage.name in setOf("TEST_CODE", "IMPLEMENTATION") }.sumOf { it.executions.size }
        }
        val sample = reviewCostPolicy.grant(UUID.randomUUID().toString(), executionId, codeExecutions, projectSamples[projectId].orEmpty()) ?: return null
        val project = projects.getValue(projectId)
        val reservations = projectSamples[projectId].orEmpty() + sample
        store.saveProject(projectId, StoredProject(project.name, project.runnerId, seen[projectId] ?: clock.instant(), projectUsage[projectId].orEmpty(), reservations))
        projectSamples[projectId] = reservations
        return sample
    }

    private fun register(project: Project, runnerId: String, now: Instant) {
        val previous = projects.put(project.id, Project(project.id, project.name, runnerId))
        seen[project.id] = now
        val saved = savedSeen[project.id]
        val changed = previous == null || previous.name != project.name || previous.runnerId != runnerId
        if (changed || saved == null || saved.plus(PROJECT_SAVE_INTERVAL).isBefore(now)) {
            try {
                store.saveProject(project.id, StoredProject(project.name, runnerId, now, projectUsage[project.id].orEmpty(), projectSamples[project.id].orEmpty()))
                savedSeen[project.id] = now
            } catch (e: RuntimeException) {
                log.warn("프로젝트 저장에 실패했습니다: {}", e.message)
            }
        }
    }

    override fun addEvent(taskId: String, stage: String?, type: String?, data: String?) {
        addIdentifiedEvent(taskId, stage, type, data, null)
    }

    @Synchronized
    override fun addIdentifiedEvent(taskId: String, stage: String?, type: String?, data: String?, eventId: String?) {
        val list = eventList(taskId) ?: return
        if (eventId != null && list.any { it.eventId == eventId }) return
        val event = TaskEvent(clock.instant(), stage, type, data, eventId)
        if (eventId != null) {
            store.appendEvent(taskId, event)
            list.add(event)
            return
        }
        list.add(event)
        try {
            store.appendEvent(taskId, event)
        } catch (e: RuntimeException) {
            log.warn("이벤트 저장에 실패했습니다: {}", e.message)
        }
    }

    @Synchronized
    override fun report(taskId: String, command: ReportStageCommand) {
        val before = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        val stage = command.stage ?: before.current()?.stage?.name
        val raw = command.toOutcome()
        val outcome = if (raw.executionId == null && before.isRunning()) raw.copy(executionId = before.current()?.executionId, stage = before.current()?.stage?.name) else raw
        val next = before.report(outcome)
        if (next === before) return
        val verdict = listOf(if (command.passed) "PASSED" else "FAILED") + command.validation.orEmpty().map { (k, v) -> "$k=$v" }
        val reportEvents = mutableListOf(TaskEvent(clock.instant(), stage, "verdict", verdict.joinToString("\n"), outcome.executionId?.let { "$it:verdict" }))
        command.error?.takeIf { !command.passed && it.isNotBlank() }?.let {
            reportEvents += TaskEvent(clock.instant(), stage, "failure", it, outcome.executionId?.let { id -> "$id:failure" })
        }
        store.saveOutcome(next, reportEvents)
        tasks[taskId] = next
        eventList(taskId)?.addAll(reportEvents)
        fun knownCost(task: Task) = task.stages.sumOf { s -> s.usage.sumOf { it.chargedUsd ?: 0.0 } }
        if (costAlertUsd.isFinite() && costAlertUsd > 0 && knownCost(before) < costAlertUsd && knownCost(next) >= costAlertUsd) {
            addEvent(taskId, stage, "cost-alert", "확인된 AI 비용이 작업 알림 기준 $${costAlertUsd}에 도달했습니다 (현재 $${knownCost(next)})")
        }
    }

    @Synchronized
    override fun approve(taskId: String) {
        update(taskId) { it.approve() }
    }

    @Synchronized
    fun message(taskId: String, text: String?) {
        val next = update(taskId) { it.message(text) }
        val stage = checkNotNull(next.current())
        addEvent(taskId, stage.stage.name, "message", stage.messages.last())
    }

    @Synchronized
    override fun cancel(taskId: String, reason: String?) {
        val next = update(taskId) { it.cancel() }
        addEvent(taskId, next.current()?.stage?.name, "log", "사용자가 작업을 취소했습니다" + reason?.takeIf { it.isNotBlank() }?.let { ": ${it.trim()}" }.orEmpty())
    }

    @Synchronized
    override fun reject(taskId: String, feedback: String?) {
        update(taskId) { it.reject(feedback) }
    }

    @Synchronized
    override fun retry(taskId: String, instruction: String?) {
        val next = update(taskId) { it.retry(instruction) }
        val text = instruction?.trim()?.takeIf { it.isNotEmpty() } ?: return
        addEvent(taskId, next.current()?.stage?.name, "message", text)
    }

    @Synchronized
    override fun reopen(taskId: String, stage: String?, feedback: String?) {
        val target = Stage.entries.firstOrNull { it.name == stage } ?: throw InvalidTaskRequestException("알 수 없는 단계: $stage")
        update(taskId) { it.reopen(target, feedback) }
        addEvent(taskId, target.name, "message", "단계를 되돌림: ${feedback.orEmpty().trim()}")
    }

    @Synchronized
    fun stop(taskId: String) {
        val next = update(taskId) { it.stop() }
        addEvent(taskId, next.current()?.stage?.name, "log", Task.STOPPED_MESSAGE)
    }

    @Synchronized
    fun delete(taskId: String) {
        val task = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        if (task.isRunning()) throw InvalidTaskStateException("실행 중인 작업은 먼저 중지하세요")
        tasks.remove(taskId)
        events.remove(taskId)
        try {
            store.deleteTask(taskId)
        } catch (e: RuntimeException) {
            log.warn("작업 삭제를 저장하지 못했습니다(다시 띄우면 되살아날 수 있음): {}", e.message)
        }
    }

    @Synchronized
    fun qualityFeedback(taskId: String, executionId: String, passed: Boolean, reason: String) {
        val before = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        val next = before.qualityFeedback(executionId, passed, reason)
        store.saveTask(next)
        tasks[taskId] = next
        addEvent(taskId, null, "quality-feedback", "$executionId: ${if (passed) "PASSED" else "REGRESSION"}: $reason")
    }

    override fun reviewPolicy(projectId: String): ReviewPolicySnapshot = reviewCostPolicy.snapshot(
        tasks.values.filter { it.projectId.value == projectId }.sortedBy { it.createdAt }.flatMap { task -> task.stages.flatMap { it.usage } } + projectUsage[projectId].orEmpty())

    @Synchronized
    fun reviewFeedback(taskId: String, usageId: String, quality: ReviewQuality) {
        val task = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        val next = task.reviewFeedback(usageId, quality)
        store.saveTask(next)
        tasks[taskId] = next
        addEvent(taskId, null, "review-feedback", "$usageId: $quality")
    }

    @Synchronized
    fun projectReviewFeedback(projectId: String, usageId: String, quality: ReviewQuality) {
        quality.validate()
        val project = projects[projectId] ?: throw InvalidTaskRequestException("등록되지 않은 프로젝트: $projectId")
        val calls = projectUsage[projectId].orEmpty()
        val reviewId = calls.firstOrNull { it.id == usageId }?.review?.id ?: throw InvalidTaskRequestException("분석 검토 호출을 찾을 수 없습니다")
        val next = calls.map { if (it.review?.id == reviewId) it.copy(review = it.review.copy(quality = quality)) else it }
        store.saveProject(projectId, StoredProject(project.name, project.runnerId, seen[projectId] ?: clock.instant(), next, projectSamples[projectId].orEmpty()))
        projectUsage[projectId] = next
    }

    fun projectReviews(projectId: String): List<AiUsage> = projectUsage[projectId].orEmpty().filter { it.review != null }

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
        const val AGENT_EFFORT = "user"
        const val EVENT_LOAD_LIMIT = 1000
        val PROJECT_SAVE_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
