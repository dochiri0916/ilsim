package com.example.coderiskanalyzer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.config.AnalyzerConfigException;
import com.example.coderiskanalyzer.production.config.AnalyzerConfigLoader;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerConfigLoaderTests {

    @TempDir
    Path tempDir;

    @Test
    void loadsOptInPoliciesAndRejectsInvalidLimitsAndExceptions() throws Exception {
        assertThat(new AnalyzerConfigLoader().load(tempDir).ciPolicy())
                .isEqualTo(AnalyzerConfig.CiPolicy.defaults());
        String fingerprint = "a".repeat(64);
        Files.writeString(tempDir.resolve(".code-risk.yml"), """
                fail:
                  max-unknown-ratio: 0.25
                  new-test-gaps: true
                  max-survived-mutations: 0
                  mutation-exceptions:
                    - fingerprint: %s
                      reason: Equivalent mutant reviewed in ABC-123
                """.formatted(fingerprint));
        var policy = new AnalyzerConfigLoader().load(tempDir).ciPolicy();
        assertThat(policy.maxUnknownRatio()).isEqualTo(0.25);
        assertThat(policy.newTestGaps()).isTrue();
        assertThat(policy.maxSurvivedMutations()).isZero();
        assertThat(policy.includeReview()).isFalse();
        assertThat(policy.onJevFailure()).isFalse();
        Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  include-review: true\n  on-jev-failure: true\n");
        assertThat(new AnalyzerConfigLoader().load(tempDir).ciPolicy()).satisfies(p -> {
            assertThat(p.includeReview()).isTrue();
            assertThat(p.onJevFailure()).isTrue();
        });
        assertThat(policy.maxJevCalls()).isNull();
        Files.writeString(tempDir.resolve(".code-risk.yml"), "jev:\n  max-calls: 5\n");
        assertThat(new AnalyzerConfigLoader().load(tempDir).ciPolicy().maxJevCalls()).isEqualTo(5);
        for (String invalid : java.util.List.of("max-calls: -1", "max-calls: 1.5", "max-calls: '3'")) {
            Files.writeString(tempDir.resolve(".code-risk.yml"), "jev:\n  " + invalid + "\n");
            assertThatThrownBy(() -> new AnalyzerConfigLoader().load(tempDir))
                    .as(invalid).isInstanceOf(AnalyzerConfigException.class);
        }
        assertThat(policy.mutationExceptions()).singleElement().satisfies(e ->
                assertThat(e.reason()).contains("ABC-123"));
        for (String invalid : java.util.List.of("max-unknown-ratio: -0.1", "max-unknown-ratio: 1.01",
                "max-unknown-ratio: .nan", "max-unknown-ratio: '0.5'", "max-unknown-ratio: null",
                "max-survived-mutations: -1", "max-survived-mutations: 1.5",
                "max-survived-mutations: 2147483648", "new-test-gaps: yesplease", "include-review: maybe", "on-jev-failure: 1",
                "mutation-exceptions: [{fingerprint: bad, reason: reviewed}]",
                "mutation-exceptions: [{fingerprint: '" + fingerprint + "', reason: ' '}]",
                "mutation-exceptions: [{fingerprint: '" + fingerprint + "', reason: reviewed, typo: true}]")) {
            Files.writeString(tempDir.resolve(".code-risk.yml"), "fail:\n  " + invalid + "\n");
            assertThatThrownBy(() -> new AnalyzerConfigLoader().load(tempDir))
                    .as(invalid).isInstanceOf(AnalyzerConfigException.class);
        }
    }

    @Test
    void loadsRootsRulesReportFailurePolicyAndReasonedSuppressions() throws Exception {
        Files.writeString(tempDir.resolve(".code-risk.yml"), """
                analysis:
                  source-roots:
                    - src/custom/java
                  excludes:
                    - '**/generated/**'
                rules:
                  TX-003:
                    enabled: false
                  SEC-001:
                    severity: HIGH
                suppressions:
                  - rule: TX-003
                    path: '**/LegacyService.java'
                    reason: migration tracked in ABC-123
                report:
                  format: sarif
                fail:
                  severity: MEDIUM
                  confidence: MEDIUM
                """);

        AnalyzerConfig config = new AnalyzerConfigLoader().load(tempDir);

        assertThat(config.sourceRoots()).containsExactly("src/custom/java");
        assertThat(config.excludes()).containsExactly("**/generated/**");
        assertThat(config.rules().get("TX-003").enabled()).isFalse();
        assertThat(config.rules().get("SEC-001").severity()).isEqualTo(Severity.HIGH);
        assertThat(config.suppressions()).singleElement().satisfies(suppression -> {
            assertThat(suppression.reason()).contains("ABC-123");
            assertThat(suppression.path()).isEqualTo("**/LegacyService.java");
        });
        assertThat(config.reportFormat()).isEqualTo("sarif");
        assertThat(config.failSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(config.failConfidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void rejectsInvalidClasspathInputs() throws Exception {
        for (String entry : java.util.List.of("../outside.jar", "/tmp/outside.jar", "missing.jar", "src")) {
            Files.writeString(tempDir.resolve(".code-risk.yml"), "analysis:\n  classpath: ['" + entry + "']\n");
            assertThatThrownBy(() -> new AnalyzerConfigLoader().load(tempDir))
                    .isInstanceOf(AnalyzerConfigException.class).hasMessageContaining("classpath");
        }
        Files.writeString(tempDir.resolve("invalid.jar"), "not a jar");
        Files.writeString(tempDir.resolve(".code-risk.yml"), "analysis:\n  classpath: [invalid.jar]\n");
        assertThatThrownBy(() -> new AnalyzerConfigLoader().load(tempDir))
                .isInstanceOf(AnalyzerConfigException.class).hasMessageContaining("invalid JAR");
        Files.createSymbolicLink(tempDir.resolve("linked.jar"), tempDir.resolve("invalid.jar"));
        Files.writeString(tempDir.resolve(".code-risk.yml"), "analysis:\n  classpath: [linked.jar]\n");
        assertThatThrownBy(() -> new AnalyzerConfigLoader().load(tempDir))
                .isInstanceOf(AnalyzerConfigException.class).hasMessageContaining("symbolic links");
    }

    @Test
    void rejectsMissingSuppressionReasonAndRootsEscapingProject() throws Exception {
        Files.writeString(tempDir.resolve(".code-risk.yml"), """
                suppressions:
                  - rule: TX-003
                    path: '**/LegacyService.java'
                    reason: ' '
                """);
        assertThatThrownBy(() -> new AnalyzerConfigLoader().load(tempDir))
                .isInstanceOf(AnalyzerConfigException.class).hasMessageContaining("reason");

        Files.writeString(tempDir.resolve(".code-risk.yml"), "analysis:\n  source-roots: [../outside]\n");
        assertThatThrownBy(() -> new AnalyzerConfigLoader().load(tempDir))
                .isInstanceOf(AnalyzerConfigException.class).hasMessageContaining("project root");
    }
}
