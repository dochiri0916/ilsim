package com.example.coderiskanalyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.cli.ScanCommand;
import com.example.coderiskanalyzer.production.cli.ScanRequest;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.report.JsonReporter;
import com.example.coderiskanalyzer.production.report.SarifReporter;
import com.example.coderiskanalyzer.production.rule.RuleEngine;
import com.example.coderiskanalyzer.production.rule.concurrency.DoubleCheckedLockingRule;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.source.SourceRootDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrencyScanTests {
    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScanCommand command = new ScanCommand(new SourceDiscoveryService(new SourceRootDetector()),
            new JavaSourceParser(), new ProgramModelBuilder(), new RuleEngine(List.of(new DoubleCheckedLockingRule())),
            List.of(new JsonReporter(), new SarifReporter()));

    @Test
    void confirmedDclUsesExistingGateAndReportsActualOutcomeAndSemanticFingerprint() throws Exception {
        project("", "new Object()", "");
        var response = scan("json", "");
        assertThat(response.exitCode()).isEqualTo(1);
        var json = mapper.readTree(response.output());
        assertThat(json.path("summary").path("findings").asInt()).isEqualTo(1);
        var finding = json.path("findings").get(0);
        assertThat(finding.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(finding.path("concurrency").path("primarySpecificationRuleId").asText()).isEqualTo("JAVA-CONC-006");
        assertThat(finding.path("concurrency").path("outcome").asText()).isEqualTo("DEFINITE_PATTERN");
        assertThat(finding.path("fingerprint").asText()).startsWith("v2|CON-005|src/main/java/Service.java|");
        assertThat(finding.path("fingerprint").asText()).isEqualTo(json.path("concurrencyAssessments").get(0).path("fingerprint").asText());
        assertThat(json.path("concurrencyAssessments").get(0).path("location").path("file").asText())
                .isEqualTo("src/main/java/Service.java");
    }

    @Test
    void protectedDclIsRecordedWithoutFindingOrGateFailureInBothFormats() throws Exception {
        project("volatile", "new Object()", "");
        for (String format : List.of("json", "sarif")) {
            var response = scan(format, "");
            assertThat(response.exitCode()).isZero();
            var json = mapper.readTree(response.output());
            assertThat(findings(json, format)).isEmpty();
            var proof = assessments(json, format).get(0);
            assertThat(proof.path("outcome").asText()).isEqualTo("SAFE_BY_PROOF");
            assertThat(proof.path("safeguards")).isNotEmpty();
            assertThat(proof.path("missingEvidence")).isEmpty();
            assertThat(proof.path("evidence").get(0).path("location").path("file").asText())
                    .isEqualTo("src/main/java/Service.java");
            if (format.equals("sarif")) assertValidSarif(response.output());
        }
    }

    @Test
    void unknownDclRemainsReviewWithMissingEvidenceAndHonorsExistingReviewPolicy() throws Exception {
        project("volatile", "factory()", "");
        var response = scan("json", "");
        assertThat(response.exitCode()).isZero();
        var finding = mapper.readTree(response.output()).path("findings").get(0);
        assertThat(finding.path("status").asText()).isEqualTo("REVIEW");
        assertThat(finding.path("concurrency").path("outcome").asText()).isEqualTo("UNKNOWN");
        assertThat(finding.path("concurrency").path("missingEvidence")).isNotEmpty();
        assertThat(finding.path("concurrency").path("safeguards")).isNotEmpty();
        project("volatile", "factory()", "fail:\n  include-review: true\n  confidence: LOW\n");
        assertThat(scan("json", "").exitCode()).isEqualTo(1);
    }

    @Test
    void suppressionAndBaselineFilteringPreserveAuditAndSarifFingerprint() throws Exception {
        project("", "new Object()", "");
        var sarif = scan("sarif", "");
        assertValidSarif(sarif.output());
        var result = mapper.readTree(sarif.output()).path("runs").get(0).path("results").get(0);
        assertThat(result.path("partialFingerprints").path("concurrency/v2").asText()).startsWith("v2|CON-005|");
        command.createBaseline(tempDir.toString(), "baseline.json");
        Path file = tempDir.resolve("src/main/java/Service.java");
        Files.writeString(file, "\n// header\n" + Files.readString(file));
        var baselined = scan("json", "baseline.json");
        assertThat(baselined.exitCode()).isZero();
        var json = mapper.readTree(baselined.output());
        assertThat(json.path("findings")).isEmpty();
        assertThat(json.path("concurrencyAssessments")).hasSize(1);
        project("", "new Object()", "suppressions:\n  - rule: CON-005\n    path: '**/Service.java'\n    reason: 'Reviewed publication'\n");
        var suppressed = scan("json", "");
        assertThat(suppressed.exitCode()).isZero();
        var finding = mapper.readTree(suppressed.output()).path("findings").get(0);
        assertThat(finding.path("status").asText()).isEqualTo("SUPPRESSED");
        assertThat(finding.path("concurrency").path("outcome").asText()).isEqualTo("DEFINITE_PATTERN");
        assertThat(finding.path("fingerprint").asText()).isEqualTo(result.path("partialFingerprints").path("concurrency/v2").asText());
    }

    @Test
    void disablingDclProducesNeitherFindingsNorAuditRecords() throws Exception {
        project("", "new Object()", "");
        Files.writeString(tempDir.resolve(".code-risk.yml"), "rules:\n  TEST-001:\n    enabled: false\n  CON-005:\n    enabled: false\n");
        var response = scan("json", "");
        assertThat(response.exitCode()).isZero();
        var json = mapper.readTree(response.output());
        assertThat(json.path("findings")).isEmpty();
        assertThat(json.path("concurrencyAssessments")).isEmpty();
    }

    @Test
    void advisoryJevReviewRetainsStaticAssessmentAndFingerprint() throws Exception {
        project("volatile", "factory()", "");
        Path root = tempDir.resolve("src/main/java");
        var sources = new JavaSourceParser().parse(List.of(new com.example.coderiskanalyzer.production.source.SourceFile(
                root, root.resolve("Service.java"))));
        var findings = new DoubleCheckedLockingRule().analyze(new com.example.coderiskanalyzer.production.analysis.AnalysisContext(
                new ProgramModelBuilder().build(sources), sources));
        var assessment = findings.getFirst().concurrencyAssessment();
        var judge = new com.example.coderiskanalyzer.production.judge.SemanticJudge() {
            @Override
            public com.example.coderiskanalyzer.production.judge.JudgeResult judge(
                    com.example.coderiskanalyzer.production.finding.Finding finding, java.util.Map<String, Object> evidence) {
                throw new AssertionError("DCL must use advisory review");
            }

            @Override
            public com.example.coderiskanalyzer.production.judge.JudgeResult reviewRisk(
                    com.example.coderiskanalyzer.production.finding.Finding finding, java.util.Map<String, Object> evidence) {
                assertThat(((java.util.Map<?, ?>) evidence.get("concurrency")).get("outcome")).isEqualTo("UNKNOWN");
                return new com.example.coderiskanalyzer.production.judge.JudgeResult(true, true, 0.99, "Advisory opinion");
            }
        };
        var reviewed = command.reviewCandidates(findings, judge).getFirst();
        assertThat(reviewed.concurrencyAssessment()).isEqualTo(assessment);
        assertThat(reviewed.status()).isEqualTo(com.example.coderiskanalyzer.production.finding.FindingStatus.REVIEW);
        assertThat(reviewed.evidence()).extracting("type").contains("JEV_RISK_REVIEW");
        assertThat(com.example.coderiskanalyzer.production.baseline.FindingFingerprint.stableOf(tempDir, reviewed))
                .isEqualTo(com.example.coderiskanalyzer.production.baseline.FindingFingerprint.stableOf(tempDir, findings.getFirst()));
    }

    private void project(String modifier, String initializer, String config) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Service.java"), "class Service { static " + modifier + " Object instance;"
                + " static Object get() { if (instance == null) { synchronized(Service.class) { if (instance == null) {"
                + " instance = " + initializer + "; } } } return instance; }"
                + " static Object factory() { return new Object(); } }");
        Files.writeString(tempDir.resolve(".code-risk.yml"), "rules:\n  TEST-001:\n    enabled: false\n" + config);
    }

    private com.example.coderiskanalyzer.production.cli.ScanResponse scan(String format, String baseline) {
        return command.scanForCli(new ScanRequest(tempDir.toString(), format, "", "false", "", baseline, "", false));
    }

    private JsonNode findings(JsonNode json, String format) {
        return format.equals("json") ? json.path("findings") : json.path("runs").get(0).path("results");
    }

    private JsonNode assessments(JsonNode json, String format) {
        return format.equals("json") ? json.path("concurrencyAssessments")
                : json.path("runs").get(0).path("properties").path("concurrencyAssessments");
    }

    private void assertValidSarif(String report) {
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        assertThat(registry.getSchema(SchemaLocation.of("classpath:schemas/sarif-schema-2.1.0.json"))
                .validate(report, InputFormat.JSON)).isEmpty();
    }
}
