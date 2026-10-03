package com.example.coderiskanalyzer.rule.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowAnalyzer;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.security.PathTraversalRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathTraversalRuleTests {

    @TempDir Path tempDir;

    @Test
    void reportsRequestInputAtFileReadSink() throws Exception {
        assertThat(analyze("@RequestParam String path) { Files.readString(Path.of(path)); }"))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("SEC-003");
                    assertThat(finding.status().name()).isEqualTo("REVIEW");
                });
    }

    @Test
    void reportsFileStreamSinkAndIgnoresPathConstructionWithoutFileAccess() throws Exception {
        assertThat(analyze("@RequestParam String path) { new FileInputStream(path); }"))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("SEC-003"));
        assertThat(analyze("@RequestParam String path) { Path resolved = Path.of(\"/safe\").resolve(path); }"))
                .isEmpty();
    }

    @Test
    void ignoresPathThatIsNormalizedAndRejectedWhenOutsideBase() throws Exception {
        assertThat(analyze("@RequestParam String path) { Path base = root.toRealPath(); "
                + "Path resolved = base.resolve(path).normalize(); "
                + "if (!resolved.startsWith(base)) { throw new IllegalArgumentException(); } "
                + "Files.readString(resolved); }"))
                .isEmpty();
    }


    @Test
    void startsWithGuardWithoutNormalizationDoesNotProtectSink() throws Exception {
        assertThat(analyze("@RequestParam String path) { Path base = root.toRealPath(); "
                + "Path resolved = base.resolve(path); "
                + "if (!resolved.startsWith(base)) { throw new IllegalArgumentException(); } "
                + "Files.readString(resolved); }"))
                .hasSize(1);
    }

    @Test
    void keepsWarningsForGuardsThatDoNotProtectTheSink() throws Exception {
        String setup = "@RequestParam String path) { Path base = root.toRealPath(); "
                + "Path resolved = base.resolve(path).normalize(); ";
        String reject = "if (!resolved.startsWith(base)) { throw new IllegalArgumentException(); } ";
        for (String body : List.of(
                "Files.readString(resolved); " + reject,
                "if (enabled) { " + reject + "} Files.readString(resolved);",
                "Path other = base.resolve(path + \"other\").normalize(); if (!other.startsWith(base)) return; Files.readString(resolved);",
                reject + "resolved = Path.of(path); Files.readString(resolved);",
                "if (!resolved.startsWith(base)) { if (enabled) return; } Files.readString(resolved);",
                "if (!resolved.startsWith(base)) { Runnable task = () -> { throw new IllegalArgumentException(); }; } Files.readString(resolved);",
                "try { " + reject + "} catch (Exception ignored) {} Files.readString(resolved);",
                "while (enabled) { " + reject + "break; } Files.readString(resolved);",
                "if (!resolved.startsWith(base) && enabled) return; Files.readString(resolved);",
                reject + "Files.readString(Path.of(path));",
                reject + "Files.copy(resolved, Path.of(path));")) {
            assertThat(analyze(setup + body + "}")).as(body).hasSize(1);
        }
        assertThat(analyze("@RequestParam String path, @RequestParam String other) { Path base = root.toRealPath(); "
                + "Path sourcePath = base.resolve(path).normalize(); if (!sourcePath.startsWith(base)) return; "
                + "Files.copy(sourcePath, Path.of(other)); }")).hasSize(1);
        assertThat(analyze("@RequestParam String path) { Path base = root.toRealPath(); "
                + "Path first = choose(path); Path second = choose(path); "
                + "if (!first.startsWith(base)) return; Files.readString(second); }")).hasSize(1);
        assertThat(analyze("@RequestParam String path) { String base = \"/safe\"; String resolved = base + path; "
                + "if (!resolved.startsWith(base)) return; Files.readString(resolved); }")).hasSize(1);
    }

    @Test
    void ignoresDeclarationsInOtherScopesWhenResolvingPathTypes() throws Exception {
        assertThat(analyze("@RequestParam String path) { String base = \"/safe\"; String resolved = base + path; "
                + "Runnable task = () -> { Path base = root.toRealPath(); Path resolved = base.resolve(path); }; "
                + "if (!resolved.startsWith(base)) return; Files.readString(resolved); }")).hasSize(1);
        assertThat(analyze("@RequestParam String path) { Path base = root.toRealPath(); Path resolved = Path.of(path); "
                + "{ Path resolved = base.resolve(path).normalize(); if (!resolved.startsWith(base)) return; } "
                + "Files.readString(resolved); }")).hasSize(1);
    }

    @Test
    void checksEachSinkAndAcceptsValidationOnEveryBranch() throws Exception {
        String setup = "@RequestParam String path) { Path base = root.toRealPath(); "
                + "Path resolved = base.resolve(path).normalize(); ";
        String reject = "if (!resolved.startsWith(base)) return; ";
        assertThat(analyze(setup + "Files.readString(resolved); " + reject + "Files.readString(resolved); }")).hasSize(1);
        assertThat(analyze(setup + "if (enabled) { " + reject + "} else { " + reject
                + "} Path alias = resolved; Files.readString(alias); }")).isEmpty();
        assertThat(analyze(setup + "if (resolved.startsWith(base)) { new FileInputStream(resolved); } }")).isEmpty();
        assertThat(analyze(setup + "if (resolved.startsWith(base) == false) return; Files.readString(resolved); }")).isEmpty();
        assertThat(analyze(setup + "if (!resolved.startsWith(base) || enabled) return; Files.readString(resolved); }")).isEmpty();
    }

    private List<Finding> analyze(String method) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Controller.java");
        Files.writeString(file, "class Controller { void download(" + method + " }");
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
        return new PathTraversalRule(new DataFlowAnalyzer()).analyze(context);
    }
}
