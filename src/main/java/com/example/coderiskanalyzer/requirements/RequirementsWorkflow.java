package com.example.coderiskanalyzer.requirements;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Validates the complete plan before touching project files or constructing a JEV client. */
public final class RequirementsWorkflow {
    public enum Mode { SPEC_ONLY, SPEC_CODE_REVIEW, CODE_CHANGE }
    public enum Action { DOCUMENT_ANALYSIS, DOC_JEV, CODE_COMPARE, SCAN, CODE_JEV, WRITE_TESTS, IMPLEMENT, TEST, BUILD }
    public enum Phase { ANALYSIS, TEST_WRITING, IMPLEMENTATION, VERIFICATION }
    public record Scope(Path project, Set<String> approvedFiles, Set<Action> approvedActions) {
        public Scope { approvedFiles = Set.copyOf(approvedFiles); approvedActions = Set.copyOf(approvedActions); }
    }
    public record Change(String path, String expectedSha256, String content) {}
    public record Request(String json, List<Action> actions, Scope scope, Phase phase, List<Change> changes) {
        public Request { actions = List.copyOf(actions); changes = List.copyOf(changes); }
    }
    public interface CodeTools {
        void preflight(ObjectNode input, Scope scope, Phase phase, List<Action> actions, List<Change> changes);
        ObjectNode compare(ObjectNode input, Scope scope);
        ObjectNode write(Action action, Scope scope, List<Change> changes);
        ObjectNode verify(Action action, Scope scope);
    }

    private static final Set<Action> DOCUMENT = Set.of(Action.DOCUMENT_ANALYSIS, Action.DOC_JEV);
    private static final Set<Action> REVIEW = Set.of(Action.DOCUMENT_ANALYSIS, Action.DOC_JEV, Action.CODE_COMPARE, Action.SCAN, Action.CODE_JEV);
    private static final Set<Action> MUTATING = Set.of(Action.WRITE_TESTS, Action.IMPLEMENT, Action.TEST, Action.BUILD);
    private final RequirementsInputValidator validator;
    private final CodeTools tools;
    private final Supplier<RequirementsJudge> judges;

    public RequirementsWorkflow(RequirementsInputValidator validator, CodeTools tools, Supplier<RequirementsJudge> judges) {
        this.validator = validator; this.tools = tools; this.judges = judges;
    }

    public ObjectNode execute(Request request) {
        ObjectNode input = validator.validate(request.json());
        Mode mode = Mode.valueOf(input.path("mode").asText());
        validatePlan(mode, request);
        boolean hasCode = request.actions().stream().anyMatch(action -> !DOCUMENT.contains(action));
        if (hasCode) tools.preflight(input.deepCopy(), request.scope(), request.phase(), request.actions(), request.changes());

        ObjectNode result = validator.mapper().createObjectNode();
        result.put("mode", mode.name()).put("status", "COMPLETED");
        result.set("analysis", input);
        var executions = result.putArray("executions");
        var reviews = result.putArray("jevReviews");
        ObjectNode staticEvidence = null;
        for (Action action : request.actions()) {
            try {
                switch (action) {
                    case DOCUMENT_ANALYSIS -> result.set("documentChecks", documentChecks(input));
                    case CODE_COMPARE, SCAN -> {
                        if (staticEvidence == null) staticEvidence = tools.compare(input.deepCopy(), request.scope());
                        result.set("staticEvidence", staticEvidence);
                    }
                    case DOC_JEV, CODE_JEV -> {
                        ObjectNode payload = documentPayload(input);
                        if (action == Action.CODE_JEV) {
                            payload.put("codeRevision", input.path("codeRevision").asText());
                            payload.set("staticEvidence", staticEvidence.deepCopy());
                        }
                        try (RequirementsJudge judge = judges.get()) {
                            reviews.add(action == Action.DOC_JEV ? judge.reviewDocuments(payload) : judge.reviewCode(payload));
                    } catch (RuntimeException exception) {
                        reviews.addObject().put("kind", action.name()).put("status", "INSUFFICIENT_EVIDENCE")
                                .put("reason", "JEV unavailable or failed; inspect original evidence and retry explicitly");
                        result.put("status", "NEEDS_REVIEW");
                    }
                }
                case WRITE_TESTS, IMPLEMENT -> {
                    ObjectNode execution = tools.write(action, request.scope(), request.changes());
                    executions.add(execution);
                    if (!execution.path("status").asText().equals("APPLIED")) {
                        result.put("status", "FAILED");
                        return result;
                    }
                    staticEvidence = null;
                    result.remove("staticEvidence");
                }
                case TEST, BUILD -> {
                    ObjectNode execution = tools.verify(action, request.scope());
                    executions.add(execution);
                    if (!execution.path("status").asText().equals("PASSED") && execution.path("exitCode").asInt(-1) != 0) {
                        result.put("status", "FAILED");
                        return result; // A failed verification never permits later changes.
                    }
                }
            }
            } catch (RuntimeException exception) {
                result.put("status", "FAILED");
                executions.addObject().put("action", action.name()).put("status", "FAILED")
                        .put("reason", "Tool failed; inspect recorded applied files before retrying");
                return result;
            }
        }
        return result;
    }

    private void validatePlan(Mode mode, Request request) {
        if (request.phase() == null || request.actions().isEmpty()
                || Set.copyOf(request.actions()).size() != request.actions().size())
            throw new IllegalArgumentException("Explicit phase and distinct, nonempty actions are required");
        for (Action action : request.actions()) {
            if (mode == Mode.SPEC_ONLY && !DOCUMENT.contains(action)
                    || mode == Mode.SPEC_CODE_REVIEW && !REVIEW.contains(action))
                throw new IllegalArgumentException("Action " + action + " is forbidden in " + mode);
            if (!DOCUMENT.contains(action) && request.scope() == null)
                throw new IllegalArgumentException("Code actions require an explicit project and file scope");
            if (MUTATING.contains(action) && !request.scope().approvedActions().contains(action))
                throw new IllegalArgumentException("Action " + action + " is outside the approved scope");
            if (action == Action.IMPLEMENT && request.phase() != Phase.IMPLEMENTATION
                    || action == Action.WRITE_TESTS && request.phase() != Phase.TEST_WRITING)
                throw new IllegalArgumentException("Writes are forbidden in the requested phase");
            if (action == Action.CODE_JEV) {
                int index = request.actions().indexOf(action);
                List<Action> preceding = request.actions().subList(0, index);
                if (preceding.stream().noneMatch(a -> a == Action.SCAN || a == Action.CODE_COMPARE))
                    throw new IllegalArgumentException("Code JEV requires preceding static comparison");
                int lastComparison = Math.max(preceding.indexOf(Action.SCAN), preceding.indexOf(Action.CODE_COMPARE));
                int lastWrite = Math.max(preceding.indexOf(Action.IMPLEMENT), preceding.indexOf(Action.WRITE_TESTS));
                if (lastWrite >= 0 && lastComparison <= lastWrite)
                    throw new IllegalArgumentException("Code JEV requires fresh static comparison after changes");
            }
        }
        boolean writes = request.actions().contains(Action.IMPLEMENT) || request.actions().contains(Action.WRITE_TESTS);
        if (writes != !request.changes().isEmpty()) throw new IllegalArgumentException("Writes require an explicit change plan; unused changes are forbidden");
        if (mode == Mode.SPEC_ONLY && request.scope() != null)
            throw new IllegalArgumentException("SPEC_ONLY must not supply a code execution scope");
    }

    private ObjectNode documentPayload(ObjectNode input) {
        ObjectNode payload = validator.mapper().createObjectNode();
        ObjectNode requirements = payload.putObject("requirements");
        input.path("requirements").properties().forEach(entry -> {
            ObjectNode target = requirements.putObject(entry.getKey());
            for (String field : List.of("title", "source", "domain", "preconditions", "postconditions", "invariants", "coreLogic",
                    "transaction", "concurrency", "idempotency", "consistency", "scenarios"))
                target.set(field, entry.getValue().path(field).deepCopy());
            var documents = target.putArray("documentEvidence");
            entry.getValue().path("review").path("evidence").forEach(evidence -> {
                if (evidence.path("kind").asText().equals("REQUIREMENT")) documents.add(evidence.deepCopy());
            });
            target.set("openQuestions", entry.getValue().path("review").path("openQuestions").deepCopy());
        });
        payload.put("evidenceLimit", "User-supplied excerpts and plans; original documents not independently verified");
        return payload;
    }

    private ObjectNode documentChecks(ObjectNode input) {
        ObjectNode checks = validator.mapper().createObjectNode();
        input.path("requirements").properties().forEach(entry -> {
            var questions = checks.putObject(entry.getKey()).putArray("scenarioGaps");
            JsonNode req = entry.getValue();
            for (var perspective : java.util.Map.of("transaction", "ROLLBACK", "concurrency", "CONCURRENCY", "idempotency", "DUPLICATE").entrySet()) {
                if (req.path(perspective.getKey()).path("applicability").asText().equals("APPLICABLE")
                        && req.path("scenarios").properties().stream().noneMatch(s -> s.getValue().path("type").asText().equals(perspective.getValue())))
                    questions.add("Review missing " + perspective.getValue() + " scenario for " + perspective.getKey());
            }
        });
        checks.put("status", "REVIEW_REQUIRED");
        checks.put("reason", "Structural gap candidates only; completeness and document meaning require review");
        return checks;
    }
}
