package com.example.coderiskanalyzer.production.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Diagnostic;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

@Component
public class JsonReporter implements Reporter {

    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Override public String format() { return "json"; }

    @Override
    public String report(Path projectRoot, int filesScanned, List<Finding> findings) {
        return report(projectRoot, filesScanned, findings, List.of(), new ScanMetrics(0, 0, 0, 0, Map.of()));
    }

    @Override
    public String report(Path projectRoot, int filesScanned, List<Finding> findings,
                         List<Diagnostic> diagnostics, ScanMetrics metrics) {
        var payload = new JsonReport("1", new Summary(filesScanned, findings.size()),
                diagnostics.stream().map(diagnostic -> new JsonDiagnostic(diagnostic.severity().name(),
                        diagnostic.code(), diagnostic.message(), diagnostic.file() == null ? null
                                : rootRelative(projectRoot, diagnostic.file()))).toList(),
                metrics,
                findings.stream().map(finding -> toJsonFinding(projectRoot, finding)).toList());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not render JSON report", exception);
        }
    }

    private JsonFinding toJsonFinding(Path root, Finding finding) {
        String path = rootRelative(root, finding.location().file());
        return new JsonFinding(finding.ruleId(), finding.category().name(), finding.severity().name(),
                finding.confidence().name(), finding.status().name(), finding.title(), finding.message(),
                new JsonLocation(path, finding.location().startLine()),
                finding.evidence().stream().map(item -> new JsonEvidence(item.type(), item.message(),
                        new JsonLocation(rootRelative(root, item.location().file()),
                                item.location().startLine()), item.attributes())).toList());
    }

    private String rootRelative(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        return (normalizedFile.startsWith(normalizedRoot) ? normalizedRoot.relativize(normalizedFile) : normalizedFile)
                .toString().replace('\\', '/');
    }

    private record JsonReport(String version, Summary summary, List<JsonDiagnostic> diagnostics,
                              ScanMetrics metrics, List<JsonFinding> findings) {}
    private record Summary(int filesScanned, int findings) {}
    private record JsonLocation(String file, int startLine) {}
    private record JsonDiagnostic(String severity, String code, String message, String file) {}
    private record JsonFinding(String ruleId, String category, String severity, String confidence,
                               String status, String title, String message, JsonLocation location,
                               List<JsonEvidence> evidence) {}
    private record JsonEvidence(String type, String message, JsonLocation location,
                                java.util.Map<String, Object> attributes) {}
}
