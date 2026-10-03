package com.example.coderiskanalyzer.rule.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.rule.transaction.TransactionalSelfInvocationRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransactionalSelfInvocationRuleTests {

    @TempDir
    Path tempDir;

    @Test
    void reportsInternalCallToTransactionalMethod() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("OrderService.java");
        Files.writeString(file, "class OrderService { void create() { save(); } "
                + "@Transactional void save() {} }");
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);

        var findings = new TransactionalSelfInvocationRule().analyze(context);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().ruleId()).isEqualTo("TX-001");
        assertThat(findings.getFirst().evidence()).hasSize(2);
    }

    @Test
    void ignoresExternalCallsAndUnannotatedMethods() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("OrderService.java");
        Files.writeString(file, "class OrderService { void create() { other.save(); helper(); } "
                + "void save() {} void helper() {} }");
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);

        assertThat(new TransactionalSelfInvocationRule().analyze(context)).isEmpty();
    }
    @Test
    void transactionalCallerJoiningDefaultPropagationCalleeIsNotReportedButNewTransactionIs() throws Exception {
        assertThat(run("class S { @Transactional void a() { b(); } @Transactional void b() {} }")).isEmpty();
        assertThat(run("@Transactional class S { void a() { b(); } @Transactional void b() {} }")).isEmpty();
        assertThat(run("class S { @Transactional void a() { b(); } "
                + "@Transactional(propagation = Propagation.REQUIRES_NEW) void b() {} }")).hasSize(1);
    }

    private List<com.example.coderiskanalyzer.production.finding.Finding> run(String code) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("S.java");
        Files.writeString(file, code);
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        return new TransactionalSelfInvocationRule().analyze(new AnalysisContext(new ProgramModelBuilder().build(sources), sources));
    }

}
