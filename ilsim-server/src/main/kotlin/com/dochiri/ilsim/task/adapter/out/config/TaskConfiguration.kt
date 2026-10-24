package com.dochiri.ilsim.task.adapter.out.config

import com.dochiri.ilsim.task.adapter.out.persistence.JpaTaskStore
import com.dochiri.ilsim.task.adapter.out.persistence.ProjectJpaRepository
import com.dochiri.ilsim.task.adapter.out.persistence.TaskEventJpaRepository
import com.dochiri.ilsim.task.adapter.out.persistence.TaskJpaRepository
import com.dochiri.ilsim.task.adapter.out.persistence.TaskSchema
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ReviewCostPolicy
import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration(proxyBeanMethods = false)
class TaskConfiguration {

    @Bean
    fun taskSchema(jdbc: JdbcTemplate): TaskSchema = TaskSchema(jdbc)

    @Bean
    fun taskStore(
        schema: TaskSchema,
        tasks: TaskJpaRepository,
        events: TaskEventJpaRepository,
        projects: ProjectJpaRepository,
    ): TaskStore = JpaTaskStore(schema, tasks, events, projects)

    @Bean
    fun reviewCostPolicy(
        @Value("\${ILSIM_REVIEW_POLICY:review-cost-v2-support-5.5}") version: String,
        @Value("\${ILSIM_REVIEW_MIN_SAMPLES:5}") samples: Int,
        @Value("\${ILSIM_REVIEW_HISTORY:100}") history: Int,
        @Value("\${ILSIM_REVIEW_QUALITY:0.97}") quality: Double,
        @Value("\${ILSIM_REVIEW_COMPARE_EVERY:5}") compareEvery: Int,
        @Value("\${ILSIM_REVIEW_CALIBRATE:true}") calibrate: Boolean,
        @Value("\${ILSIM_REVIEW_SAMPLE_EVERY:20}") sampleEvery: Int,
        @Value("\${ILSIM_REVIEW_SAMPLE_LIMIT:0}") sampleLimit: Int,
        @Value("\${ILSIM_REVIEW_SAMPLE_BUDGET_USD:0}") sampleBudget: Double,
        @Value("\${ILSIM_REVIEW_SAMPLE_MAX_USD:0.05}") sampleMax: Double,
    ): ReviewCostPolicy = ReviewCostPolicy(version, samples.coerceAtLeast(1), history.coerceAtLeast(1), quality.coerceIn(0.5, 1.0), compareEvery.coerceAtLeast(1),
        calibrate, sampleEvery.coerceAtLeast(1), sampleLimit.coerceAtLeast(0), sampleBudget, sampleMax)

    @Bean
    fun taskService(
        store: TaskStore,
        clock: Clock,
        @Value("\${ILSIM_TASK_COST_ALERT_USD:0}") costAlertUsd: Double,
        reviewCostPolicy: ReviewCostPolicy,
    ): TaskService = TaskService(store, clock, costAlertUsd, reviewCostPolicy).also { it.restore() }
}
