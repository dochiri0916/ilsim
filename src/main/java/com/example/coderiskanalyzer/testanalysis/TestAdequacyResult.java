package com.example.coderiskanalyzer.testanalysis;

import java.util.List;

public record TestAdequacyResult(TestObligation obligation, CoverageStatus status,
                                 List<String> matchingTests, List<String> coveredCases,
                                 List<String> missingCases, String reason,
                                 List<com.example.coderiskanalyzer.production.finding.Evidence> executionEvidence) {
    public TestAdequacyResult(TestObligation obligation, CoverageStatus status,
                              List<String> matchingTests, List<String> coveredCases,
                              List<String> missingCases, String reason) {
        this(obligation, status, matchingTests, coveredCases, missingCases, reason, List.of());
    }
    public TestAdequacyResult {
        executionEvidence = List.copyOf(executionEvidence);
        matchingTests = List.copyOf(matchingTests);
        coveredCases = List.copyOf(coveredCases);
        missingCases = List.copyOf(missingCases);
    }
}
