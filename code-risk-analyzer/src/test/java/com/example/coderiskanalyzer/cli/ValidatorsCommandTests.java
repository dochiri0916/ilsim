package com.example.coderiskanalyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.coderiskanalyzer.production.cli.CliExitCode;
import com.example.coderiskanalyzer.production.cli.ScanApplicationRunner;
import com.example.coderiskanalyzer.production.config.AnalyzerConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class ValidatorsCommandTests {

    @TempDir
    Path project;

    private final CliExitCode exitCode = new CliExitCode();

    private String run(String... args) {
        var runner = new ScanApplicationRunner(null, exitCode, null, null, new AnalyzerConfigLoader(), null);
        PrintStream original = System.out;
        var output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        try {
            runner.run(new DefaultApplicationArguments(args));
        } finally {
            System.setOut(original);
        }
        return output.toString(StandardCharsets.UTF_8).trim();
    }

    @Test
    void printsTheValidatedSettingAsJson() throws Exception {
        assertThat(run("validators", project.toString())).isEqualTo("{\"architecture\":null,\"convention\":null}");
        Files.writeString(project.resolve(".code-risk.yml"), "validators:\n  architecture: {enabled: false}\n");
        assertThat(run("validators", project.toString())).isEqualTo("{\"architecture\":false,\"convention\":null}");
        assertThat(exitCode.getExitCode()).isZero();
    }

    @Test
    void rejectsInvalidSettingsAndArguments() throws Exception {
        Files.writeString(project.resolve(".code-risk.yml"), "validators:\n  architecture: {enabled: maybe}\n");
        assertThat(run("validators", project.toString())).startsWith("Error:").contains("validators.architecture.enabled");
        assertThat(exitCode.getExitCode()).isEqualTo(2);
        exitCode.set(0);
        assertThat(run("validators")).startsWith("Error:");
        assertThat(exitCode.getExitCode()).isEqualTo(2);
        exitCode.set(0);
        assertThat(run("validators", project.resolve("missing").toString())).startsWith("Error:");
        assertThat(exitCode.getExitCode()).isEqualTo(2);
    }
}
