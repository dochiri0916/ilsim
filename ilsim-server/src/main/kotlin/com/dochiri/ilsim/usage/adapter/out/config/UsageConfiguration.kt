package com.dochiri.ilsim.usage.adapter.out.config

import com.dochiri.ilsim.usage.adapter.out.memory.InMemoryPlanUsageStore
import com.dochiri.ilsim.usage.application.port.`in`.PlanUsageUseCase
import com.dochiri.ilsim.usage.application.port.out.PlanUsageStore
import com.dochiri.ilsim.usage.application.usecase.PlanUsageService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class UsageConfiguration {

    @Bean
    fun planUsageStore(): PlanUsageStore = InMemoryPlanUsageStore()

    @Bean
    fun planUsageUseCase(store: PlanUsageStore): PlanUsageUseCase = PlanUsageService(store)
}
