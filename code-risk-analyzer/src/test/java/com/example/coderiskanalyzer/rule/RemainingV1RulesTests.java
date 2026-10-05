package com.example.coderiskanalyzer.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.rule.concurrency.LostUpdateRiskRule;
import com.example.coderiskanalyzer.production.rule.concurrency.UnsafeAsyncStateAccessRule;
import com.example.coderiskanalyzer.production.rule.idempotency.EventHandlerWithoutStableBusinessKeyRule;
import com.example.coderiskanalyzer.production.rule.idempotency.ExternalSideEffectWithoutIdempotencyKeyRule;
import com.example.coderiskanalyzer.production.rule.idempotency.RetryableNonIdempotentOperationRule;
import com.example.coderiskanalyzer.production.rule.reliability.DatabaseExternalSideEffectConsistencyRule;
import com.example.coderiskanalyzer.production.rule.security.HardcodedSecretRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemainingV1RulesTests {

    @TempDir
    Path tempDir;

    @Test
    void hardcodedSecretIsReportedWithoutExposingLiteralAndPlaceholderIsIgnored() throws Exception {
        var findings = new HardcodedSecretRule().analyze(analyze("class Service { "
                + "String apiKey = \"AKIA1234567890ABCDEF\"; String password = \"change_me\"; }"));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("SEC-005");
            assertThat(finding.message()).doesNotContain("AKIA1234567890ABCDEF");
            assertThat(finding.evidence().getFirst().attributes().toString()).doesNotContain("AKIA1234567890ABCDEF");
        });
    }

    @Test
    void hardcodedSecretKeepsRealSecretsContainingTestAndIgnoresUrlsAndReferenceNames() throws Exception {
        var findings = new HardcodedSecretRule().analyze(analyze("class Service { "
                + "String token = \"prod-latest-Zx91kQ72\"; String apiKey = \"Xk29test81Qm45zz\"; "
                + "String tokenUrl = \"https://auth.internal/token\"; String passwordParam = \"password_param_x\"; "
                + "String secret = \"https://user:pw@host/db\"; }"));
        assertThat(findings).extracting(f -> f.evidence().getFirst().attributes().get("identifier"))
                .containsExactlyInAnyOrder("token", "apiKey", "secret");
    }

    @Test
    void lostUpdateRequiresReadModifyPersistAndRespectsVersionProtection() throws Exception {
        var unsafe = analyze("class AccountService { void debit(long id, int amount) { "
                + "Account account = repository.findById(id); account.setBalance(account.getBalance() - amount); "
                + "repository.save(account); } }");
        var protectedByVersion = analyze("class Account { @Version long version; } "
                + "class AccountService { void debit(long id, int amount) { Account account = repository.findById(id); "
                + "account.setBalance(account.getBalance() - amount); repository.save(account); } }");

        var rule = new LostUpdateRiskRule();
        assertThat(rule.analyze(unsafe)).singleElement().satisfies(finding ->
                assertThat(finding.ruleId()).isEqualTo("CON-002"));
        assertThat(rule.analyze(protectedByVersion)).isEmpty();
    }

    @Test
    void stringsCommentsAndUnusedLockAnnotationsCannotSuppressLostUpdates() throws Exception {
        for (String text : List.of("String note = \"FOR UPDATE\";", "String note = \"PESSIMISTIC_WRITE\";",
                "/* PESSIMISTIC_WRITE FOR UPDATE */")) {
            assertThat(new LostUpdateRiskRule().analyze(analyze("class AccountService { "
                    + "@Lock(PESSIMISTIC_WRITE) void debit(long id, int amount) { " + text
                    + " Account account = repository.findById(id); account.setBalance(account.getBalance() - amount); "
                    + "repository.save(account); } }"))).as(text).hasSize(1);
        }
    }

    @Test
    void acceptsPessimisticLockOnTheActualSpringDataReadOnly() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        java.util.Map<String, String> code = java.util.Map.of(
                "org/springframework/data/repository/CrudRepository.java", "package org.springframework.data.repository; "
                        + "public interface CrudRepository<T> { T findById(long id); T save(T value); }",
                "org/springframework/data/jpa/repository/Lock.java", "package org.springframework.data.jpa.repository; "
                        + "public @interface Lock { jakarta.persistence.LockModeType value(); }",
                "jakarta/persistence/LockModeType.java", "package jakarta.persistence; public enum LockModeType { PESSIMISTIC_WRITE }",
                "AccountService.java", "import org.springframework.data.jpa.repository.Lock; import jakarta.persistence.LockModeType; "
                        + "class Account { int getBalance() { return 1; } void setBalance(int value) {} } "
                        + "interface Repo extends org.springframework.data.repository.CrudRepository<Account> { "
                        + "@Lock(LockModeType.PESSIMISTIC_WRITE) Account findById(long id); } "
                        + "class AccountService { Repo repository; void debit(long id, int amount) { "
                        + "Account account = repository.findById(id); account.setBalance(account.getBalance() - amount); repository.save(account); } }");
        List<SourceFile> files = new java.util.ArrayList<>();
        for (var entry : code.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            files.add(new SourceFile(root, file));
        }
        var sources = new JavaSourceParser().parse(files);
        var rule = new LostUpdateRiskRule();
        assertThat(rule.analyze(new AnalysisContext(new ProgramModelBuilder().build(sources), sources))).isEmpty();
        Path service = root.resolve("AccountService.java");
        Files.writeString(service, code.get("AccountService.java").replace("@Lock(LockModeType.PESSIMISTIC_WRITE) Account findById(long id);",
                "Account findById(long id); @Lock(LockModeType.PESSIMISTIC_WRITE) Account findLocked(long id);"));
        sources = new JavaSourceParser().parse(files);
        assertThat(rule.analyze(new AnalysisContext(new ProgramModelBuilder().build(sources), sources))).hasSize(1);
    }

    @Test
    void asyncMethodUsingRequestContextIsReviewed() throws Exception {
        var findings = new UnsafeAsyncStateAccessRule().analyze(analyze(
                "class Service { @Async void send() { RequestContextHolder.getRequestAttributes(); } }"));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("CON-004");
            assertThat(finding.status().name()).isEqualTo("REVIEW");
        });
    }

    @Test
    void retryRulesAndEventKeyRulesReviewUnprotectedSideEffects() throws Exception {
        var retryableExternal = analyze("class Service { @Retryable void pay() { paymentClient.pay(order); } }");
        var retryableDatabase = analyze("class Service { @Retryable void save() { repository.save(order); } }");
        var eventWithoutKey = analyze("class Consumer { @KafkaListener void consume(Event event) { repository.save(event); } }");

        var detector = new SideEffectDetector();
        assertThat(new ExternalSideEffectWithoutIdempotencyKeyRule(detector).analyze(retryableExternal))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("IDEM-002"));
        assertThat(new RetryableNonIdempotentOperationRule(detector).analyze(retryableDatabase))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("IDEM-003"));
        assertThat(new EventHandlerWithoutStableBusinessKeyRule(detector).analyze(eventWithoutKey))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("IDEM-004"));
    }

    @Test
    void listenerIdAttributeIsNotAStableBusinessKey() throws Exception {
        var detector = new SideEffectDetector();
        var withListenerId = analyze("class Consumer { @KafkaListener(id = \"orders\") void consume(Event event) { repository.save(event); } }");
        assertThat(new EventHandlerWithoutStableBusinessKeyRule(detector).analyze(withListenerId)).hasSize(1);
        var withEventId = analyze("class Consumer { @KafkaListener(id = \"orders\") void consume(Event event) { repository.save(event.getId()); } }");
        assertThat(new EventHandlerWithoutStableBusinessKeyRule(detector).analyze(withEventId)).isEmpty();
    }

    @Test
    void unresolvedRepositoryNamedLikeClientIsNotAnExternalCall() throws Exception {
        var context = analyze("class Service { void a() { repository.save(order); clientRepository.findAll(); } }");
        assertThat(new DatabaseExternalSideEffectConsistencyRule(new SideEffectDetector()).analyze(context)).isEmpty();
    }

    @Test
    void databaseAndRemoteMutationInSameMethodCreatesConsistencyReview() throws Exception {
        var context = analyze("class Service { void create() { repository.save(order); paymentClient.pay(order); } }");

        assertThat(new DatabaseExternalSideEffectConsistencyRule(new SideEffectDetector()).analyze(context))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("REL-001");
                    assertThat(finding.status().name()).isEqualTo("REVIEW");
                });
    }

    private AnalysisContext analyze(String code) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Service.java");
        Files.writeString(file, code);
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        return new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
    }
}
