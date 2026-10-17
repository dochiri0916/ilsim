package com.example.coderiskanalyzer.production.config;

import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Severity;

public record AnalyzerConfig(
        List<String> sourceRoots,
        List<String> excludes,
        List<String> classpath,
        Map<String, RuleSettings> rules,
        List<SuppressionConfig> suppressions,
        String reportFormat,
        Severity failSeverity,
        Confidence failConfidence,
        CiPolicy ciPolicy) {

    public AnalyzerConfig {
        ciPolicy = ciPolicy == null ? CiPolicy.defaults() : ciPolicy;
        sourceRoots = List.copyOf(sourceRoots);
        excludes = List.copyOf(excludes);
        classpath = List.copyOf(classpath);
        rules = Map.copyOf(rules);
        suppressions = List.copyOf(suppressions);
        reportFormat = reportFormat == null || reportFormat.isBlank() ? "console" : reportFormat;
        failSeverity = failSeverity == null ? Severity.HIGH : failSeverity;
        failConfidence = failConfidence == null ? Confidence.HIGH : failConfidence;
    }

    public AnalyzerConfig(List<String> sourceRoots, List<String> excludes, List<String> classpath,
                          Map<String, RuleSettings> rules, List<SuppressionConfig> suppressions,
                          String reportFormat, Severity failSeverity, Confidence failConfidence) {
        this(sourceRoots, excludes, classpath, rules, suppressions, reportFormat, failSeverity, failConfidence,
                CiPolicy.defaults());
    }

    public record CiPolicy(Double maxUnknownRatio, boolean newTestGaps, Integer maxSurvivedMutations,
                           List<MutationException> mutationExceptions, boolean includeReview, boolean onJevFailure,
                           Integer maxJevCalls) {
        public CiPolicy { mutationExceptions = List.copyOf(mutationExceptions); }
        public CiPolicy(Double maxUnknownRatio, boolean newTestGaps, Integer maxSurvivedMutations,
                        List<MutationException> mutationExceptions, boolean includeReview, boolean onJevFailure) {
            this(maxUnknownRatio, newTestGaps, maxSurvivedMutations, mutationExceptions, includeReview, onJevFailure, null);
        }
        public CiPolicy(Double maxUnknownRatio, boolean newTestGaps, Integer maxSurvivedMutations,
                        List<MutationException> mutationExceptions) {
            this(maxUnknownRatio, newTestGaps, maxSurvivedMutations, mutationExceptions, false, false);
        }
        public static CiPolicy defaults() { return new CiPolicy(null, false, null, List.of()); }
    }
    public record MutationException(String fingerprint, String reason) {}

    public AnalyzerConfig(List<String> sourceRoots, List<String> excludes, Map<String, RuleSettings> rules,
                          List<SuppressionConfig> suppressions, String reportFormat,
                          Severity failSeverity, Confidence failConfidence) {
        this(sourceRoots, excludes, List.of(), rules, suppressions, reportFormat, failSeverity, failConfidence);
    }

    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig(List.of(), List.of(), Map.of(), List.of(), "console", Severity.HIGH, Confidence.HIGH);
    }

    public record RuleSettings(boolean enabled, Severity severity) {}
    public record SuppressionConfig(String rule, String path, String reason) {}
}
