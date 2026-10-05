package com.example.coderiskanalyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Files;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.cli.ScanCommand;
import com.example.coderiskanalyzer.production.cli.ScanRequest;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.source.SourceRootDetector;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.rule.RuleEngine;
import com.example.coderiskanalyzer.production.report.ConsoleReporter;
import com.example.coderiskanalyzer.production.report.JsonReporter;
import com.example.coderiskanalyzer.production.report.SarifReporter;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanCommandTests {

    @TempDir
    Path tempDir;

    private ScanCommand command() {
        return new ScanCommand(new SourceDiscoveryService(new SourceRootDetector()),
                new JavaSourceParser(), new ProgramModelBuilder(), new RuleEngine(java.util.List.of()),
                java.util.List.of(new ConsoleReporter(), new JsonReporter(), new SarifReporter()));
    }

    @Test
    void acceptsExistingDirectory() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createFile(tempDir.resolve("src/main/java/A.java"));
        Files.writeString(tempDir.resolve("src/main/java/A.java"), "class A { void run() {} }");
        assertThat(command().scan(tempDir.toString(), "console", "", "false"))
                .contains("Files scanned: 1", "Findings: 0");
    }

    @Test
    void scanDoesNotExecuteTargetGradleScripts() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(tempDir.resolve("src/main/java/A.java"), "class A { void run() {} }");
        Files.writeString(tempDir.resolve("build.gradle"), "new File('gradle-was-executed').text = 'yes'");

        var response = command().scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false", "", "", "", false));

        assertThat(response.exitCode()).isZero();
        assertThat(tempDir.resolve("gradle-was-executed")).doesNotExist();
    }

    @Test
    void changedFileScanFollowsSqlArgumentsIntoUnchangedHelpersWithoutScanningUnrelatedEntries() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Files.writeString(root.resolve("Controller.java"), "class Controller { Helper helper; "
                + "void users(@RequestParam String name) throws Exception { helper.query(name); } }");
        Files.writeString(root.resolve("Helper.java"), "import java.sql.*; class Helper { Statement stmt; "
                + "void query(String value) throws SQLException { stmt.executeQuery(\"select * from users where name='\" + value); } "
                + "void unrelated(@RequestParam String extra) throws SQLException { stmt.executeQuery(extra); } }");
        Files.writeString(tempDir.resolve("changed.txt"), "src/main/java/Controller.java\n");
        var scanner = new ScanCommand(new SourceDiscoveryService(new SourceRootDetector()),
                new JavaSourceParser(), new ProgramModelBuilder(), new RuleEngine(java.util.List.of(
                new com.example.coderiskanalyzer.production.rule.security.SqlInjectionRule(
                        new com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowAnalyzer()))),
                java.util.List.of(new JsonReporter()));
        var result = scanner.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false", "", "", "changed.txt", false));
        var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.output());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(report.path("findings")).hasSize(1);
        assertThat(report.path("findings").get(0).path("ruleId").asText()).isEqualTo("SEC-001");
        assertThat(report.path("findings").get(0).path("location").path("file").asText()).endsWith("Helper.java");
        assertThat(report.path("metrics").path("completeness").path("filesSelected").asInt()).isEqualTo(1);
        assertThat(report.path("metrics").path("completeness").path("filesParsedAsContext").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsMissingDirectory() {
        assertThat(command().scan(tempDir.resolve("missing").toString(), "console", "", "false"))
                .contains("path does not exist");
    }

    @Test
    void rejectsFilePath() throws Exception {
        Path file = tempDir.resolve("project.java");
        Files.createFile(file);

        assertThat(command().scan(file.toString(), "console", "", "false")).contains("path is not a directory");
    }

    @Test
    void reportsNoJavaSources() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));

        assertThat(command().scan(tempDir.toString(), "console", "", "false")).contains("no Java source found");
    }

    @Test
    void supportsSarifOutputFormat() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(tempDir.resolve("src/main/java/A.java"), "class A { void run() {} }");

        var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                command().scan(tempDir.toString(), "sarif", "", "false"));

        assertThat(report.path("version").asText()).isEqualTo("2.1.0");
        assertThat(report.path("runs").get(0).path("results")).isEmpty();
    }

    @Test
    void includesTestAdequacyFindingsInCliReportAndScansTestSources() throws Exception {
        Path main = tempDir.resolve("src/main/java/example");
        Path tests = tempDir.resolve("src/test/java/example");
        Files.createDirectories(main);
        Files.createDirectories(tests);
        Files.writeString(main.resolve("DemoService.java"), """
                package example;
                public class DemoService {
                    public int calculate(int value) {
                        if (value < 0) throw new IllegalArgumentException();
                        return value + 1;
                    }
                }
                """);
        Files.writeString(tests.resolve("DemoServiceTest.java"), """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class DemoServiceTest {
                    DemoService service = new DemoService();
                    @Test void positiveInput() { assertEquals(2, service.calculate(1)); }
                }
                """);

        var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                command().scan(tempDir.toString(), "json", "", "false"));
        var testResults = report.path("findings").findValues("ruleId").stream()
                .map(com.fasterxml.jackson.databind.JsonNode::asText).filter("TEST-001"::equals).toList();
        assertThat(testResults).isNotEmpty();
        assertThat(report.path("findings").toString()).contains("PARTIAL", "DemoServiceTest#positiveInput");
    }

    @Test
    void configSuppressionAndFailPolicyAreAppliedBeforeReturningCliExitStatus() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Path source = tempDir.resolve("src/main/java/A.java");
        Files.writeString(source, "class A {}");
        ScanCommand command = commandWithConfirmedFinding();

        var failing = command.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "", "", "", false));
        assertThat(failing.exitCode()).isEqualTo(1);

        Files.writeString(tempDir.resolve(".code-risk.yml"), """
                suppressions:
                  - rule: TX-003
                    path: '**/A.java'
                    reason: migration tracked in ABC-123
                """);
        var suppressed = command.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "", "", "", false));

        assertThat(suppressed.exitCode()).isZero();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(suppressed.output())
                .path("findings").get(0).path("status").asText()).isEqualTo("SUPPRESSED");
    }

    @Test
    void changedFilesListLimitsParsingAndOutputCannotEscapeProjectRoot() throws Exception {
        Path sourceRoot = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("A.java"), "class A {}");
        Files.writeString(sourceRoot.resolve("B.java"), "class B {}");
        Files.writeString(tempDir.resolve("changed.txt"), "src/main/java/B.java\n");
        ScanCommand command = command();

        var selected = command.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "", "", "changed.txt", false));
        var unsafeOutput = command.scanForCli(new ScanRequest(tempDir.toString(), "json", "../outside.json", "false",
                "", "", "", false));

        assertThat(selected.metrics().filesParsed()).isEqualTo(1);
        assertThat(unsafeOutput.exitCode()).isEqualTo(2);
        assertThat(unsafeOutput.output()).startsWith("Error:");
    }

    @Test
    void productionSeedLoadsRelatedTestsAsContext() throws Exception {
        writeAdequacyProject();
        Files.writeString(tempDir.resolve("changed.txt"), "src/main/java/example/OrderService.java\n");

        var response = command().scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "off", "", "changed.txt", false));

        assertThat(response.output()).contains("OrderServiceTest#createsOrder", "PARTIAL");
        assertThat(response.output()).doesNotContain("No test method was linked");
        assertThat(response.metrics().filesParsed()).isEqualTo(2);
    }

    @Test
    void testSeedLoadsOnlyItsResolvedProductionObligations() throws Exception {
        writeAdequacyProject();
        Files.writeString(tempDir.resolve("changed.txt"), "src/test/java/example/OrderServiceTest.java\n");
        ScanCommand command = commandWithConfirmedFinding();

        var response = command.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "off", "", "changed.txt", false));

        assertThat(response.output()).contains("TEST-001", "OrderServiceTest#createsOrder", "PARTIAL");
        assertThat(response.output()).doesNotContain("TX-003");
        assertThat(response.metrics().filesParsed()).isEqualTo(2);
    }

    @Test
    void productionContextDoesNotExpandRiskOrAdequacyReportingScope() throws Exception {
        writeAdequacyProject();
        Files.writeString(tempDir.resolve("src/main/java/example/AuditService.java"), """
                package example;
                public class AuditService {
                    public void audit() { if (System.nanoTime() > 0) System.out.println("audit"); }
                }
                """);
        Path test = tempDir.resolve("src/test/java/example/OrderServiceTest.java");
        Files.writeString(test, Files.readString(test).replace(
                "assertEquals(2, service.createOrder(2));",
                "assertEquals(2, service.createOrder(2)); new AuditService().audit();"));
        Files.writeString(tempDir.resolve("changed.txt"), "src/main/java/example/OrderService.java\n");

        var response = commandWithPerSourceFinding().scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "off", "", "changed.txt", false));

        assertThat(response.output()).contains("OrderService.java", "TEST-001", "TX-003");
        assertThat(response.output()).doesNotContain("AuditService.java", "AuditService#audit");
        assertThat(response.metrics().filesParsed()).isEqualTo(3);
    }

    @Test
    void missingTestRemainsMissingAndDuplicateSeedEntriesDoNotDuplicateParsing() throws Exception {
        Path main = tempDir.resolve("src/main/java/example");
        Files.createDirectories(main);
        Files.writeString(main.resolve("OrderService.java"), """
                package example;
                public class OrderService {
                    public int createOrder(int count) { if (count < 1) return 0; return count; }
                }
                """);
        Files.writeString(tempDir.resolve("changed.txt"), "src/main/java/example/OrderService.java\n"
                + "src/main/java/example/OrderService.java\n");

        var response = command().scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "off", "", "changed.txt", false));

        assertThat(response.output()).contains("MISSING", "No test method was linked");
        assertThat(response.metrics().filesParsed()).isEqualTo(1);
        var findings = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.output()).path("findings").findValues("ruleId");
        assertThat(findings.stream().map(com.fasterxml.jackson.databind.JsonNode::asText)
                .filter("TEST-001"::equals).toList()).hasSize(3);
    }

    @Test
    void newTestGapsGateUsesBaselineAndSuppressionsAndIgnoresFailOnOff() throws Exception {
        writeAdequacyProject();
        var command = command();
        assertThat(scan(command, "").exitCode()).isZero();
        Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  new-test-gaps: true\n");
        var failed = scan(command, "");
        assertThat(failed.exitCode()).isEqualTo(1);
        assertThat(failed.output()).contains("CI-TEST-GAPS");
        command.createBaseline(tempDir.toString(), "baseline.json");
        assertThat(scan(command, "baseline.json").exitCode()).isZero();
        Files.writeString(tempDir.resolve(".code-risk.yml"), """
                fail:
                  new-test-gaps: true
                suppressions:
                  - rule: TEST-001
                    path: '**'
                    reason: Deferred obligations in ABC-123
                """);
        assertThat(scan(command, "").exitCode()).isZero();
    }

    @Test
    void unknownRatioIsMeasuredBeforeExclusionsAndHasInclusiveThreshold() throws Exception {
        writeAdequacyProject();
        // A same-name unresolved production call leaves linkage UNKNOWN.
        Files.writeString(tempDir.resolve("src/test/java/example/OrderServiceTest.java"), """
                package example;
                import org.junit.jupiter.api.Test;
                class OrderServiceTest {
                    MissingService service;
                    @Test void createsOrder() { service.createOrder(2); }
                }
                """);
        var command = command();
        var before = scan(command, "");
        assertThat(before.exitCode()).isZero();
        assertThat(before.metrics().completeness().unknownRatio()).isEqualTo(1);
        command.createBaseline(tempDir.toString(), "baseline.json");
        Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  max-unknown-ratio: 0.5\n");
        var failed = scan(command, "baseline.json");
        assertThat(failed.exitCode()).isEqualTo(1);
        assertThat(failed.output()).contains("CI-UNKNOWN");
        Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  max-unknown-ratio: 1\n");
        assertThat(scan(command, "").exitCode()).isZero();
    }

    @Test
    void completenessReportsSelectedScopeUnresolvedCallsAndLimitedPatternsInAllFormats() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Files.writeString(root.resolve("A.java"), """
                class A { void run() {
                    System.nanoTime(); missing.work();
                    while (false) { missing.work(); }
                    try { missing.work(); } catch (Exception e) {}
                } }
                """);
        Files.writeString(root.resolve("B.java"), "class B {}");
        Files.writeString(root.resolve("Excluded.java"), "class Excluded {}");
        Files.writeString(tempDir.resolve("changed.txt"), "src/main/java/A.java\n");
        Files.writeString(tempDir.resolve(".code-risk.yml"), "analysis:\n  excludes: ['**/Excluded.java']\n");
        for (String format : java.util.List.of("json", "sarif", "console")) {
            var response = command().scanForCli(new ScanRequest(tempDir.toString(), format, "", "false",
                    "off", "", "changed.txt", false));
            var c = response.metrics().completeness();
            assertThat(c.filesDiscovered()).isEqualTo(2);
            assertThat(c.filesSelected()).isEqualTo(1);
            assertThat(c.methodCalls()).isEqualTo(4);
            assertThat(c.unresolvedMethodCalls()).isEqualTo(3);
            assertThat(c.unresolvedCallRatio()).isEqualTo(0.75);
            assertThat(c.limitedPatterns()).containsEntry("LOOP", 1).containsEntry("TRY", 1);
            assertThat(response.output()).contains("unresolvedCallRatio", "Excluded.java");
        }
    }

    @Test
    void unmatchedChangedJavaFileProducesWarning() throws Exception {
        writeAdequacyProject();
        Files.writeString(tempDir.resolve("changed.txt"), "src/main/java/Typo.java\nREADME.md\n");
        var response = command().scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "off", "", "changed.txt", false));
        assertThat(response.diagnostics()).anyMatch(d -> d.code().equals("CHANGED-001")
                && d.file().getFileName().toString().equals("Typo.java"));
        assertThat(response.diagnostics()).noneMatch(d -> "CHANGED-001".equals(d.code())
                && d.file().getFileName().toString().equals("README.md"));
    }

    @Test
    void jevTestReviewAddsAdvisoryEvidenceForPartialAndUnknownOnly() {
        var location = new SourceLocation(tempDir.resolve("A.java"), 1, 1, 1, 1);
        java.util.function.BiFunction<String, FindingStatus, Finding> gap = (coverage, status) -> new Finding("TEST-001",
                RiskCategory.RELIABILITY, Severity.HIGH, Confidence.MEDIUM, "t", "m", location,
                java.util.List.of(new Evidence("TEST_OBLIGATION", "r", location,
                        java.util.Map.of("coverageStatus", coverage, "missingCases", java.util.List.of("x")))), status);
        int[] calls = {0};
        var judge = new com.example.coderiskanalyzer.production.judge.SemanticJudge() {
            public com.example.coderiskanalyzer.production.judge.JudgeResult judge(Finding f, java.util.Map<String, Object> e) {
                throw new AssertionError("risk judge must not run for TEST-001");
            }
            @Override public com.example.coderiskanalyzer.production.judge.TestAdequacyReview reviewTestAdequacy(
                    Finding f, java.util.Map<String, Object> e) {
                calls[0]++;
                return new com.example.coderiskanalyzer.production.judge.TestAdequacyReview(true, 0.99, "x");
            }
        };
        var result = command().reviewCandidates(java.util.List.of(gap.apply("PARTIAL", FindingStatus.REVIEW),
                gap.apply("UNKNOWN", FindingStatus.REVIEW), gap.apply("MISSING", FindingStatus.REVIEW),
                gap.apply("COVERED", FindingStatus.REVIEW), gap.apply("PARTIAL", FindingStatus.SUPPRESSED)), judge);
        assertThat(calls[0]).isEqualTo(2);
        assertThat(result.get(0).status()).isEqualTo(FindingStatus.REVIEW);
        assertThat(result.get(0).confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(result.get(0).evidence()).anyMatch(e -> e.type().equals("JEV_TEST_REVIEW")
                && e.attributes().get("staticStatus").equals("PARTIAL"));
        assertThat(result.get(2).evidence()).hasSize(1);
        assertThat(result.get(4).evidence()).hasSize(1);
    }

    @Test
    void jevReviewSkipsSuppressedFindingsAndConfirmsOthers() {
        var location = new SourceLocation(tempDir.resolve("A.java"), 1, 1, 1, 1);
        java.util.function.Function<FindingStatus, Finding> idem = status -> new Finding("IDEM-001",
                RiskCategory.IDEMPOTENCY, Severity.MEDIUM, Confidence.LOW, "t", "m", location,
                java.util.List.of(new Evidence("TEST", "e", location, java.util.Map.of())), status);
        int[] calls = {0};
        com.example.coderiskanalyzer.production.judge.SemanticJudge judge = (finding, evidence) -> {
            calls[0]++;
            return new com.example.coderiskanalyzer.production.judge.JudgeResult(true, true, 0.95, "x");
        };
        var result = command().reviewCandidates(java.util.List.of(
                idem.apply(FindingStatus.SUPPRESSED), idem.apply(FindingStatus.REVIEW)), judge);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(result.get(0).status()).isEqualTo(FindingStatus.SUPPRESSED);
        assertThat(result.get(1).status()).isEqualTo(FindingStatus.CONFIRMED);
    }

    @Test
    void riskRulesGetAdvisoryJevEvidenceWithoutChangingStaticResult() {
        var location = new SourceLocation(tempDir.resolve("A.java"), 1, 1, 1, 1);
        java.util.function.BiFunction<String, FindingStatus, Finding> risk = (rule, status) -> new Finding(rule,
                RiskCategory.IDEMPOTENCY, Severity.MEDIUM, Confidence.LOW, "t", "m", location,
                java.util.List.of(new Evidence("TEST", "e", location, java.util.Map.of())), status);
        int[] calls = {0};
        var judge = new com.example.coderiskanalyzer.production.judge.SemanticJudge() {
            public com.example.coderiskanalyzer.production.judge.JudgeResult judge(Finding f, java.util.Map<String, Object> e) {
                throw new AssertionError("only IDEM-001 uses judge()");
            }
            @Override public com.example.coderiskanalyzer.production.judge.JudgeResult reviewRisk(
                    Finding f, java.util.Map<String, Object> e) {
                calls[0]++;
                return new com.example.coderiskanalyzer.production.judge.JudgeResult(true, true, 0.99, "x");
            }
        };
        var result = command().reviewCandidates(java.util.List.of(risk.apply("CON-001", FindingStatus.REVIEW),
                risk.apply("IDEM-003", FindingStatus.SUPPRESSED), risk.apply("SEC-005", FindingStatus.REVIEW)), judge);
        assertThat(calls[0]).isEqualTo(1);
        assertThat(result.get(0).status()).isEqualTo(FindingStatus.REVIEW);
        assertThat(result.get(0).confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.get(0).evidence()).anyMatch(e -> e.type().equals("JEV_RISK_REVIEW")
                && e.attributes().get("riskProbability").equals(0.99));
        assertThat(result.get(1).evidence()).hasSize(1);
        assertThat(result.get(2).evidence()).hasSize(1);
    }

    @Test
    void everyAdvisoryRiskRuleHasAJevQuestionAndSecretsAreNeverReviewed() {
        assertThat(ScanCommand.RISK_REVIEW_RULES)
                .allMatch(com.example.coderiskanalyzer.production.judge.jev.JevSemanticJudge::supportsRiskReview)
                .contains("TX-001", "TX-005", "CON-003", "CON-004", "SEC-006", "SEC-007", "REL-001")
                .doesNotContain("SEC-005", "IDEM-001");
    }

    @Test
    void jevRiskReviewReceivesTheEnclosingMethodSourceExceptFromFilesWithSecrets() {
        var file = tempDir.resolve("A.java");
        var other = tempDir.resolve("B.java");
        java.util.function.Function<Path, com.example.coderiskanalyzer.production.model.MethodInfo> method = path ->
                new com.example.coderiskanalyzer.production.model.MethodInfo("demo.A", "load", "void", java.util.List.of(),
                        java.util.Set.of("Transactional"), java.util.Set.of(), java.util.List.of(),
                        new SourceLocation(path, 10, 1, 20, 1), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                        "{ repository.findById(id); }", java.util.List.of());
        var model = new com.example.coderiskanalyzer.production.model.ProjectModel(java.util.List.of(
                new com.example.coderiskanalyzer.production.model.ClassInfo("demo.A", "demo", "A", java.util.Set.of(),
                        java.util.List.of(), java.util.List.of(method.apply(file), method.apply(other)),
                        new SourceLocation(file, 1, 1, 30, 1))));
        java.util.function.BiFunction<String, Path, Finding> risk = (rule, path) -> new Finding(rule,
                RiskCategory.SECURITY, Severity.HIGH, Confidence.LOW, "t", "m", new SourceLocation(path, 12, 1, 12, 9),
                java.util.List.of(new Evidence("TEST", "e", new SourceLocation(path, 12, 1, 12, 9), java.util.Map.of())),
                FindingStatus.REVIEW);
        var sent = new java.util.ArrayList<java.util.Map<String, Object>>();
        var judge = new com.example.coderiskanalyzer.production.judge.SemanticJudge() {
            public com.example.coderiskanalyzer.production.judge.JudgeResult judge(Finding f, java.util.Map<String, Object> e) {
                throw new AssertionError("only IDEM-001 uses judge()");
            }
            @Override public com.example.coderiskanalyzer.production.judge.JudgeResult reviewRisk(
                    Finding f, java.util.Map<String, Object> e) {
                sent.add(e);
                return new com.example.coderiskanalyzer.production.judge.JudgeResult(true, true, 0.7, "x");
            }
        };
        var result = command().reviewCandidates(java.util.List.of(risk.apply("SEC-006", file), risk.apply("TX-004", other),
                risk.apply("SEC-005", other)), judge, model);
        assertThat(sent).hasSize(2);
        @SuppressWarnings("unchecked")
        var source = (java.util.Map<String, Object>) sent.get(0).get("source");
        assertThat(source).containsEntry("method", "demo.A#load").containsEntry("body", "{ repository.findById(id); }")
                .containsEntry("truncated", false);
        assertThat(sent.get(1)).doesNotContainKey("source");
        assertThat(result.get(0).status()).isEqualTo(FindingStatus.REVIEW);
        assertThat(result.get(0).evidence()).anyMatch(e -> e.type().equals("JEV_RISK_REVIEW"));
    }

    @Test
    void emptyChangedScopeHasZeroRatiosAndDoesNotFailOptInGates() throws Exception {
        writeAdequacyProject();
        Files.writeString(tempDir.resolve("changed.txt"), "# no changed Java files\n");
        Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  new-test-gaps: true\n  max-unknown-ratio: 0\n");
        var response = command().scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false",
                "off", "", "changed.txt", false));
        assertThat(response.exitCode()).isZero();
        assertThat(response.metrics().completeness().testObligations()).isZero();
        assertThat(response.metrics().completeness().unknownRatio()).isZero();
        assertThat(response.metrics().completeness().unresolvedCallRatio()).isZero();
    }

    @Test
    void parseFailureTakesPrecedenceOverTestGapGateAndReportIsWritten() throws Exception {
        writeAdequacyProject();
        Files.writeString(tempDir.resolve("src/main/java/Broken.java"), "class Broken {}");
        // JavaParser recovers many syntax errors with a non-null AST. Supply its unrecoverable result explicitly.
        var parser = new JavaSourceParser() {
            @Override public java.util.List<com.example.coderiskanalyzer.production.parser.ParsedSource> parse(
                    java.util.List<com.example.coderiskanalyzer.production.source.SourceFile> files,
                    java.util.List<Path> roots, java.util.List<Path> classpath) {
                return super.parse(files, roots, classpath).stream().map(p ->
                        p.sourceFile().path().getFileName().toString().equals("Broken.java")
                                ? new com.example.coderiskanalyzer.production.parser.ParsedSource(p.sourceFile(), null,
                                        java.util.List.of("Unrecoverable parse failure")) : p).toList();
            }
        };
        var command = new ScanCommand(new SourceDiscoveryService(new SourceRootDetector()), parser,
                new ProgramModelBuilder(), new RuleEngine(java.util.List.of()),
                java.util.List.of(new JsonReporter()));
        Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  new-test-gaps: true\n");
        var response = command.scanForCli(new ScanRequest(tempDir.toString(), "json", "report.json", "false",
                "off", "", "", false));
        assertThat(response.exitCode()).isEqualTo(3);
        assertThat(Files.readString(tempDir.resolve("report.json"))).contains("PARSE-001", "CI-TEST-GAPS");
        assertThat(response.metrics().filesFailedToParse()).isEqualTo(1);
    }

    private com.example.coderiskanalyzer.production.cli.ScanResponse scan(ScanCommand command, String baseline) {
        return command.scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false", "off", baseline, "", false));
    }

    private void writeAdequacyProject() throws Exception {
        Path main = tempDir.resolve("src/main/java/example");
        Path tests = tempDir.resolve("src/test/java/example");
        Files.createDirectories(main);
        Files.createDirectories(tests);
        Files.writeString(main.resolve("OrderService.java"), """
                package example;
                public class OrderService {
                    public int createOrder(int count) {
                        if (count < 1) return 0;
                        return count;
                    }
                }
                """);
        Files.writeString(tests.resolve("OrderServiceTest.java"), """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class OrderServiceTest {
                    private final OrderService service = new OrderService();
                    @Test void createsOrder() { assertEquals(2, service.createOrder(2)); }
                }
                """);
    }

    private ScanCommand commandWithConfirmedFinding() { return commandWithFinding(FindingStatus.CONFIRMED); }

    @Test
    void includeReviewGateBlocksReviewFindingsOnlyWhenEnabled() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.writeString(tempDir.resolve("src/main/java/A.java"), "class A { void run() {} }");
        var command = commandWithFinding(FindingStatus.REVIEW);
        var request = new ScanRequest(tempDir.toString(), "json", "", "false", "", "", "", false);
        assertThat(command.scanForCli(request).exitCode()).isZero();
        Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  include-review: true\n");
        assertThat(command.scanForCli(request).exitCode()).isEqualTo(1);
        assertThat(scan(command, "").exitCode()).isZero();
    }

    private ScanCommand commandWithFinding(FindingStatus status) {
        RiskRule rule = new RiskRule() {
            @Override public String id() { return "TX-003"; }
            @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
            @Override public Severity defaultSeverity() { return Severity.HIGH; }
            @Override public java.util.List<Finding> analyze(AnalysisContext context) {
                if (context.parsedSources().isEmpty()) return java.util.List.of();
                Path source = context.parsedSources().getFirst().sourceFile().path();
                var location = new SourceLocation(source, 1, 1, 1, 1);
                return java.util.List.of(new Finding(id(), category(), defaultSeverity(), Confidence.HIGH,
                        "Review", "Finding", location,
                        java.util.List.of(new Evidence("TEST", "Evidence", location, java.util.Map.of())),
                        status));
            }
        };
        return new ScanCommand(new SourceDiscoveryService(new SourceRootDetector()), new JavaSourceParser(),
                new ProgramModelBuilder(), new RuleEngine(java.util.List.of(rule)),
                java.util.List.of(new ConsoleReporter(), new JsonReporter(), new SarifReporter()));
    }

    private ScanCommand commandWithPerSourceFinding() {
        RiskRule rule = new RiskRule() {
            @Override public String id() { return "TX-003"; }
            @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
            @Override public Severity defaultSeverity() { return Severity.HIGH; }
            @Override public java.util.List<Finding> analyze(AnalysisContext context) {
                return context.parsedSources().stream().map(source -> {
                    Path path = source.sourceFile().path();
                    var location = new SourceLocation(path, 1, 1, 1, 1);
                    return new Finding(id(), category(), defaultSeverity(), Confidence.HIGH,
                            "Review", "Finding", location,
                            java.util.List.of(new Evidence("TEST", "Evidence", location, java.util.Map.of())),
                            FindingStatus.CONFIRMED);
                }).toList();
            }
        };
        return new ScanCommand(new SourceDiscoveryService(new SourceRootDetector()), new JavaSourceParser(),
                new ProgramModelBuilder(), new RuleEngine(java.util.List.of(rule)),
                java.util.List.of(new ConsoleReporter(), new JsonReporter(), new SarifReporter()));
    }

    @Test
    void importsExecutionEvidenceIntoAllReportFormatsWithoutHidingOtherTestGaps() throws Exception {
        Path main = tempDir.resolve("src/main/java/sample/Worker.java");
        Path test = tempDir.resolve("src/test/java/sample/WorkerTest.java");
        Files.createDirectories(main.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(main, """
                package sample;
                class Worker {
                    @Transactional void run() {
                        repository.save(1);
                        repository.save(2);
                        throw new IllegalArgumentException();
                    }
                }
                """);
        Files.writeString(test, """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class WorkerTest {
                    @Test void rollsBack() { assertThrows(IllegalArgumentException.class, () -> new Worker().run()); }
                }
                """);
        Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  new-test-gaps: true\n");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var initial = command().scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false", "off", "", "", false));
        assertThat(initial.exitCode()).isEqualTo(1);
        com.fasterxml.jackson.databind.JsonNode obligation = null;
        for (var finding : mapper.readTree(initial.output()).path("findings")) {
            var attributes = finding.path("evidence").get(0).path("attributes");
            if (attributes.path("type").asText().equals("TRANSACTION_ROLLBACK")) obligation = attributes;
        }
        assertThat(obligation).isNotNull();
        Files.writeString(tempDir.resolve("junit.xml"), "<testsuite><testcase classname='sample.WorkerTest' name='rollsBack'/></testsuite>");
        Files.writeString(tempDir.resolve("jacoco.xml"), "<report><package name='sample'><sourcefile name='Worker.java'><line nr='4' mi='0' ci='3'/><line nr='5' mi='0' ci='3'/><line nr='6' mi='0' ci='3'/></sourcefile></package></report>");
        var manifest = mapper.createObjectNode().put("version", 1);
        var entry = manifest.putArray("entries").addObject();
        entry.put("obligationId", obligation.path("obligationId").asText());
        entry.put("test", "sample.WorkerTest#rollsBack").put("scenario", "ROLLBACK");
        entry.put("database", "PostgreSQL").put("databaseEvidence", "Reviewer checked real DB and transaction rollback assertions.");
        entry.putObject("assertionReview").put("reviewer", "maintainer")
                .put("reason", "Failure injected after write; committed state checked with fresh transaction.")
                .put("requiredEvidence", "assert state rollback after failure");
        entry.put("sourceSha256", executionHash(main)).put("testSourceSha256", executionHash(test));
        for (String artifact : java.util.List.of("junit", "jacoco")) {
            entry.put(artifact, artifact + ".xml").put(artifact + "Sha256", executionHash(tempDir.resolve(artifact + ".xml")));
        }
        Files.writeString(tempDir.resolve("evidence.json"), mapper.writeValueAsString(manifest));
        for (String format : java.util.List.of("console", "json", "sarif")) {
            var response = command().scanForCli(new ScanRequest(tempDir.toString(), format, "", "false", "off", "", "", false, "evidence.json"));
            assertThat(response.exitCode()).isEqualTo(1); // other persistence gaps remain
            assertThat(response.output()).contains(obligation.path("obligationId").asText() + " | COVERED", "PARTIAL", "EXECUTION_TEST");
            if (!format.equals("console")) assertThat(response.output()).contains("databaseVerifiedByAnalyzer", "staticStatus");
        }
        Files.writeString(main, Files.readString(main) + "\n");
        var stale = command().scanForCli(new ScanRequest(tempDir.toString(), "json", "", "false", "off", "", "", false, "evidence.json"));
        assertThat(stale.exitCode()).isEqualTo(2);
        assertThat(stale.diagnostics()).extracting(d -> d.code()).contains("EXECUTION-001");
        assertThat(stale.output()).contains("hash mismatch");
    }

    private String executionHash(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    @Test
    void projectJevCapWinsOverTheCallersAndInvalidValuesMeanNoCap() {
        assertThat(ScanCommand.jevCallCap(5, "20")).isEqualTo(5);
        assertThat(ScanCommand.jevCallCap(null, "20")).isEqualTo(20);
        assertThat(ScanCommand.jevCallCap(null, "0")).isZero();
        for (String invalid : java.util.Arrays.asList(null, "", " ", "-1", "many", "1.5"))
            assertThat(ScanCommand.jevCallCap(null, invalid)).as(String.valueOf(invalid)).isNull();
    }
}
