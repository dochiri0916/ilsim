package com.dochiri.ilsim.task.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "tasks")
final class TaskEntity private constructor(
    id: String,
    projectId: String,
    status: String,
    createdAt: Instant,
    updatedAt: Instant,
    snapshot: String,
) {

    @field:Id
    @field:Column(name = "id", nullable = false, length = 36)
    final var id: String = id
        private set

    @field:Version
    @field:Column(name = "version", nullable = false)
    final var version: Long? = null
        private set

    @field:Column(name = "project_id", nullable = false, length = 100)
    final var projectId: String = projectId
        private set

    @field:Column(name = "status", nullable = false, length = 20)
    final var status: String = status
        private set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    final var createdAt: Instant = createdAt
        private set

    @field:Column(name = "updated_at", nullable = false)
    final var updatedAt: Instant = updatedAt
        private set

    @field:Column(name = "snapshot", nullable = false, columnDefinition = "LONGTEXT")
    final var snapshot: String = snapshot
        private set

    fun update(status: String, updatedAt: Instant, snapshot: String) {
        this.status = status
        this.updatedAt = updatedAt
        this.snapshot = snapshot
    }

    companion object {
        fun of(id: String, projectId: String, status: String, createdAt: Instant, updatedAt: Instant, snapshot: String): TaskEntity =
            TaskEntity(id, projectId, status, createdAt, updatedAt, snapshot)
    }
}

@Entity
@Table(name = "task_events")
final class TaskEventEntity private constructor(
    taskId: String,
    at: Instant,
    stage: String?,
    type: String?,
    data: String?,
    eventId: String?,
) {
    @field:Column(name = "event_id", length = 80)
    final var eventId: String? = eventId
        private set

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    final var id: Long? = null
        private set

    @field:Column(name = "task_id", nullable = false, length = 36)
    final var taskId: String = taskId
        private set

    @field:Column(name = "at", nullable = false)
    final var at: Instant = at
        private set

    @field:Column(name = "stage", length = 30)
    final var stage: String? = stage
        private set

    @field:Column(name = "type", length = 30)
    final var type: String? = type
        private set

    @field:Column(name = "data", columnDefinition = "MEDIUMTEXT")
    final var data: String? = data
        private set

    companion object {
        fun of(taskId: String, at: Instant, stage: String?, type: String?, data: String?, eventId: String? = null): TaskEventEntity =
            TaskEventEntity(taskId, at, stage, type, data, eventId)
    }
}

@Entity
@Table(name = "projects")
final class ProjectEntity private constructor(
    id: String,
    name: String,
    runnerId: String?,
    lastSeen: Instant,
) {
    @field:Column(name = "usage_snapshot", columnDefinition = "LONGTEXT")
    final var usageSnapshot: String? = null
        private set

    fun recordUsage(snapshot: String) { usageSnapshot = snapshot }

    @field:Id
    @field:Column(name = "id", nullable = false, length = 100)
    final var id: String = id
        private set

    @field:Column(name = "name", nullable = false, length = 200)
    final var name: String = name
        private set

    @field:Column(name = "runner_id", length = 100)
    final var runnerId: String? = runnerId
        private set

    @field:Column(name = "last_seen", nullable = false)
    final var lastSeen: Instant = lastSeen
        private set

    fun update(name: String, runnerId: String?, lastSeen: Instant) {
        this.name = name
        this.runnerId = runnerId
        this.lastSeen = lastSeen
    }

    companion object {
        fun of(id: String, name: String, runnerId: String?, lastSeen: Instant): ProjectEntity =
            ProjectEntity(id, name, runnerId, lastSeen)
    }
}
