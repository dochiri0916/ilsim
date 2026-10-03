package com.example.coderiskanalyzer.production.finding;

import java.util.List;

import com.example.coderiskanalyzer.production.model.SourceLocation;

public record Finding(
        String ruleId,
        RiskCategory category,
        Severity severity,
        Confidence confidence,
        String title,
        String message,
        SourceLocation location,
        List<Evidence> evidence,
        FindingStatus status) {
    public Finding {
        evidence = List.copyOf(evidence);
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("A finding must include at least one evidence item");
        }
    }
}
