package com.dochiri.ilsim.task.application.port.out

import com.dochiri.ilsim.task.domain.Task
import com.dochiri.ilsim.task.domain.TaskEvent
import com.dochiri.ilsim.task.domain.AiUsage
import java.time.Instant

/** 작업·이벤트·프로젝트 저장소. 구현이 실패해도 서버는 메모리로 계속 동작하며 저장만 건너뛴다. */
interface TaskStore {
    fun loadTasks(): List<Task>

    fun saveTask(task: Task)

    fun saveOutcome(task: Task, events: List<TaskEvent>) {
        saveTask(task)
        events.forEach { appendEvent(task.id.value, it) }
    }

    fun loadEvents(taskId: String, limit: Int): List<TaskEvent>

    fun appendEvent(taskId: String, event: TaskEvent)

    /** 작업과 그 이벤트를 지운다. */
    fun deleteTask(taskId: String) {}

    /** 프로젝트 id → (이름, Runner id, 마지막으로 본 시각) */
    fun loadProjects(): Map<String, StoredProject>

    fun saveProject(id: String, project: StoredProject)
}

data class StoredProject(val name: String, val runnerId: String?, val lastSeen: Instant, val usage: List<AiUsage> = emptyList(),
    val reviewSamples: List<com.dochiri.ilsim.task.domain.ReviewSampleGrant> = emptyList())
