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
import com.example.coderiskanalyzer.production.report.SarifReporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

class SarifReporterTests {

    @Test
    void rendersSarif21WithRuleMetadataLocationsAndFindingProperties() throws Exception {
        Path root = Path.of("/workspace/project");
        Path source = root.resolve("src/main/java/My Service.java");
        Finding finding = new Finding("TX-001", RiskCategory.TRANSACTION, Severity.HIGH, Confidence.HIGH,
                "Self invocation", "A transaction may be bypassed.",
                new SourceLocation(source, 12, 1, 12, 10),
                List.of(new Evidence("CALL", "Internal call", new SourceLocation(source, 12, 1, 12, 10),
                        Map.of("method", "save"))), FindingStatus.CONFIRMED);

        var json = new ObjectMapper().readTree(new SarifReporter().report(root, 3, List.of(finding)));
        assertValidSarif(json.toString());

        assertThat(json.path("$schema").asText()).isEqualTo("https://json.sarif.org/sarif-2.1.0.json");
        assertThat(json.path("version").asText()).isEqualTo("2.1.0");
        var run = json.path("runs").get(0);
        assertThat(run.path("properties").path("filesScanned").asInt()).isEqualTo(3);
        assertThat(run.path("tool").path("driver").path("name").asText()).isEqualTo("Code Risk Analyzer");
        assertThat(run.path("tool").path("driver").path("rules")).hasSize(1);
        assertThat(run.path("tool").path("driver").path("rules").get(0).path("id").asText()).isEqualTo("TX-001");
        var result = run.path("results").get(0);
        assertThat(result.path("ruleId").asText()).isEqualTo("TX-001");
        assertThat(result.path("ruleIndex").asInt()).isZero();
        assertThat(result.path("level").asText()).isEqualTo("error");
        assertThat(result.path("locations").get(0).path("physicalLocation")
                .path("artifactLocation").path("uri").asText()).isEqualTo("src/main/java/My%20Service.java");
        assertThat(result.path("locations").get(0).path("physicalLocation").path("region")
                .path("startLine").asInt()).isEqualTo(12);
        assertThat(result.path("properties").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(result.path("properties").path("evidence").get(0).path("type").asText()).isEqualTo("CALL");
    }

    @Test
    void omitsRegionWhenLocationIsUnknownAndCanRenderEmptyResults() throws Exception {
        Path root = Path.of("/workspace/project");
        Finding finding = new Finding("SEC-001", RiskCategory.SECURITY, Severity.MEDIUM, Confidence.LOW,
                "Finding", "Message", new SourceLocation(root.resolve("Unknown.java"), -1, -1, -1, -1),
                List.of(new Evidence("EVIDENCE", "Details", new SourceLocation(
                        root.resolve("Unknown.java"), -1, -1, -1, -1), Map.of())), FindingStatus.REVIEW);
        var mapper = new ObjectMapper();

        var withFinding = mapper.readTree(new SarifReporter().report(root, 1, List.of(finding)));
        var withoutFindings = mapper.readTree(new SarifReporter().report(root, 1, List.of()));
        assertValidSarif(withFinding.toString());
        assertValidSarif(withoutFindings.toString());

        assertThat(withFinding.path("runs").get(0).path("results").get(0).path("locations").get(0)
                .path("physicalLocation").has("region")).isFalse();
        assertThat(withoutFindings.path("runs").get(0).path("tool").path("driver").path("rules")).isEmpty();
        assertThat(withoutFindings.path("runs").get(0).path("results")).isEmpty();
    }

    private void assertValidSarif(String report) {
        var schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        var schema = schemaRegistry.getSchema(SchemaLocation.of("classpath:schemas/sarif-schema-2.1.0.json"));

        assertThat(schema.validate(report, InputFormat.JSON)).isEmpty();
    }
}
