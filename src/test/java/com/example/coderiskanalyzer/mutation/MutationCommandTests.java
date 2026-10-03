package com.example.coderiskanalyzer.mutation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.source.SourceRootDetector;
import com.example.coderiskanalyzer.testanalysis.TestAdequacyAnalyzer;

class MutationCommandTests {
    @TempDir Path temporary;

    @Test
    void changedProductionSeedSelectsOnlyItsDeclaredClass() throws Exception {
        createProject();
        Files.writeString(temporary.resolve("changed.txt"), "src/main/java/example/Order.java\n");
        CapturingRunner runner = new CapturingRunner();
        var command = command(runner);

        var response = command.verify(temporary.toString(), "changed.txt");

        assertThat(response.exitCode()).isZero();
        assertThat(runner.targets).containsExactly("example.Order");
    }

    @Test
    void testOnlySeedResolvesProductionClassFromCalledMethod() throws Exception {
        createProject();
        Files.writeString(temporary.resolve("changed.txt"), "src/test/java/example/OrderTest.java\n");
        CapturingRunner runner = new CapturingRunner();
        var command = command(runner);

        var response = command.verify(temporary.toString(), "changed.txt");

        assertThat(response.exitCode()).isZero();
        assertThat(runner.targets).containsExactly("example.Order");
    }

    @Test
    void changedSeedTargetsItsGradleSubproject() throws Exception {
        Path production = temporary.resolve("orders/src/main/java/example/Order.java");
        Path tests = temporary.resolve("orders/src/test/java/example/OrderTest.java");
        Files.createDirectories(production.getParent());
        Files.createDirectories(tests.getParent());
        Files.writeString(temporary.resolve("settings.gradle"), "include 'orders'");
        Files.writeString(temporary.resolve("orders/build.gradle"), "plugins { id 'java' }");
        Files.writeString(production, "package example; public class Order { public boolean canOrder(int stock, int amount) { return stock >= amount; } }");
        Files.writeString(tests, "package example; class OrderTest { void checks() { new Order().canOrder(1, 1); } }");
        Files.writeString(temporary.resolve("changed.txt"), "orders/src/main/java/example/Order.java\n");
        CapturingRunner runner = new CapturingRunner();

        var response = command(runner).verify(temporary.toString(), "changed.txt");

        assertThat(response.exitCode()).isZero();
        assertThat(runner.projectTargets).containsEntry(":orders", List.of("example.Order"));
    }

    @Test
    void emptyChangedSeedDoesNotRunWholeProjectMutation() throws Exception {
        createProject();
        Files.writeString(temporary.resolve("changed.txt"), "# no Java files changed\n");
        CapturingRunner runner = new CapturingRunner();

        var response = command(runner).verify(temporary.toString(), "changed.txt");

        assertThat(response.exitCode()).isZero();
        assertThat(response.output()).contains("NO_MUTATION_TARGETS");
        assertThat(runner.called).isFalse();
    }

    @Test
    void survivingMutationGateIsOptInAndReportsReasonedExactExceptions() throws Exception {
        createProject();
        var survivor = new MutationEvidence("example.Order", "canOrder", 1, "CONDITIONALS_BOUNDARY",
                MutationStatus.SURVIVED, "changed conditional boundary");
        var runner = new CapturingRunner();
        runner.mutations = List.of(survivor);
        var command = command(runner);
        assertThat(command.verify(temporary.toString(), "").exitCode()).isZero();
        Files.writeString(temporary.resolve(".code-risk.yml"), "fail:\n  max-survived-mutations: 0\n");
        var failed = command.verify(temporary.toString(), "");
        assertThat(failed.exitCode()).isEqualTo(1);
        assertThat(failed.output()).contains(survivor.fingerprint(), "actionableSurvived");
        Files.writeString(temporary.resolve(".code-risk.yml"), """
                fail:
                  max-survived-mutations: 0
                  mutation-exceptions:
                    - fingerprint: %s
                      reason: Equivalent mutant reviewed in ABC-123
                """.formatted(survivor.fingerprint()));
        var excepted = command.verify(temporary.toString(), "");
        assertThat(excepted.exitCode()).isZero();
        assertThat(excepted.output()).contains("ABC-123", "SURVIVED");
        runner.mutations = List.of(survivor, survivor);
        assertThat(command.verify(temporary.toString(), "").exitCode()).isEqualTo(1);
        runner.mutations = List.of(new MutationEvidence("example.Order", "canOrder", 2,
                survivor.mutator(), MutationStatus.SURVIVED, survivor.description()));
        var changed = command.verify(temporary.toString(), "");
        assertThat(changed.exitCode()).isEqualTo(1);
        assertThat(changed.output()).contains("unmatchedExceptions", survivor.fingerprint());
    }

    @Test
    void mutationThresholdEqualityPassesAndExecutionFailureCannotBeExcepted() throws Exception {
        createProject();
        var runner = new CapturingRunner();
        runner.mutations = List.of(new MutationEvidence("example.Order", "canOrder", 1, "BOUNDARY",
                MutationStatus.SURVIVED, "boundary"), new MutationEvidence("example.Order", "canOrder", 2,
                "BOUNDARY", MutationStatus.NO_COVERAGE, "no coverage"));
        Files.writeString(temporary.resolve(".code-risk.yml"), "fail:\n  max-survived-mutations: 1\n");
        // NO_COVERAGE counts as unverified, so 2 unverified mutations exceed a threshold of 1.
        assertThat(command(runner).verify(temporary.toString(), "").exitCode()).isEqualTo(1);
        Files.writeString(temporary.resolve(".code-risk.yml"), "fail:\n  max-survived-mutations: 2\n");
        assertThat(command(runner).verify(temporary.toString(), "").exitCode()).isZero();
        runner.outcome = MutationExecutionResult.Outcome.MUTATION_EXECUTION_FAILED;
        assertThat(command(runner).verify(temporary.toString(), "").exitCode()).isEqualTo(3);
    }

    @Test
    void invalidMutationPolicyDoesNotExecuteTargetBuild() throws Exception {
        createProject();
        var runner = new CapturingRunner();
        Files.writeString(temporary.resolve(".code-risk.yml"), "fail:\n  max-survived-mutations: -1\n");
        assertThat(command(runner).verify(temporary.toString(), "").exitCode()).isEqualTo(2);
        assertThat(runner.called).isFalse();
    }

    private MutationCommand command(MutationRunner runner) {
        return new MutationCommand(runner, new SourceDiscoveryService(new SourceRootDetector()),
                new JavaSourceParser(), new TestAdequacyAnalyzer(), new MutationObligationMatcher());
    }

    private void createProject() throws Exception {
        Path production = temporary.resolve("src/main/java/example/Order.java");
        Path test = temporary.resolve("src/test/java/example/OrderTest.java");
        Files.createDirectories(production.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(production, "package example; public class Order { public boolean canOrder(int stock, int amount) { return stock >= amount; } }");
        Files.writeString(test, "package example; class OrderTest { void checks() { new Order().canOrder(1, 1); } }");
    }

    private static class CapturingRunner extends MutationRunner {
        private List<String> targets = List.of();
        private Map<String, List<String>> projectTargets = Map.of();
        private boolean called;
        private List<MutationEvidence> mutations = List.of();
        private MutationExecutionResult.Outcome outcome = MutationExecutionResult.Outcome.MUTATION_COMPLETED;
        CapturingRunner() { super(new MutationReportParser()); }
        @Override public MutationExecutionResult run(Path project, Map<String, List<String>> projectTargets) {
            called = true;
            this.projectTargets = projectTargets;
            targets = projectTargets.values().stream().flatMap(List::stream).sorted().toList();
            return new MutationExecutionResult(outcome, mutations, "", 0, null);
        }
    }
}
