package com.dochiri.ilsim.rules.adapter.out.config

import com.dochiri.ilsim.rules.adapter.out.file.FileKnowledgeStore
import com.dochiri.ilsim.rules.application.port.`in`.KnowledgeUseCase
import com.dochiri.ilsim.rules.application.port.out.KnowledgeStore
import com.dochiri.ilsim.rules.application.usecase.KnowledgeService
import java.nio.file.Path
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class RulesConfiguration {

    @Bean
    fun knowledgeStore(@Value("\${ILSIM_HOME:\${user.home}/.ilsim}") home: String): KnowledgeStore = FileKnowledgeStore(Path.of(home))

    @Bean
    fun knowledgeUseCase(store: KnowledgeStore): KnowledgeUseCase = KnowledgeService(store)
}
