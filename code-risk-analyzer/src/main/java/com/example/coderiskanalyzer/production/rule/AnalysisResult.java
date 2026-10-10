package com.example.coderiskanalyzer.production.rule;

import java.util.List;

import com.example.coderiskanalyzer.production.finding.Diagnostic;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyAssessment;

public record AnalysisResult(List<Finding> findings, List<Diagnostic> diagnostics,
                             List<ConcurrencyAssessment> concurrencyAssessments) {
    public AnalysisResult(List<Finding> findings, List<Diagnostic> diagnostics) {
        this(findings, diagnostics, List.of());
    }

    public AnalysisResult {
        findings = List.copyOf(findings);
        diagnostics = List.copyOf(diagnostics);
        concurrencyAssessments = List.copyOf(concurrencyAssessments);
    }
}
