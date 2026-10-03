package com.example.coderiskanalyzer.production.report;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Diagnostic;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

/** Renders findings as SARIF 2.1.0 for CI and code-scanning integrations. */
@Component
public class SarifReporter implements Reporter {

    private static final String SARIF_VERSION = "2.1.0";
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Override public String format() { return "sarif"; }

    @Override
    public String report(Path projectRoot, int filesScanned, List<Finding> findings) {
        return report(projectRoot, filesScanned, findings, List.of(), new ScanMetrics(0, 0, 0, 0, Map.of()));
    }

    @Override
    public String report(Path projectRoot, int filesScanned, List<Finding> findings,
                         List<Diagnostic> diagnostics, ScanMetrics metrics) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Rule> rules = findings.stream()
                .collect(java.util.stream.Collectors.toMap(Finding::ruleId, finding -> finding,
                        (first, ignored) -> first, LinkedHashMap::new))
                .values().stream().sorted(Comparator.comparing(Finding::ruleId))
                .map(SarifReporter::toRule).toList();
        Map<String, Integer> ruleIndexes = new LinkedHashMap<>();
        for (int index = 0; index < rules.size(); index++) ruleIndexes.put(rules.get(index).id(), index);

        List<Result> results = findings.stream()
                .map(finding -> toResult(root, finding, ruleIndexes.get(finding.ruleId())))
                .toList();
        List<SarifDiagnostic> sarifDiagnostics = diagnostics.stream().map(diagnostic -> new SarifDiagnostic(
                diagnostic.severity().name(), diagnostic.code(), diagnostic.message(),
                diagnostic.file() == null ? null : relativeUri(root, diagnostic.file()))).toList();
        Map<String, Object> runProperties = Map.of("filesScanned", filesScanned, "metrics", metrics,
                "diagnostics", sarifDiagnostics);
        SarifReport payload = new SarifReport("https://json.sarif.org/sarif-2.1.0.json", SARIF_VERSION,
                List.of(new Run(new Tool(new Driver("Code Risk Analyzer", "0.0.1", rules)), results, runProperties)));
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not render SARIF report", exception);
        }
    }

    private static Rule toRule(Finding finding) {
        String description = finding.message();
        return new Rule(finding.ruleId(), finding.ruleId(), new Message(finding.title()),
                new Message(description), new Configuration(toLevel(finding.severity())),
                new Message(description), Map.of("category", finding.category().name()));
    }

    private Result toResult(Path root, Finding finding, int ruleIndex) {
        List<Location> locations = List.of(new Location(new PhysicalLocation(
                new ArtifactLocation(relativeUri(root, finding.location().file())),
                region(finding.location().startLine(), finding.location().startColumn(),
                        finding.location().endLine(), finding.location().endColumn()))));
        List<SarifEvidence> evidence = finding.evidence().stream().map(item -> new SarifEvidence(
                item.type(), item.message(), relativeUri(root, item.location().file()),
                positive(item.location().startLine()), positive(item.location().startColumn()), item.attributes())).toList();
        return new Result(finding.ruleId(), ruleIndex, toLevel(finding.severity()), new Message(finding.message()),
                locations, Map.of("category", finding.category().name(), "confidence", finding.confidence().name(),
                        "status", finding.status().name(), "evidence", evidence));
    }

    private static Region region(int startLine, int startColumn, int endLine, int endColumn) {
        Integer line = positive(startLine);
        if (line == null) return null;
        return new Region(line, positive(startColumn), positive(endLine), positive(endColumn));
    }

    private static Integer positive(int value) {
        return value > 0 ? value : null;
    }

    private static String relativeUri(Path root, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        String path = (normalized.startsWith(root) ? root.relativize(normalized) : normalized)
                .toString().replace('\\', '/');
        try {
            return new URI(null, path, null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Could not encode source path for SARIF: " + path, exception);
        }
    }

    private static String toLevel(Severity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFO -> "note";
        };
    }

    private record SarifReport(@JsonProperty("$schema") String schema, String version, List<Run> runs) {}
    private record Run(Tool tool, List<Result> results, Map<String, Object> properties) {}
    private record Tool(Driver driver) {}
    private record Driver(String name, String version, List<Rule> rules) {}
    private record Rule(String id, String name, Message shortDescription, Message fullDescription,
                        Configuration defaultConfiguration, Message help, Map<String, Object> properties) {}
    private record Configuration(String level) {}
    private record Result(String ruleId, int ruleIndex, String level, Message message,
                         List<Location> locations, Map<String, Object> properties) {}
    private record Location(PhysicalLocation physicalLocation) {}
    private record PhysicalLocation(ArtifactLocation artifactLocation, Region region) {}
    private record ArtifactLocation(String uri) {}
    private record Region(Integer startLine, Integer startColumn, Integer endLine, Integer endColumn) {}
    private record Message(String text) {}
    private record SarifEvidence(String type, String message, String uri, Integer startLine, Integer startColumn,
                                 Map<String, Object> attributes) {}
    private record SarifDiagnostic(String severity, String code, String message, String uri) {}
}
