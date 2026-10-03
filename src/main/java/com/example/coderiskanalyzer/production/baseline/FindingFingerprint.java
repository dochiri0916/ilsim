package com.example.coderiskanalyzer.production.baseline;

import java.nio.file.Path;

import com.example.coderiskanalyzer.production.finding.Finding;

public final class FindingFingerprint {

    private FindingFingerprint() {}

    public static String of(Path root, Finding finding) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path file = finding.location().file().toAbsolutePath().normalize();
        String relative = file.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(file).toString().replace('\\', '/')
                : file.toString().replace('\\', '/');
        String sink = finding.evidence().stream().map(evidence -> {
            Object value = evidence.attributes().getOrDefault("sink",
                    evidence.attributes().getOrDefault("operation", evidence.attributes().get("method")));
            return value == null ? "" : value.toString();
        }).filter(value -> !value.isBlank()).findFirst().orElse(finding.title());
        return finding.ruleId() + "|" + relative + "|" + finding.location().startLine() + "|" + sink;
    }
}
