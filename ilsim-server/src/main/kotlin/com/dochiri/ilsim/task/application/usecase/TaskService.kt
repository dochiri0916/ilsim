package com.dochiri.ilsim.task.application.usecase

import com.dochiri.ilsim.task.application.command.ReportStageCommand
import com.dochiri.ilsim.task.application.port.`in`.ManageTaskUseCase
import com.dochiri.ilsim.task.application.port.`in`.QueryTaskUseCase
import com.dochiri.ilsim.task.application.port.`in`.RunnerUseCase
import com.dochiri.ilsim.task.application.port.out.StoredProject
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.application.port.out.RoutingAdjustmentStore
import com.dochiri.ilsim.task.application.port.`in`.RoutingOverview
import com.dochiri.ilsim.task.application.port.`in`.RoutingUseCase
import com.dochiri.ilsim.task.domain.CostRoutingPolicy
import com.dochiri.ilsim.task.domain.ModelCatalog
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.RoutingAdjustment
import com.dochiri.ilsim.task.domain.RoutingAdjustments
import com.dochiri.ilsim.task.domain.RoutingReport
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.application.result.Cleanup
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.ProjectStatus
import com.dochiri.ilsim.task.domain.PromptTemplates
import com.dochiri.ilsim.task.domain.Stats
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.ExecutionRecord
import com.dochiri.ilsim.task.domain.AiUsage
import com.dochiri.ilsim.task.domain.ReviewCostPolicy
import com.dochiri.ilsim.task.domain.ReviewPolicySnapshot
import com.dochiri.ilsim.task.domain.ReviewSampleGrant
import com.dochiri.ilsim.task.domain.ReviewQuality
import com.dochiri.ilsim.task.api.ProjectUsageRecorder
import com.dochiri.ilsim.task.api.ProjectUsageReport
import com.dochiri.ilsim.task.domain.vo.ProjectId
import com.dochiri.ilsim.task.domain.vo.RunnerId
import com.dochiri.ilsim.task.domain.vo.TaskId
import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.exception.TaskNotFoundException
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
    private val selector: ModelSelector,
    private val store: TaskStore,
    private val clock: Clock,
    private val effort: String,
    private val costAlertUsd: Double = 0.0,
    private val reviewCostPolicy: ReviewCostPolicy = ReviewCostPolicy(),
    private val adjustmentStore: RoutingAdjustmentStore? = null,
) : ManageTaskUseCase, QueryTaskUseCase, RunnerUseCase, ProjectUsageRecorder, RoutingUseCase {

    private val adjustmentHistory = Collections.synchronizedList(ArrayList<RoutingAdjustments>())

    private val tasks = ConcurrentHashMap<String, Task>()
    private val events = ConcurrentHashMap<String, MutableList<TaskEvent>>()
    private val projects = ConcurrentHashMap<String, Project>()
    private val seen = ConcurrentHashMap<String, Instant>()
    private val savedSeen = ConcurrentHashMap<String, Instant>()

    private val cleanups = Collections.synchronizedList(ArrayList<Cleanup>())
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
            adjustmentStore?.loadAll()?.let { saved ->
                adjustmentHistory.addAll(saved)
                saved.lastOrNull()?.let { selector.adjustments = it }
            }
        } catch (e: RuntimeException) {
            log.warn("저장된 작업을 읽지 못해 빈 상태로 시작합니다: {}", e.message)
        }
    }

    override fun create(projectId: String, feature: String?, requirement: String?, continueFrom: String?): Task {
        if (!projects.containsKey(projectId)) throw InvalidTaskRequestException("등록되지 않은 프로젝트: $projectId")
        val base = continueFrom?.let { id ->
            val previous = tasks[id] ?: throw TaskNotFoundException(id)
            if (previous.projectId.value != projectId) throw InvalidTaskRequestException("다른 프로젝트의 작업에서 이어 갈 수 없습니다")
            previous.branch
        }
        val task = Task.create(TaskId.of(UUID.randomUUID().toString()), clock.instant(), ProjectId.of(projectId), feature, requirement, base)
        tasks[task.id.value] = task
        events[task.id.value] = Collections.synchronizedList(ArrayList())
        persist(task)
        return task
    }

    override fun find(taskId: String): Task? = tasks[taskId]

    override fun stats(projectId: String?): Stats {
        val selected = if (projectId == null) tasks.values else tasks.values.filter { it.projectId.value == projectId }
        val analysis = if (projectId == null) projectUsage.values.flatten() else projectUsage[projectId].orEmpty()
        val reservations = (if (projectId == null) projectSamples.values.flatten() else projectSamples[projectId].orEmpty()).filter { it.policyVersion == reviewCostPolicy.version }
        return Stats.of(selected, analysis).copy(reviewProfiles = reviewCostPolicy.snapshot(selected.flatMap { task -> task.stages.flatMap { it.usage } } + analysis).profiles,
            reviewSamplesReserved = reservations.size, reviewSampleBudgetReservedUsd = reservations.sumOf { it.maxCostUsd })
    }

    @Synchronized
    override fun recordProjectUsage(projectId: String, usage: List<ProjectUsageReport>) {
        val project = projects[projectId] ?: throw InvalidTaskRequestException("등록되지 않은 프로젝트: $projectId")
        var next = projectUsage[projectId].orEmpty()
        usage.forEach { report ->
            if (next.none { it.id == report.id }) next = next + AiUsage(report.id, "ANALYSIS_${report.purpose}", report.model, report.effort,
                costUsd = report.costUsd, inputTokens = report.inputTokens, outputTokens = report.outputTokens,
                cacheReadTokens = report.cacheReadTokens, cacheWriteTokens = report.cacheWriteTokens,
                status = report.status, questions = report.questions, toolCalls = report.toolCalls, executionId = report.executionId, review = report.review).accounted(next)
        }
        store.saveProject(projectId, StoredProject(project.name, project.runnerId, seen[projectId] ?: clock.instant(), next, projectSamples[projectId].orEmpty()))
        projectUsage[projectId] = next
    }

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
    override fun poll(runnerId: String, reported: List<Project>): Assignment? {
        val now = clock.instant()
        reported.forEach { register(it, runnerId, now) }
        val ids = reported.map { it.id }.toSet()

        tasks.values.filter { it.hitRunLimit() }.forEach { update(it.id.value) { task -> task.stopForRunLimit() } }
        val chosen = tasks.values
            .filter { it.projectId.value in ids && it.isRunnable() }
            .minByOrNull { it.createdAt } ?: return null

        var assigned = chosen.assign(RunnerId.of(runnerId))
        val pendingAssessment = if (selector.assessmentAvailable()) UUID.randomUUID().toString() else null
        if (pendingAssessment != null) assigned = assigned.updateCurrent(checkNotNull(assigned.current()).account(listOf(
            AiUsage(pendingAssessment, "JEV_ROUTING", "jev", "probability", status = "UNKNOWN"))))
        val stage = checkNotNull(assigned.current())
        store.saveTask(assigned)
        tasks[assigned.id.value] = assigned
        selector.learn(tasks.values)
        val decision = selector.select(assigned, stage, stage.attempt.value, effort.takeUnless { it == "medium" })
        val execution = ExecutionRecord(
            UUID.randomUUID().toString(), decision.tier.modelId, decision.effort, selector.policy.version,
            decision.state.routing["expectedCostUsd"]?.toDoubleOrNull(), decision.state.routing["predictedSuccess"]?.toDoubleOrNull(),
            decision.state.routing["contextHash"].orEmpty(),
            workload = "${stage.stage.name}:${decision.state.routing["complexity"]?.toDoubleOrNull()?.toInt() ?: 2}:${decision.state.routing["risk"]?.toDoubleOrNull()?.toInt() ?: 1}",
            adjustmentVersion = selector.adjustments.version.takeIf { it != "none" },
        )
        val routed = assigned.updateCurrent(decision.state.finishPendingUsage(pendingAssessment).recordExecution(execution))
        val codeExecutions = tasks.values.filter { it.projectId == routed.projectId }.sumOf { task ->
            task.stages.filter { it.stage.name in setOf("TEST_CODE", "IMPLEMENTATION") }.sumOf { it.executions.size }
        } + 1
        val sample = if (stage.stage.name in setOf("TEST_CODE", "IMPLEMENTATION")) reviewCostPolicy.grant(
            UUID.randomUUID().toString(), execution.id, codeExecutions, projectSamples[routed.projectId.value].orEmpty()) else null
        if (sample != null) {
            val projectId = routed.projectId.value
            val project = projects.getValue(projectId)
            val reservations = projectSamples[projectId].orEmpty() + sample
            store.saveProject(projectId, StoredProject(project.name, project.runnerId, seen[projectId] ?: now, projectUsage[projectId].orEmpty(), reservations))
            projectSamples[projectId] = reservations
        }
        store.saveTask(routed)
        tasks[routed.id.value] = routed
        val pending = decision.state.pendingMessage
        val prompt = if (pending != null) PromptTemplates.followUp(routed, stage.stage, pending, decision.state.feedback)
        else PromptTemplates.build(routed, stage.stage, decision.state.feedback)
        return Assignment(
            routed.id.value, routed.projectId.value, stage.stage.name, stage.attempt.value, routed.feature.value,
            decision.tier.modelId, decision.effort, prompt, decision.state.sessionId.takeIf { pending != null }, execution.id,
            routed.baseBranch?.value,
            reviewPolicy(routed.projectId.value).copy(sample = sample),
        )
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
    override fun retry(taskId: String, instruction: String?) {
        val next = update(taskId) { it.retry(instruction) }
        val text = instruction?.trim()?.takeIf { it.isNotEmpty() } ?: return
        addEvent(taskId, next.current()?.stage?.name, "message", text)
    }

    @Synchronized
    override fun stop(taskId: String) {
        val next = update(taskId) { it.stop() }
        addEvent(taskId, next.current()?.stage?.name, "log", Task.STOPPED_MESSAGE)
    }

    override fun shouldStop(taskId: String): Boolean = tasks[taskId]?.isRunning() != true

    @Synchronized
    override fun delete(taskId: String) {
        val task = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        if (task.isRunning()) throw InvalidTaskStateException("실행 중인 작업은 먼저 중지하세요")
        tasks.remove(taskId)
        events.remove(taskId)
        cleanups += Cleanup(taskId, task.projectId.value, task.feature.value, task.branch?.value, keepBranch = task.status == TaskStatus.COMPLETED)
        try {
            store.deleteTask(taskId)
        } catch (e: RuntimeException) {
            log.warn("작업 삭제를 저장하지 못했습니다(다시 띄우면 되살아날 수 있음): {}", e.message)
        }
    }

    override fun claimCleanups(projectIds: Collection<String>): List<Cleanup> = synchronized(cleanups) {
        cleanups.filter { it.projectId in projectIds }.also { cleanups.removeAll(it) }
    }

    @Synchronized
    override fun qualityFeedback(taskId: String, executionId: String, passed: Boolean, reason: String) {
        val before = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        val next = before.qualityFeedback(executionId, passed, reason)
        store.saveTask(next)
        tasks[taskId] = next
        addEvent(taskId, null, "quality-feedback", "$executionId: ${if (passed) "PASSED" else "REGRESSION"}: $reason")
    }

    override fun reviewPolicy(projectId: String): ReviewPolicySnapshot = reviewCostPolicy.snapshot(
        tasks.values.filter { it.projectId.value == projectId }.sortedBy { it.createdAt }.flatMap { task -> task.stages.flatMap { it.usage } } + projectUsage[projectId].orEmpty())

    @Synchronized
    override fun reviewFeedback(taskId: String, usageId: String, quality: ReviewQuality) {
        val task = tasks[taskId] ?: throw TaskNotFoundException(taskId)
        val next = task.reviewFeedback(usageId, quality)
        store.saveTask(next)
        tasks[taskId] = next
        addEvent(taskId, null, "review-feedback", "$usageId: $quality")
    }

    @Synchronized
    override fun projectReviewFeedback(projectId: String, usageId: String, quality: ReviewQuality) {
        quality.validate()
        val project = projects[projectId] ?: throw InvalidTaskRequestException("등록되지 않은 프로젝트: $projectId")
        val calls = projectUsage[projectId].orEmpty()
        val reviewId = calls.firstOrNull { it.id == usageId }?.review?.id ?: throw InvalidTaskRequestException("분석 검토 호출을 찾을 수 없습니다")
        val next = calls.map { if (it.review?.id == reviewId) it.copy(review = it.review.copy(quality = quality)) else it }
        store.saveProject(projectId, StoredProject(project.name, project.runnerId, seen[projectId] ?: clock.instant(), next, projectSamples[projectId].orEmpty()))
        projectUsage[projectId] = next
    }

    override fun projectReviews(projectId: String): List<AiUsage> = projectUsage[projectId].orEmpty().filter { it.review != null }

    override fun routing(projectId: String?): RoutingOverview {
        val selected = if (projectId == null) tasks.values else tasks.values.filter { it.projectId.value == projectId }
        return RoutingOverview(RoutingReport.of(selected, selector.policy), selector.adjustments, adjustmentHistory.toList().reversed(), ModelCatalog.all(), modelUpgrades.toList().reversed())
    }

    private val modelUpgrades = Collections.synchronizedList(ArrayList<String>())

    @Synchronized
    override fun reportModels(models: Map<String, String>): Map<String, String> {
        val upgraded = linkedMapOf<String, String>()
        models.forEach { (label, id) ->
            val tier = ModelTier.entries.firstOrNull { it.label == label } ?: return@forEach
            ModelCatalog.upgrade(tier, id)?.let { previous ->
                upgraded[label] = id
                modelUpgrades += "${clock.instant()} ${tier.label}: $previous → $id"
                log.info("모델 새 버전 도입: {} → {}", previous, id)
            }
        }
        return upgraded
    }

    @Synchronized
    override fun adjust(items: List<RoutingAdjustment>, note: String?): RoutingAdjustments {
        items.firstOrNull { ModelTier.of(it.model) == null || it.effort !in CostRoutingPolicy.efforts }
            ?.let { throw InvalidTaskRequestException("알 수 없거나 잠긴 모델·추론 강도: ${it.key}") }
        val next = RoutingAdjustments.of(items, note, clock.instant())
        adjustmentStore?.save(next)
        adjustmentHistory += next
        selector.adjustments = next
        return next
    }

    override fun applyRecommended(projectId: String?): RoutingAdjustments {
        val report = routing(projectId).report
        val current = selector.adjustments.items.associateBy { it.key }.toMutableMap()
        report.options.filter { it.recommendedOffset != null }.forEach { o ->
            val key = "${o.model}/${o.effort}"
            current[key] = (current[key] ?: RoutingAdjustment(o.model, o.effort)).copy(successOffset = o.recommendedOffset!!)
        }
        return adjust(current.values.toList(), "실행 이력 기반 권장 보정 적용")
    }

    override fun restore(version: String): RoutingAdjustments {
        val target = if (version == "none") RoutingAdjustments() else adjustmentHistory.lastOrNull { it.version == version }
            ?: throw InvalidTaskRequestException("조정 버전을 찾을 수 없습니다: $version")
        return adjust(target.items, "버전 $version 으로 되돌림")
    }

    override fun shouldStop(taskId: String, executionId: String?): Boolean = shouldStop(taskId) ||
        executionId != null && tasks[taskId]?.current()?.executionId != executionId

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
