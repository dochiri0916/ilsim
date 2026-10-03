package com.example.coderiskanalyzer.requirements;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.example.coderiskanalyzer.requirements.RequirementsWorkflow.*;

class LocalRequirementsCodeToolsTests {
    @TempDir Path project;
    private final RequirementsInputValidator validator = new RequirementsInputValidator();
    private final LocalRequirementsCodeTools tools = new LocalRequirementsCodeTools();

    @Test
    void connectsRulesOnlyToTheMappedMethodAndRetainsInsufficientEvidence() throws Exception {
        Files.writeString(project.resolve("Service.java"), """
                class Service {
                  Repo repo;
                  void create(String key) { if (!repo.existsByKey(key)) repo.save(key); }
                  void entry() { save(); }
                  @Transactional void save() { repo.save("key"); }
                  @KafkaListener void handle(String message) { repo.save(message); }
                  void unrelated() { repo.save("other"); }
                }
                """);
        var input = input("Service.java", "Service#create(String)");
        var scope = new Scope(project, Set.of("Service.java"), Set.of());
        tools.preflight(input, scope, Phase.ANALYSIS, List.of(Action.SCAN), List.of());
        var result = tools.compare(input, scope);
        var req = result.path("requirements").path("REQ-ORDER-PLACE");
        assertThat(req.path("concurrency").path("findings").toString()).contains("CON-001");
        assertThat(req.path("transaction").path("findings")).isEmpty();
        assertThat(req.path("idempotency").path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(req.path("concurrency").path("status").asText()).isEqualTo("REVIEW_REQUIRED");

        input = input("Service.java", "Service#entry()");
        assertThat(tools.compare(input, scope).path("requirements").path("REQ-ORDER-PLACE")
                .path("transaction").path("findings").toString()).contains("TX-001");
        input = input("Service.java", "Service#handle(String)");
        assertThat(tools.compare(input, scope).path("requirements").path("REQ-ORDER-PLACE")
                .path("idempotency").path("findings").toString()).contains("IDEM-001");
    }

    @Test
    void unresolvedAndOverloadedSymbolsAreNotGuessedOrTreatedAsSafe() throws Exception {
        Files.writeString(project.resolve("Service.java"), "class Service { void go() {} void go(String value) {} }");
        var scope = new Scope(project, Set.of("Service.java"), Set.of());
        for (String symbol : List.of("Service#go", "Service#absent()")) {
            var result = tools.compare(input("Service.java", symbol), scope);
            var req = result.path("requirements").path("REQ-ORDER-PLACE");
            assertThat(req.path("concurrency").path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
            assertThat(req.path("unresolvedMappings")).hasSize(1);
        }
    }

    @Test
    void rejectsTraversalSymlinkAndUnapprovedFilesBeforeAnyReadOrWrite() throws Exception {
        Path external = Files.createTempFile("requirements-external-", ".java");
        try {
            Files.createSymbolicLink(project.resolve("Linked.java"), external);
            for (String path : List.of("../escape.java", "Linked.java", "NotApproved.java")) {
                var scope = new Scope(project, Set.of("../escape.java", "Linked.java"), Set.of());
                assertThatThrownBy(() -> tools.preflight(input(path, "Service#go()"), scope,
                        Phase.ANALYSIS, List.of(Action.SCAN), List.of())).isInstanceOf(IllegalArgumentException.class);
            }
        } finally { Files.deleteIfExists(external); }
    }

    @Test
    void testWritingPhaseCannotWriteProductionEvenWhenFileIsApproved() throws Exception {
        var scope = new Scope(project, Set.of("src/main/java/New.java"), Set.of(Action.WRITE_TESTS));
        var changes = List.of(new Change("src/main/java/New.java", "ABSENT", "class New {}"));
        assertThatThrownBy(() -> tools.preflight(inputWithoutMapping(), scope, Phase.TEST_WRITING,
                List.of(Action.WRITE_TESTS), changes)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(project.resolve("src/main/java/New.java"))).isFalse();
    }

    @Test
    void digestMismatchBlocksAllChangesAndApprovedNewTestIsWritten() throws Exception {
        var scope = new Scope(project, Set.of("src/test/java/NewTests.java", "src/test/java/ExistingTests.java"), Set.of(Action.WRITE_TESTS));
        Files.createDirectories(project.resolve("src/test/java"));
        Files.writeString(project.resolve("src/test/java/ExistingTests.java"), "original");
        var changes = List.of(new Change("src/test/java/NewTests.java", "ABSENT", "class NewTests {}"),
                new Change("src/test/java/ExistingTests.java", "wrong", "replacement"));
        assertThatThrownBy(() -> tools.preflight(inputWithoutMapping(), scope, Phase.TEST_WRITING,
                List.of(Action.WRITE_TESTS), changes)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(project.resolve("src/test/java/NewTests.java"))).isFalse();
        var one = changes.subList(0, 1);
        tools.preflight(inputWithoutMapping(), scope, Phase.TEST_WRITING, List.of(Action.WRITE_TESTS), one);
        tools.write(Action.WRITE_TESTS, scope, one);
        assertThat(Files.readString(project.resolve("src/test/java/NewTests.java"))).isEqualTo("class NewTests {}");
        assertThatThrownBy(() -> tools.write(Action.WRITE_TESTS, scope, one)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(project.resolve("src/test/java/NewTests.java"))).isEqualTo("class NewTests {}");
    }

    @Test
    void concurrentApprovedReplacementsCannotBothUseTheSameOriginalDigest() throws Exception {
        var scope = new Scope(project, Set.of("src/test/java/NewTests.java"), Set.of(Action.WRITE_TESTS));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> attempt = () -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    return tools.write(Action.WRITE_TESTS, scope,
                            List.of(new Change("src/test/java/NewTests.java", "ABSENT", "class NewTests {}"))).path("status").asText();
                } catch (IllegalArgumentException exception) { return "REJECTED"; }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsOnlyOnce("APPLIED");
        }
    }

    @Test
    void gradleVerificationPropagatesFailureAndUsesFixedTask() throws Exception {
        Files.writeString(project.resolve("gradlew"), "#!/bin/sh\nprintf '%s\\n' \"$@\" > executed-args\nexit 7\n");
        project.resolve("gradlew").toFile().setExecutable(true);
        var scope = new Scope(project, Set.of(), Set.of(Action.TEST));
        var result = tools.verify(Action.TEST, scope);
        assertThat(result.path("exitCode").asInt()).isEqualTo(7);
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(Files.readString(project.resolve("executed-args"))).isEqualTo("test\n--no-daemon\n--rerun-tasks\n");
    }

    private ObjectNode input(String path, String symbol) throws Exception {
        var input = inputWithoutMapping();
        ((ObjectNode) input.path("requirements").path("REQ-ORDER-PLACE").path("mappings"))
                .putArray("code").addObject().put("path", path).put("symbol", symbol);
        return input;
    }
    private ObjectNode inputWithoutMapping() throws Exception {
        return validator.validate(Files.readString(Path.of("docs/requirements-analysis/order-inventory.code-review.analysis.json")));
    }
}
