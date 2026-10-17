package com.example.coderiskanalyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.example.coderiskanalyzer.production.cli.CliExitCode;
import com.example.coderiskanalyzer.production.cli.RulesCommand;
import com.example.coderiskanalyzer.production.cli.ScanApplicationRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RulesCommandTests {
    @Autowired RulesCommand command;
    private final CliExitCode exitCode = new CliExitCode();

    @Test
    void keepsExistingRuleListAndAddsDetailAndSpecificationLookup() {
        assertThat(command.rules("").lines().count()).isEqualTo(22);
        assertThat(command.rules("CON-002")).startsWith("CON-002  Lost update risk")
                .contains("Specification: DB-CONC-009  P1  PARTIAL", "Specification: JPA-CONC-018");
        assertThat(command.rules("db-conc-009")).contains("Priority: P1  Implementation: PARTIAL",
                "LostUpdateRiskRule.java", "Remaining:");
        assertThat(command.rules("JAVA-CONC-006")).contains("Implementation: PARTIAL", "Analyzer rules: CON-005");
        assertThat(command.rules("CON-005")).contains("Unsafe double-checked locking", "JAVA-CONC-006");
    }

    @Test
    void jarArgumentPathSupportsBooleanOptionsAndJson() throws Exception {
        var json = new ObjectMapper().readTree(run("rules", "--spec", "--format", "json"));
        assertThat(exitCode.getExitCode()).isZero();
        assertThat(json.path("rules")).hasSize(22);
        assertThat(json.path("workItems")).isNotEmpty();
        assertThat(run("rules", "DIST-CONC-020", "--spec=true"))
                .contains("CON-001 (requires JVM_LOCAL_LOCK)");
        assertThat(run("rules", "--spec", "false").lines().count()).isEqualTo(22);
        assertThat(run("rules", "--spec=false").lines().count()).isEqualTo(22);
        assertThat(run("rules", "--spec")).contains("Counts:", "PLANNED", "PARTIAL", "JAVA-CONC-001");
    }

    @Test
    void invalidOptionsAndUnknownIdsAreInputErrors() {
        for (String[] args : new String[][] {
                {"rules", "--spec=maybe"}, {"rules", "--format"}, {"rules", "--format", "xml"},
                {"rules", "--unknown"}, {"rules", "CON-002", "extra"}, {"rules", "missing"},
                {"rules", "CON-002", "--spec"}, {"rules", "JAVA-CONC-999", "--spec"}}) {
            exitCode.set(0);
            assertThat(run(args)).startsWith("Error:");
            assertThat(exitCode.getExitCode()).isEqualTo(2);
        }
    }

    @Test
    void rejectsRemovedOrchestrationCommand() {
        assertThat(run("validators", ".")).isEqualTo("Error: unknown command: validators");
        assertThat(exitCode.getExitCode()).isEqualTo(2);
        assertThat(run()).startsWith("Usage:").doesNotContain("validators");
        assertThat(exitCode.getExitCode()).isEqualTo(2);
    }

    private String run(String... args) {
        var runner = new ScanApplicationRunner(null, exitCode, command);
        PrintStream original = System.out;
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);
            try { runner.run(new DefaultApplicationArguments(args)); }
            finally { System.setOut(original); }
        }
        return output.toString(StandardCharsets.UTF_8).trim();
    }
}
