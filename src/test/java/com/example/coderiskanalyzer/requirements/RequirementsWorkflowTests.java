package com.example.coderiskanalyzer.requirements;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.coderiskanalyzer.requirements.RequirementsWorkflow.*;

class RequirementsWorkflowTests {
    private final RequirementsInputValidator validator = new RequirementsInputValidator();
    private final CodeTools tools = mock(CodeTools.class);
    private final RequirementsJudge judge = mock(RequirementsJudge.class);
    private final RequirementsWorkflow workflow = new RequirementsWorkflow(validator, tools, () -> judge);

    @Test
    void acceptsSpecificationWithoutTouchingAnyCodeOrJevTool() throws Exception {
        var result = run(spec(), List.of(Action.DOCUMENT_ANALYSIS), null, Phase.ANALYSIS);
        assertThat(result.path("mode").asText()).isEqualTo("SPEC_ONLY");
        assertThat(result.path("analysis").path("requirements")).hasSize(1);
        verifyNoInteractions(tools, judge);
    }

    @ParameterizedTest
    @EnumSource(value = Action.class, names = {"CODE_COMPARE", "SCAN", "CODE_JEV", "WRITE_TESTS", "IMPLEMENT", "TEST", "BUILD"})
    void blocksEntireSpecificationPlanBeforeEvenAnEarlierAllowedAction(Action forbidden) throws Exception {
        assertThatThrownBy(() -> run(spec(), List.of(Action.DOCUMENT_ANALYSIS, forbidden), scope(Set.of(forbidden)), Phase.ANALYSIS))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tools, judge);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRITE_TESTS", "IMPLEMENT", "TEST", "BUILD"})
    void codeReviewCannotExecuteOrModifyCode(String name) throws Exception {
        Action action = Action.valueOf(name);
        assertThatThrownBy(() -> run(code(), List.of(Action.SCAN, action), scope(Set.of(action, Action.SCAN)), Phase.IMPLEMENTATION))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tools, judge);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "mode", "evidence", "duplicate", "trailing"})
    void rejectsBadInputBeforeInvokingTools(String defect) throws Exception {
        String input = spec();
        ObjectNode node = validator.validate(input);
        switch (defect) {
            case "missing" -> node.remove("requirements");
            case "mode" -> node.put("mode", "GUESS");
            case "evidence" -> ((ObjectNode) node.path("requirements").path("REQ-ORDER-PLACE").path("review"))
                    .putArray("evidence").addObject().put("kind", "CODE").put("path", "source.java")
                    .put("locator", "create").put("observation", "prohibited");
            case "duplicate" -> input = input.replaceFirst("\\\"version\\\": 1", "\"version\": 1, \"version\": 1");
            case "trailing" -> input += " {}";
        }
        if (!defect.equals("duplicate") && !defect.equals("trailing")) input = node.toString();
        final String invalid = input;
        assertThatThrownBy(() -> run(invalid, List.of(Action.DOC_JEV), null, Phase.ANALYSIS))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tools, judge);
    }

    @Test
    void refusesUnapprovedCodeChangeAndProductionWriteDuringTestPhase() throws Exception {
        String input = change();
        assertThatThrownBy(() -> run(input, List.of(Action.BUILD), scope(Set.of()), Phase.VERIFICATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(input, List.of(Action.IMPLEMENT), scope(Set.of(Action.IMPLEMENT)), Phase.TEST_WRITING))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tools, judge);
    }

    @Test
    void invokesApprovedVerificationOnlyAfterPreflight() throws Exception {
        var scope = scope(Set.of(Action.TEST, Action.BUILD));
        when(tools.verify(Action.TEST, scope)).thenReturn(validator.mapper().createObjectNode().put("exitCode", 0));
        when(tools.verify(Action.BUILD, scope)).thenReturn(validator.mapper().createObjectNode().put("exitCode", 0));
        run(change(), List.of(Action.TEST, Action.BUILD), scope, Phase.VERIFICATION);
        var order = inOrder(tools);
        order.verify(tools).preflight(any(), eq(scope), eq(Phase.VERIFICATION), anyList(), anyList());
        order.verify(tools).verify(Action.TEST, scope);
        order.verify(tools).verify(Action.BUILD, scope);
        verifyNoInteractions(judge);
    }

    @Test
    void failedPreflightPreventsDocumentJevAndWrites() throws Exception {
        doThrow(new IllegalArgumentException("outside approved files")).when(tools)
                .preflight(any(), any(), any(), anyList(), anyList());
        assertThatThrownBy(() -> run(change(), List.of(Action.DOC_JEV, Action.IMPLEMENT), scope(Set.of(Action.IMPLEMENT)), Phase.IMPLEMENTATION))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(judge);
        verify(tools, never()).write(any(), any(), anyList());
    }

    @Test
    void writeFailureStopsLaterVerificationAndPreservesPartialFailureResult() throws Exception {
        var scope = scope(Set.of(Action.IMPLEMENT, Action.TEST));
        when(tools.write(any(), any(), anyList())).thenReturn(validator.mapper().createObjectNode()
                .put("status", "FAILED").put("reason", "Partial replacement requires inspection"));
        var result = workflow.execute(new Request(change(), List.of(Action.IMPLEMENT, Action.TEST), scope,
                Phase.IMPLEMENTATION, List.of(new Change("Main.java", "ABSENT", "class Main {}"))));
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        verify(tools, never()).verify(any(), any());
    }

    @Test
    void laterToolExceptionRetainsAppliedFilesAndStopsThePlan() throws Exception {
        var scope = scope(Set.of(Action.IMPLEMENT, Action.TEST));
        var applied = validator.mapper().createObjectNode().put("status", "APPLIED");
        applied.putArray("files").addObject().put("path", "Main.java");
        when(tools.write(any(), any(), anyList())).thenReturn(applied);
        when(tools.compare(any(), any())).thenThrow(new IllegalArgumentException("sensitive runtime details"));
        var result = workflow.execute(new Request(change(), List.of(Action.IMPLEMENT, Action.SCAN, Action.TEST), scope,
                Phase.IMPLEMENTATION, List.of(new Change("Main.java", "ABSENT", "class Main {}"))));
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("executions").get(0).path("files").toString()).contains("Main.java");
        assertThat(result.toString()).doesNotContain("sensitive runtime details");
        verify(tools, never()).verify(any(), any());
    }

    @Test
    void refusesCodeJevUsingEvidenceCollectedBeforeTheLastWrite() throws Exception {
        for (var actions : List.of(List.of(Action.SCAN, Action.IMPLEMENT, Action.CODE_JEV),
                List.of(Action.SCAN, Action.IMPLEMENT, Action.CODE_JEV, Action.CODE_COMPARE))) {
            assertThatThrownBy(() -> workflow.execute(new Request(change(), actions,
                    scope(Set.of(Action.IMPLEMENT)), Phase.IMPLEMENTATION,
                    List.of(new Change("Main.java", "ABSENT", "class Main {}")))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(tools, judge);
    }

    @Test
    void specificationJevReceivesDocumentsAndPlansOnly() throws Exception {
        when(judge.reviewDocuments(any())).thenAnswer(invocation -> validator.mapper().createObjectNode().put("kind", "DOCUMENT"));
        var result = run(spec(), List.of(Action.DOC_JEV), null, Phase.ANALYSIS);
        assertThat(result.path("jevReviews")).hasSize(1);
        verify(judge).reviewDocuments(argThat(payload -> !payload.has("codeRevision")
                && !payload.toString().contains("mappings") && payload.has("requirements")));
        verify(judge, never()).reviewCode(any());
        verifyNoInteractions(tools);
    }

    @Test
    void codeJevUsesASeparatePathAndRequiresStaticEvidenceFirst() throws Exception {
        assertThatThrownBy(() -> run(code(), List.of(Action.CODE_JEV), scope(Set.of()), Phase.ANALYSIS))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tools, judge);
        when(tools.compare(any(), any())).thenReturn(validator.mapper().createObjectNode().put("status", "INSUFFICIENT_EVIDENCE"));
        when(judge.reviewCode(any())).thenReturn(validator.mapper().createObjectNode().put("kind", "CODE"));
        run(code(), List.of(Action.SCAN, Action.CODE_JEV), scope(Set.of()), Phase.ANALYSIS);
        verify(judge).reviewCode(argThat(payload -> payload.has("staticEvidence") && payload.has("codeRevision")));
        verify(judge, never()).reviewDocuments(any());
    }

    @Test
    void jevFailureKeepsInsufficientEvidenceAndDoesNotChangeMode() throws Exception {
        when(judge.reviewDocuments(any())).thenThrow(new RuntimeException("secret must not be echoed"));
        var result = run(spec(), List.of(Action.DOC_JEV), null, Phase.ANALYSIS);
        assertThat(result.path("mode").asText()).isEqualTo("SPEC_ONLY");
        assertThat(result.path("jevReviews").get(0).path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.toString()).doesNotContain("secret must not be echoed");
    }

    private ObjectNode run(String input, List<Action> actions, Scope scope, Phase phase) {
        return workflow.execute(new Request(input, actions, scope, phase, List.of()));
    }
    private Scope scope(Set<Action> approved) { return new Scope(Path.of("."), Set.of(), approved); }
    private String spec() throws Exception { return Files.readString(Path.of("docs/requirements-analysis/order-inventory.analysis.json")); }
    private String code() throws Exception { return Files.readString(Path.of("docs/requirements-analysis/order-inventory.code-review.analysis.json")); }
    private String change() throws Exception { return validator.validate(code()).put("mode", "CODE_CHANGE").toString(); }
}
