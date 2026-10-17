package com.dochiri.ilsim.task

import com.dochiri.ilsim.task.application.port.out.ComplexityAssessor
import com.dochiri.ilsim.task.application.port.out.StoredProject
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.domain.Assessment
import com.dochiri.ilsim.task.domain.StageState
import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
}

/** JEV 없이 규칙 기반으로만 동작하게 하는 평가기. */
object NoAssessor : ComplexityAssessor {
    override fun isAvailable(): Boolean = false
    override fun assess(task: Task, state: StageState, attempt: Int): Assessment? = null
}

class FakeAssessor(private val result: Assessment?) : ComplexityAssessor {
    var calls = 0
    override fun isAvailable(): Boolean = result != null
    override fun assess(task: Task, state: StageState, attempt: Int): Assessment? {
        calls++
        return result
    }
}

object NoStore : TaskStore {
    override fun loadTasks(): List<Task> = emptyList()
    override fun saveTask(task: Task) {}
    override fun loadEvents(taskId: String, limit: Int): List<TaskEvent> = emptyList()
    override fun appendEvent(taskId: String, event: TaskEvent) {}
    override fun loadProjects(): Map<String, StoredProject> = emptyMap()
    override fun saveProject(id: String, project: StoredProject) {}
}
