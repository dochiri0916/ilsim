package com.example.coderiskanalyzer.production.rule;

import java.util.List;

import com.example.coderiskanalyzer.production.finding.Diagnostic;
import com.example.coderiskanalyzer.production.finding.Finding;

public record AnalysisResult(List<Finding> findings, List<Diagnostic> diagnostics) {
    public AnalysisResult {
        findings = List.copyOf(findings);
        diagnostics = List.copyOf(diagnostics);
    }
}
