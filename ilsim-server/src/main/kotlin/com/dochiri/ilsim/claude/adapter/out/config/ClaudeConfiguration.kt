package com.dochiri.ilsim.claude.adapter.out.config

import com.dochiri.ilsim.claude.adapter.out.memory.InMemoryClaudeAccountStore
import com.dochiri.ilsim.claude.adapter.out.persistence.JdbcClaudeEnvironmentStore
import com.dochiri.ilsim.claude.adapter.out.persistence.JdbcClaudeSkillStore
import com.dochiri.ilsim.claude.application.port.`in`.ClaudeUseCase
import com.dochiri.ilsim.claude.application.usecase.ClaudeService
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper

@Configuration(proxyBeanMethods = false)
class ClaudeConfiguration {

    @Bean
    fun claudeUseCase(jdbc: JdbcTemplate, clock: Clock): ClaudeUseCase {
        val mapper = JsonMapper.builder().build()
        return ClaudeService(JdbcClaudeEnvironmentStore(jdbc), JdbcClaudeSkillStore(jdbc, mapper), InMemoryClaudeAccountStore(), { text ->
            runCatching { mapper.readTree(text).isObject }.getOrDefault(false)
        }, clock)
    }
}
