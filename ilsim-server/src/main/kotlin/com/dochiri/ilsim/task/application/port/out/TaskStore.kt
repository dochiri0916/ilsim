package com.dochiri.ilsim.task.application.port.out

import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.AiUsage
import java.time.Instant

interface TaskStore {
    fun loadTasks(): List<Task>

    fun saveTask(task: Task)

    fun saveOutcome(task: Task, events: List<TaskEvent>) {
        saveTask(task)
        events.forEach { appendEvent(task.id.value, it) }
    }

    fun loadEvents(taskId: String, limit: Int): List<TaskEvent>

    fun appendEvent(taskId: String, event: TaskEvent)

    fun deleteTask(taskId: String) {}

    fun loadProjects(): Map<String, StoredProject>

    fun saveProject(id: String, project: StoredProject)
}

data class StoredProject(val name: String, val runnerId: String?, val lastSeen: Instant, val usage: List<AiUsage> = emptyList(),
    val reviewSamples: List<com.dochiri.ilsim.task.domain.ReviewSampleGrant> = emptyList())
