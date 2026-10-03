package com.example.coderiskanalyzer.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.report.JsonReporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonReporterTests {

    @Test
    void rendersStableJsonSchemaWithRelativeLocation() throws Exception {
        Path root = Path.of("/workspace/project");
        Finding finding = new Finding("TX-001", RiskCategory.TRANSACTION, Severity.HIGH, Confidence.HIGH,
                "Self invocation", "A transaction may be bypassed.",
                new SourceLocation(root.resolve("src/main/java/Service.java"), 12, 1, 12, 10),
                List.of(new Evidence("CALL", "Internal call", new SourceLocation(
                        root.resolve("src/main/java/Service.java"), 12, 1, 12, 10), Map.of("method", "save"))),
                FindingStatus.CONFIRMED);

        var json = new ObjectMapper().readTree(new JsonReporter().report(root, 3, List.of(finding)));

        assertThat(json.path("version").asText()).isEqualTo("1");
        assertThat(json.path("summary").path("filesScanned").asInt()).isEqualTo(3);
        assertThat(json.path("findings").get(0).path("location").path("file").asText())
                .isEqualTo("src/main/java/Service.java");
        assertThat(json.path("findings").get(0).path("evidence").get(0).path("attributes").path("method").asText())
                .isEqualTo("save");
    }
}
