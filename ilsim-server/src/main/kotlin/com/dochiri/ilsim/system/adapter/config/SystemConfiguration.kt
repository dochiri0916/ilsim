package com.dochiri.ilsim.system.adapter.config

import com.dochiri.ilsim.system.adapter.`in`.mcp.PingTool
import java.time.Clock
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class SystemConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun pingTools(pingTool: PingTool): ToolCallbackProvider =
        MethodToolCallbackProvider.builder().toolObjects(pingTool).build()
}
