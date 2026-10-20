package com.example.coderiskanalyzer.production.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Severity;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class AnalyzerConfigLoader {

    private static final String FILE_NAME = ".code-risk.yml";

    public AnalyzerConfig load(Path projectRoot) {
        Path configPath = projectRoot.resolve(FILE_NAME);
        if (!Files.exists(configPath)) return AnalyzerConfig.defaults();
        if (Files.isSymbolicLink(configPath)) throw new AnalyzerConfigException(".code-risk.yml must not be a symbolic link");
        try (InputStream input = Files.newInputStream(configPath)) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            if (loaded == null) return AnalyzerConfig.defaults();
            return parse(asMap(loaded, "configuration"), projectRoot);
        } catch (AnalyzerConfigException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AnalyzerConfigException("Could not read .code-risk.yml: invalid YAML or unreadable file");
        }
    }

    private AnalyzerConfig parse(Map<String, Object> root, Path projectRoot) {
        validateKeys(root, Set.of("analysis", "rules", "suppressions", "report", "fail", "jev"), "configuration");
        Map<String, Object> analysis = map(root.get("analysis"), "analysis");
        validateKeys(analysis, Set.of("source-roots", "excludes", "classpath"), "analysis");
        List<String> sourceRoots = strings(analysis.get("source-roots"), "analysis.source-roots");
        validateRelativePaths(sourceRoots, "analysis.source-roots");
        List<String> excludes = strings(analysis.get("excludes"), "analysis.excludes");
        List<String> configuredClasspath = strings(analysis.get("classpath"), "analysis.classpath");
        validateRelativePaths(configuredClasspath, "analysis.classpath");
        List<String> classpath = expandClasspath(configuredClasspath, projectRoot);
        Map<String, AnalyzerConfig.RuleSettings> rules = new LinkedHashMap<>();
        for (var entry : map(root.get("rules"), "rules").entrySet()) {
            Map<String, Object> settings = asMap(entry.getValue(), "rules." + entry.getKey());
            validateKeys(settings, Set.of("enabled", "severity"), "rules." + entry.getKey());
            boolean enabled = booleanValue(settings.get("enabled"), true, "rules." + entry.getKey() + ".enabled");
            Severity severity = enumValue(settings.get("severity"), Severity.class, "rules." + entry.getKey() + ".severity");
            rules.put(entry.getKey(), new AnalyzerConfig.RuleSettings(enabled, severity));
        }

        List<AnalyzerConfig.SuppressionConfig> suppressions = new ArrayList<>();
        Object suppressionValue = root.get("suppressions");
        if (suppressionValue != null) {
            if (!(suppressionValue instanceof List<?> list)) throw invalid("suppressions must be a list");
            for (int index = 0; index < list.size(); index++) {
                Map<String, Object> item = asMap(list.get(index), "suppressions[" + index + "]");
                String rule = requiredString(item.get("rule"), "suppressions[" + index + "].rule");
                String path = requiredString(item.get("path"), "suppressions[" + index + "].path");
                validatePattern(path, "suppressions[" + index + "].path");
                String reason = requiredString(item.get("reason"), "suppressions[" + index + "].reason");
                suppressions.add(new AnalyzerConfig.SuppressionConfig(rule, path, reason));
            }
        }

        Map<String, Object> report = map(root.get("report"), "report");
        validateKeys(report, Set.of("format"), "report");
        Map<String, Object> fail = map(root.get("fail"), "fail");
        validateKeys(fail, Set.of("severity", "confidence", "max-unknown-ratio", "new-test-gaps", "max-survived-mutations", "mutation-exceptions",
                "include-review", "on-jev-failure"), "fail");
        Map<String, Object> jev = map(root.get("jev"), "jev");
        validateKeys(jev, Set.of("enabled", "max-calls"), "jev");
        if (booleanValue(jev.get("enabled"), false, "jev.enabled")) {
            throw invalid("JEV configuration is not available in non-JEV scan configuration");
        }
        String reportFormat = stringValue(report.get("format"), "console", "report.format");
        Severity failSeverity = enumValue(fail.get("severity"), Severity.class, "fail.severity");
        Confidence failConfidence = enumValue(fail.get("confidence"), Confidence.class, "fail.confidence");
        Double maxUnknown = null;
        if (fail.containsKey("max-unknown-ratio")) {
            Object value = fail.get("max-unknown-ratio");
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0 || number.doubleValue() > 1)
                throw invalid("fail.max-unknown-ratio must be a number between 0 and 1");
            maxUnknown = number.doubleValue();
        }
        Integer maxSurvived = null;
        if (fail.containsKey("max-survived-mutations")) {
            Object value = fail.get("max-survived-mutations");
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0 || number.doubleValue() > Integer.MAX_VALUE
                    || number.doubleValue() != number.intValue())
                throw invalid("fail.max-survived-mutations must be a non-negative integer");
            maxSurvived = number.intValue();
        }
        List<AnalyzerConfig.MutationException> exceptions = new ArrayList<>();
        if (fail.containsKey("mutation-exceptions")) {
            if (!(fail.get("mutation-exceptions") instanceof List<?> list))
                throw invalid("fail.mutation-exceptions must be a list");
            Set<String> seen = new java.util.HashSet<>();
            for (Object value : list) {
                Map<String, Object> item = asMap(value, "fail.mutation-exceptions");
                validateKeys(item, Set.of("fingerprint", "reason"), "fail.mutation-exceptions");
                String fingerprint = requiredString(item.get("fingerprint"), "mutation exception fingerprint");
                if (!fingerprint.matches("[0-9a-f]{64}") || !seen.add(fingerprint))
                    throw invalid("mutation exception fingerprint must be a unique lowercase SHA-256 value");
                exceptions.add(new AnalyzerConfig.MutationException(fingerprint,
                        requiredString(item.get("reason"), "mutation exception reason")));
            }
        }
        Integer maxJevCalls = null;
        if (jev.containsKey("max-calls")) {
            Object value = jev.get("max-calls");
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0 || number.doubleValue() > Integer.MAX_VALUE
                    || number.doubleValue() != number.intValue())
                throw invalid("jev.max-calls must be a non-negative integer");
            maxJevCalls = number.intValue();
        }
        var policy = new AnalyzerConfig.CiPolicy(maxUnknown,
                booleanValue(fail.get("new-test-gaps"), false, "fail.new-test-gaps"), maxSurvived, exceptions,
                booleanValue(fail.get("include-review"), false, "fail.include-review"),
                booleanValue(fail.get("on-jev-failure"), false, "fail.on-jev-failure"), maxJevCalls);
        return new AnalyzerConfig(sourceRoots, excludes, classpath, rules, suppressions, reportFormat,
                failSeverity, failConfidence, policy);
    }

    private List<String> expandClasspath(List<String> entries, Path projectRoot) {
        var expanded = new java.util.LinkedHashSet<String>();
        for (String entry : entries) {
            if (entry.chars().anyMatch(c -> "*?[{".indexOf(c) >= 0)) {
                List<String> matches = matchJars(entry, projectRoot);
                if (matches.isEmpty()) throw invalid("analysis.classpath pattern matched no JAR files: " + entry);
                for (String match : matches) {
                    validateJar(projectRoot.resolve(match));
                    expanded.add(match);
                }
                continue;
            }
            Path path = projectRoot.resolve(entry).normalize();
            rejectSymbolicLinks(projectRoot, path);
            if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                validateClassDirectory(path);
            } else if (!Files.isRegularFile(path) || !Files.isReadable(path) || !entry.endsWith(".jar")) {
                throw invalid("analysis.classpath entries must be readable JAR files, class directories or JAR patterns inside the project root");
            } else {
                validateJar(path);
            }
            expanded.add(projectRoot.relativize(path).toString());
        }
        return List.copyOf(expanded);
    }

    private List<String> matchJars(String pattern, Path projectRoot) {
        if (!pattern.endsWith(".jar")) throw invalid("analysis.classpath patterns must match JAR files (end with .jar)");
        Path prefix = projectRoot;
        for (String part : pattern.split("/")) {
            if (part.chars().anyMatch(c -> "*?[{".indexOf(c) >= 0)) break;
            prefix = prefix.resolve(part);
        }
        prefix = prefix.normalize();
        rejectSymbolicLinks(projectRoot, prefix);
        if (!Files.isDirectory(prefix, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return List.of();
        var matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (var files = Files.walk(prefix)) {
            return files.filter(file -> Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .map(projectRoot::relativize)
                    .filter(matcher::matches)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (java.io.IOException | java.io.UncheckedIOException exception) {
            throw invalid("analysis.classpath pattern could not be read: " + pattern);
        }
    }

    private void rejectSymbolicLinks(Path projectRoot, Path path) {
        Path current = projectRoot;
        for (Path part : projectRoot.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw invalid("analysis.classpath may not traverse symbolic links");
        }
    }

    private void validateJar(Path jar) {
        try (var ignored = new java.util.jar.JarFile(jar.toFile())) {
        } catch (java.io.IOException exception) {
            throw invalid("analysis.classpath contains an unreadable or invalid JAR");
        }
    }

    private void validateClassDirectory(Path directory) {
        try (var files = Files.walk(directory)) {
            if (files.noneMatch(file -> file.toString().endsWith(".class")
                    && Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)))
                throw invalid("analysis.classpath class directory contains no .class files: " + directory.getFileName());
        } catch (java.io.IOException | java.io.UncheckedIOException exception) {
            throw invalid("analysis.classpath class directory could not be read");
        }
    }

    private Map<String, Object> map(Object value, String key) {
        return value == null ? Map.of() : asMap(value, key);
    }

    private Map<String, Object> asMap(Object value, String key) {
        if (!(value instanceof Map<?, ?> source)) throw invalid(key + " must be a mapping");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String name)) throw invalid(key + " contains a non-string key");
            result.put(name, entry.getValue());
        }
        return result;
    }

    private List<String> strings(Object value, String key) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw invalid(key + " must be a list");
        return list.stream().map(item -> requiredString(item, key)).toList();
    }

    private String requiredString(Object value, String key) {
        if (!(value instanceof String string) || string.isBlank()) throw invalid(key + " must be a non-empty string");
        return string.trim();
    }

    private String stringValue(Object value, String fallback, String key) {
        return value == null ? fallback : requiredString(value, key);
    }

    private boolean booleanValue(Object value, boolean fallback, String key) {
        if (value == null) return fallback;
        if (value instanceof Boolean booleanValue) return booleanValue;
        throw invalid(key + " must be true or false");
    }

    private <E extends Enum<E>> E enumValue(Object value, Class<E> enumType, String key) {
        if (value == null) return null;
        if (!(value instanceof String string)) throw invalid(key + " must be a string");
        try {
            return Enum.valueOf(enumType, string.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid(key + " has an unsupported value");
        }
    }

    private void validateRelativePaths(List<String> paths, String key) {
        for (String value : paths) {
            try {
                Path path = Path.of(value);
                if (path.isAbsolute() || path.normalize().startsWith("..")) throw invalid(key + " must stay within the project root");
            } catch (java.nio.file.InvalidPathException exception) {
                throw invalid(key + " contains an invalid path");
            }
        }
    }

    private void validatePattern(String pattern, String key) {
        try {
            Path path = Path.of(pattern);
            if (path.isAbsolute() || path.normalize().startsWith("..")) throw invalid(key + " must stay within the project root");
        } catch (java.nio.file.InvalidPathException exception) {
            throw invalid(key + " contains an invalid path pattern");
        }
    }

    private void validateKeys(Map<String, Object> values, java.util.Set<String> allowed, String key) {
        values.keySet().stream().filter(name -> !allowed.contains(name)).findFirst()
                .ifPresent(name -> { throw invalid("unknown configuration key " + key + "." + name); });
    }

    private AnalyzerConfigException invalid(String message) { return new AnalyzerConfigException(message); }
}
