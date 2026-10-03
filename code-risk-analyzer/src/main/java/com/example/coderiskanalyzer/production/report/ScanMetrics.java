package com.example.coderiskanalyzer.production.report;

import java.util.Map;
import java.util.Collections;
import java.util.TreeMap;

public record ScanMetrics(int filesParsed, int filesFailedToParse, int classes, int methods,
                          Map<String, Long> phaseMillis, AnalysisCompleteness completeness) {
    public ScanMetrics(int filesParsed, int filesFailedToParse, int classes, int methods, Map<String, Long> phaseMillis) {
        this(filesParsed, filesFailedToParse, classes, methods, phaseMillis, AnalysisCompleteness.empty());
    }
    public ScanMetrics { phaseMillis = Collections.unmodifiableMap(new TreeMap<>(phaseMillis)); }
}
