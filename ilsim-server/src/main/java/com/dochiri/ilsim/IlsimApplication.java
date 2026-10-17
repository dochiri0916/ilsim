package com.dochiri.ilsim;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class IlsimApplication {

    public static void main(String[] args) {
        SpringApplication.run(IlsimApplication.class, args);
    }

    @Bean
    ToolCallbackProvider pingTools(PingTool pingTool) {
        return MethodToolCallbackProvider.builder().toolObjects(pingTool).build();
    }
}
