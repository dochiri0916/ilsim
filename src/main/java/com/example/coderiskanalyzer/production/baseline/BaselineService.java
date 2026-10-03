package com.example.coderiskanalyzer.production.baseline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.example.coderiskanalyzer.production.finding.Finding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class BaselineService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void create(Path projectRoot, Path baselinePath, List<Finding> findings) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path output = baselinePath.toAbsolutePath().normalize();
        List<BaselineFinding> entries = findings.stream().map(finding -> new BaselineFinding(
                FindingFingerprint.of(root, finding), finding.ruleId(), relative(root, finding.location().file()),
                finding.location().startLine())).distinct().sorted(java.util.Comparator.comparing(BaselineFinding::fingerprint)).toList();
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Baseline("1", entries));
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent == null ? Path.of(".") : parent, ".code-risk-baseline-", ".tmp");
        try {
            Files.write(temporary, json);
            try {
                Files.move(temporary, output, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public List<Finding> excludeExisting(Path projectRoot, Path baselinePath, List<Finding> findings) throws IOException {
        if (!Files.isRegularFile(baselinePath)) throw new IOException("Baseline file does not exist or is not a file");
        Baseline baseline;
        try {
            baseline = objectMapper.readValue(Files.readAllBytes(baselinePath), Baseline.class);
        } catch (JsonProcessingException exception) {
            throw new IOException("Baseline file is invalid JSON", exception);
        }
        if (baseline == null || !"1".equals(baseline.version()) || baseline.findings() == null
                || baseline.findings().stream().anyMatch(entry -> entry == null || entry.fingerprint() == null
                        || entry.fingerprint().isBlank())) {
            throw new IOException("Baseline schema version is unsupported");
        }
        Set<String> existing = baseline.findings().stream().map(BaselineFinding::fingerprint)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Path root = projectRoot.toAbsolutePath().normalize();
        return findings.stream().filter(finding -> !existing.contains(FindingFingerprint.of(root, finding))).toList();
    }

    private String relative(Path root, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return (normalized.startsWith(root) ? root.relativize(normalized) : normalized).toString().replace('\\', '/');
    }

    private record Baseline(String version, List<BaselineFinding> findings) {}
    private record BaselineFinding(String fingerprint, String ruleId, String file, int line) {}
}
