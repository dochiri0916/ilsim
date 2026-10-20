package com.dochiri.ilsim.task.adapter.out.config

import com.dochiri.ilsim.jev.api.JevGateway
import com.dochiri.ilsim.task.adapter.out.jev.JevComplexityAssessor
import com.dochiri.ilsim.task.adapter.out.persistence.JpaTaskStore
import com.dochiri.ilsim.task.adapter.out.persistence.ProjectJpaRepository
import com.dochiri.ilsim.task.adapter.out.persistence.TaskEventJpaRepository
import com.dochiri.ilsim.task.adapter.out.persistence.TaskJpaRepository
import com.dochiri.ilsim.task.adapter.out.persistence.TaskSchema
import com.dochiri.ilsim.task.application.port.out.ComplexityAssessor
import com.dochiri.ilsim.task.application.port.out.TaskStore
import com.dochiri.ilsim.task.application.usecase.ModelSelector
import com.dochiri.ilsim.task.application.usecase.TaskService
import com.dochiri.ilsim.task.domain.ModelTier
import com.dochiri.ilsim.task.domain.ReasoningEffort
import com.dochiri.ilsim.task.domain.CostRoutingPolicy
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
    fun complexityAssessor(jev: JevGateway): ComplexityAssessor = JevComplexityAssessor(jev)

    @Bean
    fun modelSelector(
        assessor: ComplexityAssessor,
        @Value("\${ILSIM_MIN_MODEL:haiku}") minimum: String,
        @Value("\${ILSIM_ROUTING_POLICY:cost-v1}") policyVersion: String,
        @Value("\${ILSIM_ROUTING_MIN_SAMPLES:5}") samples: Int,
        @Value("\${ILSIM_ROUTING_HISTORY:100}") history: Int,
        @Value("\${ILSIM_ROUTING_QUALITY:0.9}") quality: Double,
        @Value("\${ILSIM_ROUTING_CALIBRATE:true}") calibrate: Boolean,
        @Value("\${ILSIM_ROUTING_ALLOW_UNVERIFIED:false}") allowUnverified: Boolean,
        @Value("\${ILSIM_ROUTING_COMPARE_EVERY:5}") compareEvery: Int,
        @Value("\${ILSIM_ROUTING_COMPARE_CANDIDATES:15}") compareCandidates: Int,
    ): ModelSelector = ModelSelector(assessor, ModelTier.parse(minimum, ModelTier.HAIKU), CostRoutingPolicy(policyVersion, samples.coerceAtLeast(1), history.coerceAtLeast(1), quality.coerceIn(0.5, 1.0), calibrate, allowUnverified, compareEvery.coerceAtLeast(1), compareCandidates.coerceIn(1, 15)))

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
        selector: ModelSelector,
        store: TaskStore,
        clock: Clock,
        @Value("\${ILSIM_EFFORT:medium}") effort: String,
        @Value("\${ILSIM_TASK_COST_ALERT_USD:0}") costAlertUsd: Double,
        reviewCostPolicy: ReviewCostPolicy,
        jdbc: JdbcTemplate,
    ): TaskService = TaskService(selector, store, clock, ReasoningEffort.parse(effort), costAlertUsd, reviewCostPolicy,
        com.dochiri.ilsim.task.adapter.out.persistence.JdbcRoutingAdjustmentStore(jdbc)).also { it.restore() }
}
