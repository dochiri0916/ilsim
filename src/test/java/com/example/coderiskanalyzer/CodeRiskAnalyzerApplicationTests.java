package com.example.coderiskanalyzer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.ShellRunner;

@SpringBootTest
class CodeRiskAnalyzerApplicationTests {

    @Autowired
    private CommandRegistry commandRegistry;

    @Autowired
    private ShellRunner shellRunner;

    @Test
    void contextLoads() {
    }

    @Test
    void registersScanCommand() {
        org.junit.jupiter.api.Assertions.assertNotNull(commandRegistry.getCommandByName("scan"));
        org.junit.jupiter.api.Assertions.assertNotNull(shellRunner);
        org.assertj.core.api.Assertions.assertThat(shellRunner.getClass().getSimpleName())
                .contains("NonInteractive");
    }

    @Test
    void registersRulesCommand() {
        org.junit.jupiter.api.Assertions.assertNotNull(commandRegistry.getCommandByName("rules"));
    }

    @Test
    void registersOptInMutationCommand() {
        org.junit.jupiter.api.Assertions.assertNotNull(commandRegistry.getCommandByName("mutation"));
    }

}
