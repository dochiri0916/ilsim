package com.dochiri.ilsim.task.adapter.out.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

interface TaskJpaRepository : JpaRepository<TaskEntity, String> {
    fun findAllByOrderByCreatedAtAsc(): List<TaskEntity>
}

interface TaskEventJpaRepository : JpaRepository<TaskEventEntity, Long> {
    fun existsByTaskIdAndEventId(taskId: String, eventId: String): Boolean
    fun findByTaskIdOrderByIdDesc(taskId: String, page: Pageable): List<TaskEventEntity>

    @Modifying
    @Transactional
    @Query("delete from TaskEventEntity e where e.taskId = :taskId")
    fun deleteAllOfTask(taskId: String): Int
}

interface ProjectJpaRepository : JpaRepository<ProjectEntity, String>
