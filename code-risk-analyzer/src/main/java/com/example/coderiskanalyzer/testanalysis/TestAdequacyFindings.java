package com.example.coderiskanalyzer.testanalysis;

import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import org.springframework.stereotype.Component;

@Component
public class TestAdequacyFindings {
    public List<Finding> convert(List<TestAdequacyResult> results) {
        return results.stream().map(this::convert).toList();
    }

    private Finding convert(TestAdequacyResult result) {
        TestObligation obligation = result.obligation();
        Severity severity = switch (result.status()) {
            case COVERED -> Severity.INFO;
            case PARTIAL -> obligation.riskSeverity() != null ? obligation.riskSeverity()
                    : obligation.type().equals("AUTHORIZATION") ? Severity.CRITICAL
                    : obligation.type().equals("TRANSACTION_ROLLBACK") || obligation.type().equals("STATE_CHANGE")
                    ? Severity.HIGH : Severity.MEDIUM;
            case MISSING -> obligation.riskSeverity() != null ? obligation.riskSeverity()
                    : obligation.type().equals("AUTHORIZATION") ? Severity.CRITICAL
                    : obligation.type().equals("TRANSACTION_ROLLBACK") || obligation.type().equals("RISK")
                    || obligation.type().equals("STATE_CHANGE") ? Severity.HIGH : Severity.MEDIUM;
            case UNKNOWN -> Severity.LOW;
        };
        String title = obligation.id() + " " + obligation.type() + " " + result.status();
        String message = "%s#%s | %s | %s%nCondition: %s%nRequired: %s%nTests: %s%nCovered: %s%nMissing: %s%nReason: %s"
                .formatted(obligation.owner(), obligation.method(), obligation.id(), result.status(), obligation.condition(),
                        obligation.requiredEvidence(), result.matchingTests(), result.coveredCases(), result.missingCases(), result.reason());
        return new Finding("TEST-001", obligation.riskCategory() == null ? RiskCategory.RELIABILITY : obligation.riskCategory(), severity,
                result.status() == CoverageStatus.UNKNOWN ? Confidence.LOW : Confidence.MEDIUM,
                title, message, obligation.location(), java.util.stream.Stream.concat(java.util.stream.Stream.of(new Evidence("TEST_OBLIGATION", result.reason(),
                obligation.location(), Map.of("obligationId", obligation.id(), "type", obligation.type(),
                "coverageStatus", result.status().name(), "tests", result.matchingTests(),
                "coveredCases", result.coveredCases(), "missingCases", result.missingCases(),
                "riskId", obligation.riskId() == null ? "" : obligation.riskId()))), result.executionEvidence().stream()).toList(), FindingStatus.REVIEW);
    }
}
