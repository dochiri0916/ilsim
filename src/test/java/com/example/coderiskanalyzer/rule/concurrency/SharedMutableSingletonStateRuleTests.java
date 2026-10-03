package com.example.coderiskanalyzer.rule.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.concurrency.SharedMutableSingletonStateRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedMutableSingletonStateRuleTests {

    @TempDir
    Path tempDir;

    @Test
    void reportsMutableFieldWrittenByRequestEntryPoint() throws Exception {
        var findings = analyze("@Service class Counter { private int value; @GetMapping int next() { return ++value; } }");
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().ruleId()).isEqualTo("CON-003");
    }

    @Test
    void ignoresFinalAndAtomicFields() throws Exception {
        var findings = analyze("@Component class Counter { private final Object stable = new Object(); "
                + "private AtomicInteger value = new AtomicInteger(); @GetMapping int next() { return value.incrementAndGet(); } }");
        assertThat(findings).isEmpty();
    }

    @Test
    void ignoresReadsShadowedNamesSynchronizedBlocksAndNonSingletonScopes() throws Exception {
        assertThat(analyze("@Service class S { private boolean ready; @GetMapping boolean a() { return !ready; } }")).isEmpty();
        assertThat(analyze("@Service class S { private int total; @GetMapping int a(int total) { total = total + 1; return total; } }")).isEmpty();
        assertThat(analyze("@Service class S { private int n; @GetMapping void a() { synchronized (this) { n++; } } }")).isEmpty();
        assertThat(analyze("@Service @RequestScope class S { private int n; @GetMapping void a() { n++; } }")).isEmpty();
        assertThat(analyze("@Service class S { private int total; @GetMapping void a(int total) { this.total = total; } }")).hasSize(1);
    }

    private List<Finding> analyze(String code) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Counter.java");
        Files.writeString(file, code);
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
        return new SharedMutableSingletonStateRule().analyze(context);
    }
}
