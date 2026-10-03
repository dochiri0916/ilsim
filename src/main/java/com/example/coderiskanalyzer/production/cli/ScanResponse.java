package com.example.coderiskanalyzer.production.cli;

import java.util.List;

import com.example.coderiskanalyzer.production.finding.Diagnostic;
import com.example.coderiskanalyzer.production.report.ScanMetrics;

public record ScanResponse(String output, int exitCode, List<Diagnostic> diagnostics, ScanMetrics metrics) {
    public ScanResponse { diagnostics = List.copyOf(diagnostics); }
}
