package com.example.coderiskanalyzer.production.cli;

public record ScanRequest(String path, String format, String output, String jev,
                          String failOn, String baseline, String filesFile, boolean verbose,
                          String executionEvidence) {
    public ScanRequest(String path, String format, String output, String jev,
                       String failOn, String baseline, String filesFile, boolean verbose) {
        this(path, format, output, jev, failOn, baseline, filesFile, verbose, "");
    }
}
