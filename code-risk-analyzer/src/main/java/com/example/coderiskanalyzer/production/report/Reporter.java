package com.example.coderiskanalyzer.production.report;

import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.finding.Diagnostic;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyAssessment;

public interface Reporter {
    String format();
    String report(Path projectRoot, int filesScanned, List<Finding> findings);

    default String report(Path projectRoot, int filesScanned, List<Finding> findings,
                          List<Diagnostic> diagnostics, ScanMetrics metrics) {
        return report(projectRoot, filesScanned, findings);
    }

    default String report(Path projectRoot, int filesScanned, List<Finding> findings,
                          List<Diagnostic> diagnostics, ScanMetrics metrics, List<ConcurrencyAssessment> assessments) {
        return report(projectRoot, filesScanned, findings, diagnostics, metrics);
    }
}
