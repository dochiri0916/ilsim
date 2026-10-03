package com.example.coderiskanalyzer.testanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestAdequacyAnalyzerTests {
    @TempDir Path temp;

    @Test
    void resolvesTestCallsAndRecognizesExceptionAndWeakTests() throws Exception {
        Path main = temp.resolve("src/main/java");
        Path tests = temp.resolve("src/test/java");
        Files.createDirectories(main.resolve("sample"));
        Files.createDirectories(tests.resolve("sample"));
        Path production = main.resolve("sample/Worker.java");
        Path test = tests.resolve("sample/WorkerTest.java");
        Files.writeString(production, """
                package sample;
                class Worker {
                    int run(int quantity) {
                        if (quantity <= 0) throw new IllegalArgumentException();
                        return quantity;
                    }
                    int run(String value) { return value.length(); }
                    int untested(int value) { return value + 1; }
                }
                """);
        Files.writeString(test, """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class WorkerTest {
                    Worker worker = new Worker();
                    @Test void rejectsZero() { assertThrows(IllegalArgumentException.class, () -> worker.run(0)); }
                    @Test void assertionlessCall() { worker.run(2); }
                    @Test void acceptsPositive() { assertEquals(2, worker.run(2)); }
                }
                """);

        List<SourceFile> files = List.of(new SourceFile(main, production), new SourceFile(tests, test));
        var parsed = new JavaSourceParser().parse(files);
        assertThat(parsed).allMatch(source -> source.compilationUnit() != null);
        List<TestAdequacyResult> results = new TestAdequacyAnalyzer().analyze(parsed, List.of());
        List<String> ids = results.stream().map(result -> result.obligation().id()).toList();
        assertThat(new TestAdequacyAnalyzer().analyze(parsed, List.of()).stream()
                .map(result -> result.obligation().id()).toList()).containsExactlyElementsOf(ids);

        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().type()).isEqualTo("EXCEPTION_PATH");
            assertThat(result.status()).isEqualTo(CoverageStatus.COVERED);
            assertThat(result.matchingTests()).contains("sample.WorkerTest#rejectsZero");
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().condition()).contains("condition true");
            assertThat(result.status()).isEqualTo(CoverageStatus.PARTIAL);
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().type()).isEqualTo("BOUNDARY");
            assertThat(result.coveredCases()).contains("equal to", "greater than");
            assertThat(result.missingCases()).contains("less than");
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().method()).isEqualTo("untested");
            assertThat(result.status()).isEqualTo(CoverageStatus.MISSING);
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().method()).isEqualTo("run");
            assertThat(result.obligation().location().startLine()).isEqualTo(7);
            assertThat(result.matchingTests()).isEmpty();
            assertThat(result.status()).isEqualTo(CoverageStatus.MISSING);
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.matchingTests()).contains("sample.WorkerTest#assertionlessCall");
            assertThat(result.status()).isEqualTo(CoverageStatus.MISSING);
            assertThat(result.reason()).contains("no recognized assertion");
        });
    }

    @Test
    void linksRiskFindingsToTheContainingProductionMethod() throws Exception {
        Path main = temp.resolve("src/main/java");
        Files.createDirectories(main.resolve("sample"));
        Path production = main.resolve("sample/Worker.java");
        Files.writeString(production, "package sample; class Worker { void write() { repository.save(); } Repository repository; }");
        var parsed = new JavaSourceParser().parse(List.of(new SourceFile(main, production)));
        var location = new com.example.coderiskanalyzer.production.model.SourceLocation(production, 1, 50, 1, 50);
        var risk = new com.example.coderiskanalyzer.production.finding.Finding("CON-001",
                com.example.coderiskanalyzer.production.finding.RiskCategory.CONCURRENCY,
                com.example.coderiskanalyzer.production.finding.Severity.HIGH,
                com.example.coderiskanalyzer.production.finding.Confidence.HIGH, "Lost update", "risk", location,
                List.of(new com.example.coderiskanalyzer.production.finding.Evidence("RISK", "risk", location, Map.of())),
                com.example.coderiskanalyzer.production.finding.FindingStatus.CONFIRMED);

        List<TestAdequacyResult> results = new TestAdequacyAnalyzer().analyze(parsed, List.of(risk));
        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().type()).isEqualTo("RISK");
            assertThat(result.obligation().riskId()).isEqualTo("CON-001");
            assertThat(result.status()).isEqualTo(CoverageStatus.MISSING);
        });
    }

    @Test
    void recognizesValidationStateAssertionsAndMockitoPersistenceVerification() throws Exception {
        Path main = temp.resolve("src/main/java");
        Path tests = temp.resolve("src/test/java");
        Files.createDirectories(main.resolve("sample"));
        Files.createDirectories(tests.resolve("sample"));
        Path production = main.resolve("sample/AccountService.java");
        Path test = tests.resolve("sample/AccountServiceTest.java");
        Files.writeString(production, """
                package sample;
                import jakarta.validation.constraints.Positive;
                class Account { int status; }
                class AccountService {
                    Repository repository;
                    Account account;
                    void cancel(@Positive int reason) { account.status = 1; repository.save(account); }
                }
                interface Repository { Account save(Account account); }
                """);
        Files.writeString(test, """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.mockito.Mockito.verify;
                class AccountServiceTest {
                    AccountService service = new AccountService();
                    @Test void cancel() {
                        service.cancel(1);
                        assertThat(service.account.status).isEqualTo(1);
                        verify(service.repository).save(service.account);
                    }
                }
                """);

        var parsed = new JavaSourceParser().parse(List.of(new SourceFile(main, production), new SourceFile(tests, test)));
        List<TestAdequacyResult> results = new TestAdequacyAnalyzer().analyze(parsed, List.of());
        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().type()).isEqualTo("VALIDATION");
            assertThat(result.obligation().condition()).contains("Positive");
            assertThat(result.status()).isEqualTo(CoverageStatus.PARTIAL);
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().type()).isEqualTo("PERSISTENCE");
            assertThat(result.status()).isEqualTo(CoverageStatus.COVERED);
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.obligation().type()).isEqualTo("STATE_CHANGE");
            assertThat(result.status()).isEqualTo(CoverageStatus.PARTIAL);
        });
    }

    @Test
    void reportsUnknownWhenAnOverloadedTestCallCannotBeResolved() throws Exception {
        Path main = temp.resolve("src/main/java/sample");
        Path tests = temp.resolve("src/test/java/sample");
        Files.createDirectories(main);
        Files.createDirectories(tests);
        Path production = main.resolve("Worker.java");
        Path test = tests.resolve("WorkerTest.java");
        Files.writeString(production, """
                package sample;
                class Worker {
                    int run(String value) { return 1; }
                    int run(Integer value) { return 2; }
                }
                """);
        Files.writeString(test, """
                package sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class WorkerTest {
                    Worker worker = new Worker();
                    @Test void ambiguousNull() { assertEquals(1, worker.run(null)); }
                }
                """);

        var parsed = new JavaSourceParser().parse(List.of(new SourceFile(main, production), new SourceFile(tests, test)));
        List<TestAdequacyResult> results = new TestAdequacyAnalyzer().analyze(parsed, List.of());

        assertThat(results).filteredOn(result -> result.obligation().method().equals("run"))
                .isNotEmpty().allSatisfy(result -> assertThat(result.status()).isEqualTo(CoverageStatus.UNKNOWN));
    }

    @Test
    void extractsSaveAndFlushRollbackWithoutRequiringRollbackForEveryFlush() throws Exception {
        Path main = temp.resolve("src/main/java");
        Files.createDirectories(main.resolve("sample"));
        Path production = main.resolve("sample/FlushService.java");
        Files.writeString(production, """
                package sample;
                @interface Transactional {}
                class Repository {
                    void saveAndFlush(Object value) {}
                    void flush() {}
                }
                class FlushService {
                    Repository repository = new Repository();
                    @Transactional void writeThenFail() {
                        repository.saveAndFlush(new Object());
                        throw new IllegalStateException();
                    }
                    @Transactional void multipleWrites() {
                        repository.saveAndFlush(new Object());
                        repository.saveAndFlush(new Object());
                    }
                    @Transactional void singleWrite() {
                        repository.saveAndFlush(new Object());
                    }
                    void nonTransactionalWriteThenFail() {
                        repository.saveAndFlush(new Object());
                        throw new IllegalStateException();
                    }
                    @Transactional void flushThenFail() {
                        repository.flush();
                        throw new IllegalStateException();
                    }
                }
                """);
        var parsed = new JavaSourceParser().parse(List.of(new SourceFile(main, production)));
        assertThat(parsed).allMatch(source -> source.compilationUnit() != null);
        var results = new TestAdequacyAnalyzer().analyze(parsed, List.of());

        assertThat(results).filteredOn(result -> result.obligation().type().equals("TRANSACTION_ROLLBACK"))
                .extracting(result -> result.obligation().method())
                .containsExactlyInAnyOrder("writeThenFail", "multipleWrites");
        assertThat(results).filteredOn(result -> result.obligation().type().equals("PERSISTENCE"))
                .extracting(result -> result.obligation().method())
                .containsExactlyInAnyOrder("writeThenFail", "multipleWrites", "multipleWrites",
                        "singleWrite", "nonTransactionalWriteThenFail");
        assertThat(results).filteredOn(result -> result.obligation().type().equals("TRANSACTION_ROLLBACK"))
                .allSatisfy(result -> {
                    assertThat(result.status()).isEqualTo(CoverageStatus.MISSING);
                    assertThat(result.obligation().requiredEvidence()).isEqualTo("assert state rollback after failure");
                });
    }

    @Test
    void assignsDistinctIdsToRepeatedObligationsWithoutChangingUniqueIds() throws Exception {
        Path main = temp.resolve("src/main/java/sample");
        Files.createDirectories(main);
        Path production = main.resolve("Handler.java");
        String single = """
                    int single(Integer value) {
                        if (value == null) throw new IllegalArgumentException();
                        return value;
                    }
                """;
        Files.writeString(production, "package sample;\nclass Handler {\n" + single + "}\n");
        var parsed = new JavaSourceParser().parse(List.of(new SourceFile(temp.resolve("src/main/java"), production)));
        Map<String, String> before = new java.util.HashMap<>();
        new TestAdequacyAnalyzer().analyze(parsed, List.of()).forEach(result ->
                before.put(result.obligation().type() + "|" + result.obligation().condition(), result.obligation().id()));

        Files.writeString(production, "package sample;\nclass Handler {\n" + single + """
                    int repeated(Integer first, Integer second) {
                        if (first == null) throw new IllegalArgumentException();
                        if (first == null) throw new IllegalArgumentException();
                        return first + second;
                    }
                }
                """);
        parsed = new JavaSourceParser().parse(List.of(new SourceFile(temp.resolve("src/main/java"), production)));
        List<TestAdequacyResult> results = new TestAdequacyAnalyzer().analyze(parsed, List.of());

        assertThat(results).extracting(result -> result.obligation().id()).doesNotHaveDuplicates();
        assertThat(results).filteredOn(result -> result.obligation().method().equals("repeated")
                        && result.obligation().type().equals("EXCEPTION_PATH")).hasSize(2);
        assertThat(results).filteredOn(result -> result.obligation().method().equals("single"))
                .hasSize(before.size()).allSatisfy(result -> assertThat(result.obligation().id())
                        .isEqualTo(before.get(result.obligation().type() + "|" + result.obligation().condition())));
        assertThat(new TestAdequacyAnalyzer().analyze(parsed, List.of()))
                .extracting(result -> result.obligation().id())
                .containsExactlyElementsOf(results.stream().map(result -> result.obligation().id()).toList());
    }

}
