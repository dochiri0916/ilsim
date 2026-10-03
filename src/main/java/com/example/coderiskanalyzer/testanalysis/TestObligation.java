package com.example.coderiskanalyzer.testanalysis;

import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;

public record TestObligation(String id, String type, String condition, String owner, String method,
                             SourceLocation location, String requiredEvidence, String riskId,
                             RiskCategory riskCategory, Severity riskSeverity) {
    public TestObligation(String id, String type, String condition, String owner, String method,
                          SourceLocation location, String requiredEvidence, String riskId) {
        this(id, type, condition, owner, method, location, requiredEvidence, riskId, null, null);
    }
}
