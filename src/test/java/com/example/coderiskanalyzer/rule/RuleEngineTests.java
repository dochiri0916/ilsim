package com.example.coderiskanalyzer.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.ProjectModel;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.example.coderiskanalyzer.production.rule.RuleEngine;
import com.example.coderiskanalyzer.production.rule.RuleMetadata;
import com.example.coderiskanalyzer.production.rule.RuleRegistry;
import org.junit.jupiter.api.Test;

class RuleEngineTests {

    @Test
    void stackOverflowInRuleBecomesDiagnostic() {
        RiskRule rule = new RiskRule() {
            @Override public String id() { return "X-001"; }
            @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
            @Override public Severity defaultSeverity() { return Severity.LOW; }
            @Override public List<Finding> analyze(AnalysisContext context) { throw new StackOverflowError(); }
        };
        var result = new RuleEngine(List.of(rule)).analyzeDetailed(
                new AnalysisContext(new ProjectModel(List.of()), List.of()), Map.of());
        assertThat(result.diagnostics()).anyMatch(d -> d.code().equals("RULE-001"));
    }

    @Test
    void continuesAfterRuleFailureAppliesSettingsAndDeduplicatesDeterministically() {
        RiskRule duplicateRule = new TestRule("TX-001", context -> List.of(
                finding("TX-001", "FIRST", "save"), finding("TX-001", "SECOND", "save")));
        RiskRule failingRule = new TestRule("TX-002", context -> { throw new IllegalStateException("private detail"); });
        RiskRule disabledRule = new TestRule("TX-003", context -> List.of(finding("TX-003", "DISABLED", "save")));
        var result = new RuleEngine(List.of(disabledRule, failingRule, duplicateRule)).analyzeDetailed(
                new AnalysisContext(new ProjectModel(List.of()), List.of()), Map.of(
                        "TX-001", new AnalyzerConfig.RuleSettings(true, Severity.CRITICAL),
                        "TX-003", new AnalyzerConfig.RuleSettings(false, null),
                        "UNKNOWN", new AnalyzerConfig.RuleSettings(false, null)));

        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(finding.evidence()).hasSize(2);
        });
        assertThat(result.diagnostics()).extracting("code").containsExactly("CONFIG-002", "RULE-001");
        assertThat(result.diagnostics().get(1).message()).doesNotContain("private detail");
    }

    @Test
    void registryExposesAllV1RuleMetadataInStableOrder() {
        List<RiskRule> rules = List.of(new TestRule("TX-002", context -> List.of()),
                new TestRule("SEC-001", context -> List.of()));

        var registry = new RuleRegistry(rules);

        assertThat(registry.all()).extracting(RuleMetadata::id).containsExactly("SEC-001", "TX-002");
        assertThat(registry.find("tx-002").title()).isEqualTo("Async transaction boundary");
    }

    private Finding finding(String id, String type, String sink) {
        var location = new SourceLocation(Path.of("/tmp/Service.java"), 1, 1, 1, 5);
        return new Finding(id, RiskCategory.TRANSACTION, Severity.MEDIUM, Confidence.MEDIUM,
                type, type, location, List.of(new Evidence(type, type, location, Map.of("sink", sink))), FindingStatus.REVIEW);
    }

    private record TestRule(String id, java.util.function.Function<AnalysisContext, List<Finding>> analyzer) implements RiskRule {
        @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
        @Override public Severity defaultSeverity() { return Severity.MEDIUM; }
        @Override public List<Finding> analyze(AnalysisContext context) { return analyzer.apply(context); }
    }
}
