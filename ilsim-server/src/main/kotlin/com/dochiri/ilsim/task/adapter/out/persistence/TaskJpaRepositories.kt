package com.dochiri.ilsim.task.adapter.out.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface TaskJpaRepository : JpaRepository<TaskEntity, String> {
    fun findAllByOrderByCreatedAtAsc(): List<TaskEntity>
}

interface TaskEventJpaRepository : JpaRepository<TaskEventEntity, Long> {
    fun findByTaskIdOrderByIdDesc(taskId: String, page: Pageable): List<TaskEventEntity>
}

interface ProjectJpaRepository : JpaRepository<ProjectEntity, String>
