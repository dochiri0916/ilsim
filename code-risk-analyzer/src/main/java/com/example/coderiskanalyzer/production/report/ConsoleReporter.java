package com.example.coderiskanalyzer.production.report;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencySpecCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConsoleReporter implements Reporter {

    private final ConcurrencySpecCatalog concurrencySpecification;

    public ConsoleReporter() { this(new ConcurrencySpecCatalog()); }

    @Autowired
    public ConsoleReporter(ConcurrencySpecCatalog concurrencySpecification) {
        this.concurrencySpecification = concurrencySpecification;
    }

    @Override public String format() { return "console"; }

    @Override
    public String report(Path root, int files, List<Finding> findings,
                         List<com.example.coderiskanalyzer.production.finding.Diagnostic> diagnostics, ScanMetrics metrics) {
        var c = metrics.completeness();
        return report(root, files, findings) + "\n\nAnalysis completeness:\n"
                + "filesDiscovered=" + c.filesDiscovered() + ", filesSelected=" + c.filesSelected()
                + ", filesParsedAsContext=" + c.filesParsedAsContext() + "\n"
                + "methodCalls=" + c.methodCalls() + ", unresolvedMethodCalls=" + c.unresolvedMethodCalls()
                + ", unresolvedCallRatio=" + c.unresolvedCallRatio() + "\n"
                + "testObligations=" + c.testObligations() + ", unknownTestObligations=" + c.unknownTestObligations()
                + ", unknownRatio=" + c.unknownRatio() + "\n"
                + "limitedPatterns=" + c.limitedPatterns() + "\nconfiguredSourceRoots=" + c.configuredSourceRoots()
                + "\nconfiguredExcludes=" + c.configuredExcludes() + "\ndisabledRules=" + c.disabledRules()
                + "\n" + String.join("\n", c.limitations())
                + "\nParse failures: " + metrics.filesFailedToParse() + "\n"
                + diagnostics.stream().map(d -> d.severity() + " " + d.code() + ": " + d.message())
                        .collect(Collectors.joining("\n"));
    }

    @Override
    public String report(Path projectRoot, int filesScanned, List<Finding> findings) {
        String body = findings.isEmpty() ? "No findings." : findings.stream().map(finding -> {
            String file = projectRoot.toAbsolutePath().normalize()
                    .relativize(finding.location().file().toAbsolutePath().normalize()).toString();
            String evidence = finding.evidence().stream()
                    .map(item -> "  - " + item.message()
                            + (item.type().equals("EXECUTION_TEST") ? "\n    EXECUTION_TEST: " + item.attributes() : ""))
                    .collect(Collectors.joining(System.lineSeparator()));
            var concurrency = concurrencySpecification.forFinding(finding);
            String specification = concurrency == null ? "" : "%nConcurrency: %s  %s  %s  %s".formatted(
                    String.join(", ", concurrency.specificationRuleIds()), concurrency.outcome(),
                    concurrency.priority(), concurrency.implementationStatus());
            return "%s  %s  %s%n%s:%d%n%s%nConfidence: %s  Status: %s".formatted(
                    finding.severity(), finding.ruleId(), finding.title(), file,
                    finding.location().startLine(), finding.message() + System.lineSeparator() + evidence,
                    finding.confidence(), finding.status()) + specification;
        }).collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
        return "Files scanned: " + filesScanned + System.lineSeparator() + "Findings: " + findings.size()
                + System.lineSeparator() + System.lineSeparator() + body;
    }
}
