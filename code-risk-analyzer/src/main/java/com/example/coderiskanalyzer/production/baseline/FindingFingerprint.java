package com.example.coderiskanalyzer.production.baseline;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyAssessment;

import com.example.coderiskanalyzer.production.finding.Finding;

public final class FindingFingerprint {

    private FindingFingerprint() {}

    /** Additive v2 identity for rules with semantic declaration and evidence paths; v1 stays intact. */
    public static String stableOf(Path root, Finding finding) {
        return finding.concurrencyAssessment() == null ? null : stableOf(root, finding.concurrencyAssessment());
    }

    public static String stableOf(Path root, ConcurrencyAssessment assessment) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path file = assessment.location().file().toAbsolutePath().normalize();
        String relative = (file.startsWith(normalizedRoot) ? normalizedRoot.relativize(file) : file)
                .toString().replace('\\', '/');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.util.stream.Stream.concat(java.util.stream.Stream.of(assessment.declaration(), assessment.subject()),
                    assessment.evidencePath().stream()).forEach(value -> {
                        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                        digest.update(bytes);
                    });
            return "v2|" + assessment.ruleId() + "|" + relative + "|" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

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
