package com.example.coderiskanalyzer.rule.concurrency.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.rule.RuleRegistry;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.example.coderiskanalyzer.production.rule.concurrency.CheckThenActRaceRule;
import com.example.coderiskanalyzer.production.rule.concurrency.LostUpdateRiskRule;
import com.example.coderiskanalyzer.production.rule.concurrency.SharedMutableSingletonStateRule;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyOutcome;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencySpecCatalog;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ImplementationStatus;
import com.example.coderiskanalyzer.production.rule.transaction.TransactionalSelfInvocationRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ConcurrencySpecCatalogTests {
    @Autowired RuleRegistry registry;
    @Autowired ConcurrencySpecCatalog catalog;
    @Autowired List<RiskRule> rules;
    @TempDir Path tempDir;

    @Test
    void accountsForEverySpecificationWithoutClaimingExistingFixturesValidateRuntimeConcurrency() {
        assertThat(catalog.all()).hasSize(22);
        assertThat(catalog.all()).extracting(ConcurrencySpecCatalog.Requirement::id).doesNotHaveDuplicates();
        assertThat(catalog.progress().counts()).containsEntry("PARTIAL", 10).containsEntry("PLANNED", 12)
                .containsEntry("IMPLEMENTED", 0).containsEntry("VALIDATED", 0);
        for (var requirement : catalog.all()) {
            assertThat(requirement.remainingWork()).as(requirement.id()).isNotEmpty();
            if (requirement.implementationStatus() == ImplementationStatus.PLANNED) {
                assertThat(requirement.bindings()).as(requirement.id()).isEmpty();
                continue;
            }
            for (var binding : requirement.bindings()) {
                assertThat(registry.find(binding.ruleId())).as(requirement.id()).isNotNull();
                var implementation = rules.stream().filter(rule -> rule.id().equals(binding.ruleId())).findFirst().orElseThrow();
                String source = "src/main/java/" + implementation.getClass().getName().replace('.', '/') + ".java";
                assertThat(requirement.implementationEvidence()).as(requirement.id()).contains(source);
            }
            for (String path : requirement.implementationEvidence()) {
                assertThat(Path.of(path)).as(requirement.id()).isRegularFile();
                String className = path.substring("src/main/java/".length(), path.length() - ".java".length())
                        .replace('/', '.');
                assertThat(className).startsWith("com.example.coderiskanalyzer.production.rule.");
            }
            for (String path : requirement.testEvidence()) assertThat(Path.of(path)).as(requirement.id()).isRegularFile();
        }
        assertThat(registry.all()).hasSize(22);
    }

    @Test
    void mapsLostUpdateAndVersionProtectionToOneActualFinding() throws Exception {
        var findings = new LostUpdateRiskRule().analyze(context("""
                class Account { int getBalance() { return 0; } void setBalance(int value) {} }
                interface Repo { Account findById(long id); void save(Account account); }
                class Service { Repo repository; void debit(long id) {
                    Account account = repository.findById(id);
                    account.setBalance(account.getBalance() - 1);
                    repository.save(account);
                } }
                """));
        assertThat(findings).hasSize(1);
        var mapping = catalog.forFinding(findings.getFirst());
        assertThat(mapping.specificationRuleIds()).containsExactly("DB-CONC-009", "JPA-CONC-018");
        assertThat(mapping.primarySpecificationRuleId()).isEqualTo("DB-CONC-009");
        assertThat(mapping.outcome()).isEqualTo(ConcurrencyOutcome.POTENTIAL_RISK);
        assertThat(mapping.implementationStatus()).isEqualTo(ImplementationStatus.PARTIAL);
    }

    @Test
    void distributedLockMappingRequiresActualJvmLockEvidence() throws Exception {
        String method = "void create(String key) { if (!repo.existsByKey(key)) { repo.save(key); } }";
        var rule = new CheckThenActRaceRule();
        var unguarded = rule.analyze(context("class Service { " + method + " }"));
        var guarded = rule.analyze(context("class Service { synchronized " + method + " }"));
        assertThat(unguarded).hasSize(1);
        assertThat(guarded).hasSize(1);
        assertThat(catalog.forFinding(unguarded.getFirst()).specificationRuleIds()).containsExactly("DB-CONC-010");
        assertThat(catalog.forFinding(guarded.getFirst()).specificationRuleIds())
                .containsExactly("DB-CONC-010", "DIST-CONC-020");
    }

    @Test
    void outcomesAreEvidenceSpecificRatherThanBlindConversionsOfConfirmedStatus() throws Exception {
        var transactional = new TransactionalSelfInvocationRule().analyze(context(
                "class Service { void a() { b(); } @Transactional void b() {} }"));
        var shared = new SharedMutableSingletonStateRule().analyze(context(
                "@Service class Counter { int count; @GetMapping void increment() { count++; } }"));
        assertThat(transactional.getFirst().status().name()).isEqualTo("CONFIRMED");
        assertThat(shared.getFirst().status().name()).isEqualTo("CONFIRMED");
        assertThat(catalog.forFinding(transactional.getFirst()).outcome()).isEqualTo(ConcurrencyOutcome.POTENTIAL_RISK);
        assertThat(catalog.forFinding(shared.getFirst()).outcome()).isEqualTo(ConcurrencyOutcome.DEFINITE_PATTERN);
        assertThat(catalog.forAnalyzerRule("CON-004")).isEmpty();
        assertThat(catalog.forAnalyzerRule("SEC-001")).isEmpty();
    }

    private AnalysisContext context(String code) throws Exception {
        Path file = tempDir.resolve("Service.java");
        Files.writeString(file, code);
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(tempDir, file)));
        return new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
    }
}
