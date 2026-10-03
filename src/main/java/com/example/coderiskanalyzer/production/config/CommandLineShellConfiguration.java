package com.example.coderiskanalyzer.production.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.shell.core.NonInteractiveShellRunner;
import org.springframework.shell.core.ShellRunner;
import org.springframework.shell.core.command.CommandParser;
import org.springframework.shell.core.command.CommandRegistry;

@Configuration
public class CommandLineShellConfiguration {

    @Bean
    @Primary
    ShellRunner commandLineShellRunner(CommandParser commandParser, CommandRegistry commandRegistry) {
        return new NonInteractiveShellRunner(commandParser, commandRegistry);
    }
}
