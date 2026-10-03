package com.example.coderiskanalyzer.production.suppression;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class SuppressionMatcher {

    private final AntPathMatcher pathMatcher = new AntPathMatcher("/");

    public List<Finding> apply(Path projectRoot, List<Finding> findings,
                               List<AnalyzerConfig.SuppressionConfig> suppressions) {
        Path root = projectRoot.toAbsolutePath().normalize();
        return findings.stream().map(finding -> suppressions.stream()
                .filter(rule -> rule.rule().equals("*") || rule.rule().equals(finding.ruleId()))
                .filter(rule -> matchesPath(root, finding.location().file(), rule.path()))
                .findFirst().map(rule -> suppressed(finding, rule.reason())).orElse(finding)).toList();
    }

    private boolean matchesPath(Path root, Path file, String pattern) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) return false;
        String relative = root.relativize(normalized).toString().replace('\\', '/');
        return pathMatcher.match(pattern, relative);
    }

    private Finding suppressed(Finding finding, String reason) {
        Evidence evidence = new Evidence("CONFIG_SUPPRESSION", "Suppressed by .code-risk.yml: " + reason,
                finding.location(), Map.of("reason", reason));
        java.util.ArrayList<Evidence> combined = new java.util.ArrayList<>(finding.evidence());
        combined.add(evidence);
        return new Finding(finding.ruleId(), finding.category(), finding.severity(), finding.confidence(),
                finding.title(), finding.message(), finding.location(), combined,
                FindingStatus.SUPPRESSED);
    }
}
