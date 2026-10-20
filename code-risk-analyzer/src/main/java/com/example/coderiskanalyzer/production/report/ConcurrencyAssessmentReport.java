package com.example.coderiskanalyzer.production.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.baseline.FindingFingerprint;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyAssessment;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyOutcome;

public record ConcurrencyAssessmentReport(String ruleId, String specificationRuleId, String fingerprint,
                                          String declaration, String subject, List<String> evidencePath,
                                          ConcurrencyOutcome outcome, Location location, List<ProofEvidence> evidence,
                                          List<String> assumptions, List<String> missingEvidence, List<String> safeguards) {
    public static ConcurrencyAssessmentReport from(Path root, ConcurrencyAssessment assessment) {
        return new ConcurrencyAssessmentReport(assessment.ruleId(), assessment.specificationRuleId(),
                FindingFingerprint.stableOf(root, assessment), assessment.declaration(), assessment.subject(),
                assessment.evidencePath(), assessment.outcome(), location(root, assessment.location()),
                assessment.evidence().stream().map(item -> new ProofEvidence(item.type(), item.message(),
                        location(root, item.location()), item.attributes())).toList(),
                assessment.assumptions(), assessment.missingEvidence(), assessment.safeguards());
    }

    private static Location location(Path root, SourceLocation source) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path file = source.file().toAbsolutePath().normalize();
        return new Location((file.startsWith(normalizedRoot) ? normalizedRoot.relativize(file) : file)
                .toString().replace('\\', '/'), source.startLine());
    }

    public record Location(String file, int startLine) {}
    public record ProofEvidence(String type, String message, Location location, Map<String, Object> attributes) {}
}
