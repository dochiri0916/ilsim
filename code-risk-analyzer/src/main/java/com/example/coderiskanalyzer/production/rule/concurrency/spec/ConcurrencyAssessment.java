package com.example.coderiskanalyzer.production.rule.concurrency.spec;

import java.util.List;
import java.util.Objects;

import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.model.SourceLocation;

/** A rule-scoped assessment, including proofs that must not become warning findings. */
public record ConcurrencyAssessment(String ruleId, String specificationRuleId, String declaration,
                                    String subject, List<String> evidencePath, ConcurrencyOutcome outcome,
                                    SourceLocation location, List<Evidence> evidence, List<String> assumptions,
                                    List<String> missingEvidence, List<String> safeguards) {
    public ConcurrencyAssessment {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(location);
        evidencePath = List.copyOf(evidencePath);
        evidence = List.copyOf(evidence);
        assumptions = List.copyOf(assumptions);
        missingEvidence = List.copyOf(missingEvidence);
        safeguards = List.copyOf(safeguards);
        if (ruleId == null || ruleId.isBlank() || specificationRuleId == null || specificationRuleId.isBlank()
                || declaration == null || declaration.isBlank() || subject == null || subject.isBlank()
                || evidence.isEmpty() || evidencePath.isEmpty())
            throw new IllegalArgumentException("Concurrency assessments require a declaration and evidence path");
        if (outcome == ConcurrencyOutcome.UNKNOWN && missingEvidence.isEmpty())
            throw new IllegalArgumentException("UNKNOWN requires missing evidence");
        if (outcome == ConcurrencyOutcome.SAFE_BY_PROOF && (safeguards.isEmpty() || !missingEvidence.isEmpty()))
            throw new IllegalArgumentException("SAFE_BY_PROOF requires protection without missing evidence");
    }
}
