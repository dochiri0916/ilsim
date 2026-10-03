package com.example.coderiskanalyzer.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptCommandTests {
    @TempDir Path dir;
    private static final String SOURCE = "docs/requirements-analysis/order-inventory.code-review.analysis.json";

    private String input(java.util.function.UnaryOperator<String> edit) throws Exception {
        Path file = dir.resolve("in.json");
        Files.writeString(file, edit.apply(Files.readString(Path.of(SOURCE))).replace("\"SPEC_CODE_REVIEW\"", "\"CODE_CHANGE\""));
        return file.toString();
    }

    @Test
    void generatesCompactPromptWithScopeScenariosAndRequiredChecks() throws Exception {
        var result = new PromptCommand().execute(new String[]{"prompt", input(s -> s), "--rules", "AGENTS.md"});
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("REQ-ORDER-PLACE", "SCN-ROLLBACK", "read and follow AGENTS.md",
                "scan <project> --fail-on", "mutation <project>", "Done when");
    }

    @Test
    void isDeterministic() throws Exception {
        String file = input(s -> s);
        assertThat(new PromptCommand().execute(new String[]{"prompt", file}).output())
                .isEqualTo(new PromptCommand().execute(new String[]{"prompt", file}).output());
    }

    @Test
    void rejectsNonCodeChangeMode() {
        var result = new PromptCommand().execute(new String[]{"prompt", SOURCE});
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("CODE_CHANGE");
    }

    @Test
    void rejectsUnknownPerspectiveAndMissingRequiredScenario() throws Exception {
        var unknown = new PromptCommand().execute(new String[]{"prompt",
                input(s -> s.replaceFirst("\"applicability\"\\s*:\\s*\"APPLICABLE\"", "\"applicability\": \"UNKNOWN\""))});
        assertThat(unknown.output()).satisfiesAnyOf(o -> assertThat(o).contains("UNKNOWN"),
                o -> assertThat(o).contains("JSON Schema"));
        assertThat(unknown.exitCode()).isEqualTo(2);
    }

    @Test
    void rejectsBadArguments() {
        assertThat(new PromptCommand().execute(new String[]{"prompt"}).exitCode()).isEqualTo(2);
        assertThat(new PromptCommand().execute(new String[]{"prompt", SOURCE, "--x", "y"}).exitCode()).isEqualTo(2);
    }

    @Test
    void lightModeQuotesChangeAndScopesToFiles() {
        var result = new PromptCommand().execute(new String[]{"prompt", "--change", "주문 취소 시 재고 복원",
                "--files", "src/main/java/A.java, src/test/java/ATest.java", "--rules", "AGENTS.md"});
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("주문 취소 시 재고 복원", "src/main/java/A.java, src/test/java/ATest.java",
                "read and follow AGENTS.md", "--files-file", "Escalate");
    }

    @Test
    void lightModeRejectsBadInput() {
        for (String[] args : new String[][]{
                {"prompt", "--change", "x"}, {"prompt", "--files", "A.java"},
                {"prompt", "--change", " ", "--files", "A.java"},
                {"prompt", "--change", "x", "--files", "/etc/passwd"},
                {"prompt", "--change", "x", "--files", "../A.java"},
                {"prompt", "--change", "x", "--files", "A.java,A.java"},
                {"prompt", "--change", "x", "--files", "A.java", "--change", "y"},
                {"prompt", "--change", "x".repeat(1001), "--files", "A.java"}}) {
            assertThat(new PromptCommand().execute(args).exitCode()).as(String.join(" ", args)).isEqualTo(2);
        }
    }
}
