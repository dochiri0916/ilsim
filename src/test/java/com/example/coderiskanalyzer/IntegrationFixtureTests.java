package com.example.coderiskanalyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.coderiskanalyzer.production.cli.ScanCommand;
import com.example.coderiskanalyzer.production.cli.ScanRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest
class IntegrationFixtureTests {

    @Autowired
    private ScanCommand scanCommand;

    @TempDir
    Path tempDir;

    @Test
    void scansSpringFixtureAndReportsExpectedFindingsWithoutSafeSqlFalsePositive() throws Exception {
        copyFixture();

        var response = scanCommand.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "", "", "", false));
        var report = new ObjectMapper().readTree(response.output());
        var findings = report.path("findings");

        assertThat(response.exitCode()).isEqualTo(1);
        assertThat(findings.findValuesAsText("ruleId")).as(response.output()).contains("SEC-001", "TX-001");
        var sqlFindings = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        findings.forEach(finding -> {
            if (finding.path("ruleId").asText().equals("SEC-001")) sqlFindings.add(finding);
        });
        assertThat(sqlFindings).hasSize(1);
        assertThat(sqlFindings.getFirst().path("location").path("file").asText())
                .endsWith("OrderController.java");
    }

    private void copyFixture() throws Exception {
        Path fixture = new ClassPathResource("fixtures/integration-project").getFile().toPath();
        try (var files = Files.walk(fixture)) {
            for (Path source : files.toList()) {
                Path destination = tempDir.resolve(fixture.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(destination);
                else Files.copy(source, destination);
            }
        }
    }
}
