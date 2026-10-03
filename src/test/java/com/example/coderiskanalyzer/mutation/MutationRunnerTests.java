package com.example.coderiskanalyzer.mutation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MutationRunnerTests {
    @TempDir Path temporary;

    @Test
    void parsesReportFromProcessAndUsesArgumentListWithoutShell() throws Exception {
        Path project = gradleProject();
        GradleProcessExecutor fake = (command, cwd, timeout, stdout, stderr) -> {
            assertThat(command).contains("--init-script", "codeRiskMutation");
            assertThat(command.get(0)).isEqualTo(project.resolve("gradlew").toString());
            assertThat(command.stream().noneMatch(arg -> arg.equals("sh") || arg.equals("-c"))).isTrue();
            String report = command.stream().filter(arg -> arg.startsWith("-DcodeRisk.reportDir="))
                    .findFirst().orElseThrow().substring("-DcodeRisk.reportDir=".length());
            Path xml = Files.createDirectories(Path.of(report)).resolve("mutations.xml");
            Files.writeString(xml, "<mutations><mutation><mutatedClass>example.Order</mutatedClass><mutatedMethod>canOrder</mutatedMethod><lineNumber>4</lineNumber><mutator>CONDITIONALS_BOUNDARY</mutator><description>boundary</description><status>SURVIVED</status></mutation></mutations>");
            return new GradleProcessExecutor.Result(0, false);
        };

        var result = new MutationRunner(new MutationReportParser(), fake).run(project, List.of("example.Order"));

        assertThat(result.outcome()).isEqualTo(MutationExecutionResult.Outcome.MUTATION_COMPLETED);
        assertThat(result.mutations()).extracting(MutationEvidence::status).containsExactly(MutationStatus.SURVIVED);
    }

    @Test
    void timeoutAndBuildFailureAreReportedSeparately() throws Exception {
        Path project = gradleProject();
        var timeout = new MutationRunner(new MutationReportParser(), (command, cwd, limit, out, err) ->
                new GradleProcessExecutor.Result(-1, true)).run(project, List.of());
        assertThat(timeout.outcome()).isEqualTo(MutationExecutionResult.Outcome.TIMED_OUT);

        var buildFailure = new MutationRunner(new MutationReportParser(), (command, cwd, limit, out, err) -> {
            Files.writeString(err, "Execution failed for task ':compileJava'.");
            return new GradleProcessExecutor.Result(1, false);
        }).run(project, List.of());
        assertThat(buildFailure.outcome()).isEqualTo(MutationExecutionResult.Outcome.BUILD_FAILED);
        assertThat(buildFailure.diagnostic()).doesNotContain("compileJava");

        var testFailure = new MutationRunner(new MutationReportParser(), (command, cwd, limit, out, err) -> {
            Files.writeString(err, "PIT needs a green suite before mutation analysis.");
            return new GradleProcessExecutor.Result(1, false);
        }).run(project, List.of());
        assertThat(testFailure.outcome()).isEqualTo(MutationExecutionResult.Outcome.TESTS_FAILED_BEFORE_MUTATION);
    }

    private Path gradleProject() throws Exception {
        Path project = temporary.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("gradlew"), "placeholder");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }");
        return project;
    }
}
