package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.port.out.StoredProject
import com.dochiri.ilsim.task.application.result.Assignment
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.Project
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.vo.StageStatus
import com.dochiri.ilsim.task.domain.vo.TaskStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
}

object NoStore : TaskStore {
    override fun loadTasks(): List<Task> = emptyList()
    override fun saveTask(task: Task) {}
    override fun loadEvents(taskId: String, limit: Int): List<TaskEvent> = emptyList()
    override fun appendEvent(taskId: String, event: TaskEvent) {}
    override fun loadProjects(): Map<String, StoredProject> = emptyMap()
    override fun saveProject(id: String, project: StoredProject) {}
}

fun TaskService.latest(task: Task): Task = checkNotNull(find(task.id.value))

fun TaskService.poll(runnerId: String, reported: List<Project>): Assignment? {
    register(runnerId, reported)
    val ids = reported.map { it.id }.toSet()
    val next = list().filter { it.projectId.value in ids && it.status == TaskStatus.ACTIVE && it.current()?.status == StageStatus.PENDING }.minByOrNull { it.createdAt } ?: return null
    return claim(next.id.value, runnerId)
}

fun TaskService.create(projectId: String, feature: String?, requirement: String?): Task = createForAgent(projectId, feature, requirement)
