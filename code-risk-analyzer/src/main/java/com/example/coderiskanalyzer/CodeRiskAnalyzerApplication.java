package com.example.coderiskanalyzer;

import com.example.coderiskanalyzer.production.cli.RulesCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.shell.core.command.annotation.EnableCommand;
import com.example.coderiskanalyzer.production.cli.ScanCommand;

@SpringBootApplication
@EnableCommand({ScanCommand.class, RulesCommand.class})
public class CodeRiskAnalyzerApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(CodeRiskAnalyzerApplication.class, args);
        int exitCode = SpringApplication.exit(context);
        if (exitCode != 0) System.exit(exitCode);
    }

}
