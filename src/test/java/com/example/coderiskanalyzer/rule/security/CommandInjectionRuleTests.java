package com.example.coderiskanalyzer.rule.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowAnalyzer;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.security.CommandInjectionRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandInjectionRuleTests {

    @TempDir Path tempDir;

    @Test
    void reportsRequestInputPassedToRuntimeExec() throws Exception {
        assertThat(analyze("@RequestParam String host) { Runtime.getRuntime().exec(\"ping \" + host); }"))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("SEC-002");
                    assertThat(finding.severity().name()).isEqualTo("CRITICAL");
                });
    }

    @Test
    void reportsTaintedProcessBuilderAndIgnoresLiteralCommand() throws Exception {
        assertThat(analyze("@RequestParam String host) { new ProcessBuilder(\"ping\", host).start(); }"))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("SEC-002"));
        assertThat(analyze("@RequestParam String host) { ProcessBuilder builder = new ProcessBuilder(); "
                + "builder.command(\"ping\", host); builder.start(); }"))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("SEC-002"));
        assertThat(analyze("@RequestParam String host) { Runtime.getRuntime().exec(\"ping localhost\"); }"))
                .isEmpty();
    }

    private List<Finding> analyze(String method) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Controller.java");
        Files.writeString(file, "class Controller { void run(" + method + " }");
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
        return new CommandInjectionRule(new DataFlowAnalyzer()).analyze(context);
    }
}
