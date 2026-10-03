package com.example.coderiskanalyzer.suppression;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.suppression.SuppressionMatcher;
import org.junit.jupiter.api.Test;

class SuppressionMatcherTests {

    @Test
    void suppressesOnlyMatchingRuleAndPathAndKeepsReasonAsEvidence() {
        Path root = Path.of("/workspace/project");
        Finding finding = new Finding("TX-003", RiskCategory.TRANSACTION, Severity.MEDIUM, Confidence.HIGH,
                "Remote call", "Review", new SourceLocation(root.resolve("src/LegacyService.java"), 9, 1, 9, 20),
                List.of(new Evidence("REMOTE", "Call", new SourceLocation(root.resolve("src/LegacyService.java"), 9, 1, 9, 20), Map.of())),
                FindingStatus.REVIEW);
        AnalyzerConfig.SuppressionConfig suppression = new AnalyzerConfig.SuppressionConfig(
                "TX-003", "**/LegacyService.java", "migration tracked in ABC-123");

        var results = new SuppressionMatcher().apply(root, List.of(finding), List.of(suppression));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(FindingStatus.SUPPRESSED);
            assertThat(result.evidence()).anySatisfy(evidence ->
                    assertThat(evidence.attributes().get("reason")).isEqualTo("migration tracked in ABC-123"));
        });
        assertThat(new SuppressionMatcher().apply(root, List.of(finding), List.of(
                new AnalyzerConfig.SuppressionConfig("SEC-001", "**/LegacyService.java", "reason"))))
                .singleElement().satisfies(result -> assertThat(result.status()).isEqualTo(FindingStatus.REVIEW));
    }
}
