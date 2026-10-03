package com.example.coderiskanalyzer.requirements;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequirementsCommandTests {
    @org.junit.jupiter.api.io.TempDir Path project;
    private final RequirementsCommand command = new RequirementsCommand();
    private static final String INPUT = "docs/requirements-analysis/order-inventory.analysis.json";

    @Test
    void documentOnlyCliNeedsNoProjectOrJevAndProducesStructuredResult() {
        var result = command.execute(new String[]{"requirements", INPUT});
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("SPEC_ONLY", "documentChecks", "scenarioGaps");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCAN", "TEST", "BUILD", "IMPLEMENT", "CODE_JEV"})
    void forbiddenCliActionsFailBeforeRequiringAProject(String action) {
        var result = command.execute(new String[]{"requirements", INPUT, "--actions", action});
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--actions=", "--actions=GUESS", "--unknown=true", "--actions=SCAN,SCAN"})
    void rejectsInvalidOptionsAndRepeatedActions(String option) {
        assertThat(command.execute(new String[]{"requirements", INPUT, option}).exitCode()).isEqualTo(2);
    }

    @Test
    void packagedSchemaMatchesTheSingleDocumentedSchema() throws Exception {
        try (var schema = RequirementsInputValidator.class.getResourceAsStream("/requirements/requirements-analysis.schema.json")) {
            assertThat(new String(schema.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo(java.nio.file.Files.readString(Path.of("docs/requirements-analysis/requirements-analysis.schema.json")));
        }
    }

    @Test
    void approvedCliTestChangeIsAppliedButAnUnapprovedLaterBuildBlocksTheEntirePlan() throws Exception {
        var validator = new RequirementsInputValidator();
        Path input = project.resolve("input.json");
        java.nio.file.Files.writeString(input, validator.validate(java.nio.file.Files.readString(
                Path.of("docs/requirements-analysis/order-inventory.code-review.analysis.json"))).put("mode", "CODE_CHANGE").toString());
        var scope = validator.mapper().createObjectNode().put("project", project.toString());
        scope.putArray("files").add("src/test/java/NewTests.java");
        scope.putArray("actions").add("WRITE_TESTS");
        Path scopeFile = project.resolve("scope.json");
        java.nio.file.Files.writeString(scopeFile, scope.toString());
        var changes = validator.mapper().createObjectNode();
        changes.putArray("changes").addObject().put("path", "src/test/java/NewTests.java")
                .put("expectedSha256", "ABSENT").put("content", "class NewTests {}");
        Path plan = project.resolve("changes.json");
        java.nio.file.Files.writeString(plan, changes.toString());
        String[] args = {"requirements", input.toString(), "--scope", scopeFile.toString(), "--phase", "TEST_WRITING",
                "--changes", plan.toString(), "--actions", "WRITE_TESTS,BUILD"};
        assertThat(command.execute(args).exitCode()).isEqualTo(2);
        assertThat(java.nio.file.Files.exists(project.resolve("src/test/java/NewTests.java"))).isFalse();
        args[args.length - 1] = "WRITE_TESTS";
        var result = command.execute(args);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("APPLIED");
        assertThat(java.nio.file.Files.readString(project.resolve("src/test/java/NewTests.java"))).isEqualTo("class NewTests {}");
    }
}
