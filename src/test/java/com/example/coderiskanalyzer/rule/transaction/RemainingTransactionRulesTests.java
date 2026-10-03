package com.example.coderiskanalyzer.rule.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.rule.transaction.AsyncTransactionBoundaryRule;
import com.example.coderiskanalyzer.production.rule.transaction.MultipleWritesWithoutTransactionRule;
import com.example.coderiskanalyzer.production.rule.transaction.RemoteCallInsideTransactionRule;
import com.example.coderiskanalyzer.production.rule.transaction.SwallowedExceptionInTransactionRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemainingTransactionRulesTests {

    @TempDir
    Path tempDir;

    @Test
    void asyncBoundaryReportsTransactionalCallerButNotDualAnnotation() throws Exception {
        var context = analyze("class Service { @Transactional void process() { send(); } "
                + "@Async void send() {} @Async @Transactional void dual() {} }");

        var findings = new AsyncTransactionBoundaryRule().analyze(context);

        assertThat(findings).hasSize(1).allSatisfy(finding -> {
            assertThat(finding.ruleId()).isEqualTo("TX-002");
            assertThat(finding.status().name()).isEqualTo("REVIEW");
        });
    }

    @Test
    void remoteCallInsideTransactionIsReviewed() throws Exception {
        var context = analyze("class Service { @Transactional void process() { paymentClient.pay(); } "
                + "void read() { paymentClient.get(); } }");

        var findings = new RemoteCallInsideTransactionRule(new SideEffectDetector()).analyze(context);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("TX-003");
            assertThat(finding.status().name()).isEqualTo("REVIEW");
            assertThat(finding.confidence().name()).isEqualTo("HIGH");
        });
    }

    @Test
    void multipleWritesReportWithoutTransactionAndRespectLocalTransactionalCallerChain() throws Exception {
        var unannotated = analyze("class Service { void process() { repository.save(a); repository.save(b); } }");
        var transactionallyCovered = analyze("class Service { @Transactional void process() { write(); } "
                + "void write() { repository.save(a); repository.save(b); } }");
        var crossClassCovered = analyze("class Service { Helper helper; @Transactional void process() { helper.write(); } } "
                + "class Helper { void write() { repository.save(a); repository.save(b); } }");

        assertThat(new MultipleWritesWithoutTransactionRule(new SideEffectDetector()).analyze(unannotated))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("TX-004");
                    assertThat(finding.status().name()).isEqualTo("REVIEW");
                });
        assertThat(new MultipleWritesWithoutTransactionRule(new SideEffectDetector()).analyze(transactionallyCovered)).isEmpty();
        assertThat(new MultipleWritesWithoutTransactionRule(new SideEffectDetector()).analyze(crossClassCovered)).isEmpty();
    }

    @Test
    void swallowedExceptionAfterWriteIsReviewedUnlessRethrownOrRolledBack() throws Exception {
        var swallowed = analyze("class Service { @Transactional void process() { try { repository.save(a); "
                + "dangerous(); } catch (Exception e) { log.error(e); } } }");
        var rethrown = analyze("class Service { @Transactional void process() { try { repository.save(a); "
                + "dangerous(); } catch (Exception e) { throw e; } } }");
        var rollbackOnly = analyze("class Service { @Transactional void process() { try { repository.save(a); "
                + "dangerous(); } catch (Exception e) { tx.setRollbackOnly(); } } }");

        assertThat(new SwallowedExceptionInTransactionRule(new SideEffectDetector()).analyze(swallowed))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("TX-005");
                    assertThat(finding.status().name()).isEqualTo("REVIEW");
                });
        var rule = new SwallowedExceptionInTransactionRule(new SideEffectDetector());
        assertThat(rule.analyze(rethrown)).isEmpty();
        assertThat(rule.analyze(rollbackOnly)).isEmpty();
    }

    private AnalysisContext analyze(String sourceText) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Service.java");
        Files.writeString(file, sourceText);
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        return new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
    }
}
