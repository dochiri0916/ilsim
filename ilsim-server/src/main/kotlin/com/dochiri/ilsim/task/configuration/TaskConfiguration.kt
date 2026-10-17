package com.dochiri.ilsim.task.configuration

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

    /** 모델 선택의 최소 등급. 환경 변수 ILSIM_MIN_MODEL (haiku | sonnet | opus), 기본 sonnet. */
    @Bean
    fun modelSelector(assessor: ComplexityAssessor, @Value("\${ILSIM_MIN_MODEL:sonnet}") minimum: String): ModelSelector =
        ModelSelector(assessor, ModelTier.parse(minimum, ModelTier.SONNET))

    /** 추론 강도는 기본 medium. 환경 변수 ILSIM_EFFORT 로 바꿀 수 있다. */
    @Bean
    fun taskService(
        selector: ModelSelector,
        store: TaskStore,
        clock: Clock,
        @Value("\${ILSIM_EFFORT:medium}") effort: String,
    ): TaskService = TaskService(selector, store, clock, ReasoningEffort.parse(effort)).also { it.restore() }
}
