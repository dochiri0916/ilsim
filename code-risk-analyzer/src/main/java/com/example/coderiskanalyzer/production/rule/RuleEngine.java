package com.example.coderiskanalyzer.production.rule;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.finding.Diagnostic;
import com.example.coderiskanalyzer.production.finding.DiagnosticSeverity;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.rule.concurrency.ConcurrencyRiskRule;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyAssessment;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyOutcome;
import org.springframework.stereotype.Component;

@Component
public class RuleEngine {

    private final List<RiskRule> rules;

    public RuleEngine(List<RiskRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Finding> analyze(AnalysisContext context) {
        return analyzeDetailed(context, Map.of()).findings();
    }

    public boolean hasRule(String id) {
        return id.equals("TEST-001") || rules.stream().anyMatch(rule -> rule.id().equals(id));
    }

    public AnalysisResult analyzeDetailed(AnalysisContext context, Map<String, AnalyzerConfig.RuleSettings> settings) {
        List<Finding> candidates = new java.util.ArrayList<>();
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        List<ConcurrencyAssessment> assessments = new java.util.ArrayList<>();
        List<RiskRule> orderedRules = rules.stream().sorted(Comparator.comparing(RiskRule::id)).toList();
        java.util.Set<String> knownIds = new java.util.HashSet<>(orderedRules.stream().map(RiskRule::id).toList());
        knownIds.add("TEST-001");
        settings.keySet().stream().filter(id -> !knownIds.contains(id)).sorted().forEach(id -> diagnostics.add(
                new Diagnostic(DiagnosticSeverity.WARNING, "CONFIG-002",
                        "Configuration references unknown rule " + id + "; the setting was ignored.", null)));
        for (RiskRule rule : orderedRules) {
            AnalyzerConfig.RuleSettings ruleSettings = settings.get(rule.id());
            if (ruleSettings != null && !ruleSettings.enabled()) continue;
            try {
                List<Finding> ruleFindings;
                if (rule instanceof ConcurrencyRiskRule concurrencyRule) {
                    var evaluated = concurrencyRule.assess(context);
                    ruleFindings = evaluated.stream().filter(item -> item.outcome() != ConcurrencyOutcome.SAFE_BY_PROOF)
                            .map(concurrencyRule::finding).toList();
                    assessments.addAll(evaluated);
                } else ruleFindings = rule.analyze(context);
                for (Finding finding : ruleFindings) {
                    candidates.add(ruleSettings == null || ruleSettings.severity() == null
                            ? finding : withSeverity(finding, ruleSettings.severity()));
                }
            } catch (RuntimeException | StackOverflowError exception) {
                diagnostics.add(new Diagnostic(DiagnosticSeverity.ERROR, "RULE-001",
                        "Rule " + rule.id() + " failed with " + exception.getClass().getSimpleName()
                                + "; analysis continued with remaining rules.", null));
            }
        }
        return new AnalysisResult(deduplicate(candidates), diagnostics, assessments);
    }

    private Finding withSeverity(Finding finding, Severity severity) {
        return new Finding(finding.ruleId(), finding.category(), severity, finding.confidence(), finding.title(),
                finding.message(), finding.location(), finding.evidence(), finding.status(), finding.concurrencyAssessment());
    }

    private List<Finding> deduplicate(List<Finding> candidates) {
        Map<String, Finding> byKey = new LinkedHashMap<>();
        candidates.stream().sorted(ordering()).forEach(finding -> byKey.merge(fingerprint(finding), finding,
                this::mergeEvidence));
        return byKey.values().stream().sorted(ordering()).toList();
    }

    private Finding mergeEvidence(Finding first, Finding second) {
        List<Evidence> evidence = new java.util.ArrayList<>(first.evidence());
        for (var item : second.evidence()) if (!evidence.contains(item)) evidence.add(item);
        return new Finding(first.ruleId(), first.category(), first.severity(), first.confidence(), first.title(),
                first.message(), first.location(), evidence, first.status(), first.concurrencyAssessment());
    }

    private String fingerprint(Finding finding) {
        if (finding.concurrencyAssessment() != null)
            return com.example.coderiskanalyzer.production.baseline.FindingFingerprint.stableOf(
                    finding.location().file().toAbsolutePath().normalize().getRoot(), finding);
        String sink = finding.evidence().stream().map(evidence -> {
            Object value = evidence.attributes().getOrDefault("sink",
                    evidence.attributes().getOrDefault("operation", evidence.attributes().get("method")));
            return value == null ? "" : value.toString();
        }).filter(value -> !value.isBlank()).findFirst().orElse(finding.title());
        return finding.ruleId() + "|" + finding.location().file().toAbsolutePath().normalize() + "|"
                + finding.location().startLine() + "|" + sink;
    }

    private Comparator<Finding> ordering() {
        return Comparator.comparing((Finding finding) -> finding.location().file().toAbsolutePath().normalize().toString())
                .thenComparingInt(finding -> finding.location().startLine())
                .thenComparing(Finding::ruleId)
                .thenComparing(Finding::title);
    }
}
