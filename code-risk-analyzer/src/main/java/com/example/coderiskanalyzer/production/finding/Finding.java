package com.example.coderiskanalyzer.production.finding;

import java.util.List;

import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyAssessment;

public record Finding(
        String ruleId,
        RiskCategory category,
        Severity severity,
        Confidence confidence,
        String title,
        String message,
        SourceLocation location,
        List<Evidence> evidence,
        FindingStatus status,
        ConcurrencyAssessment concurrencyAssessment) {
    public Finding(String ruleId, RiskCategory category, Severity severity, Confidence confidence,
                   String title, String message, SourceLocation location, List<Evidence> evidence, FindingStatus status) {
        this(ruleId, category, severity, confidence, title, message, location, evidence, status, null);
    }

    public Finding {
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("A finding must include at least one evidence item");
        }
        if (concurrencyAssessment != null && (!ruleId.equals(concurrencyAssessment.ruleId())
                || concurrencyAssessment.outcome()
                        == com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyOutcome.SAFE_BY_PROOF))
            throw new IllegalArgumentException("A finding must represent a non-safe assessment of its own rule");
    }
}
