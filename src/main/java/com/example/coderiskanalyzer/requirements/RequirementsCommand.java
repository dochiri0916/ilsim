package com.example.coderiskanalyzer.requirements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.coderiskanalyzer.requirements.RequirementsWorkflow.*;

/** CLI approval is explicit request metadata, separate from the requirement and JEV result. */
public final class RequirementsCommand {
    public record Response(String output, int exitCode) {}
    private final RequirementsInputValidator validator = new RequirementsInputValidator();
    private final RequirementsWorkflow workflow = new RequirementsWorkflow(validator, new LocalRequirementsCodeTools(), JevRequirementsJudge::new);

    public Response execute(String[] args) {
        try {
            if (args.length < 2 || args[1].startsWith("--"))
                throw new IllegalArgumentException("Usage: requirements <input.json> [--actions DOCUMENT_ANALYSIS,DOC_JEV] [--scope scope.json] [--phase ANALYSIS] [--changes changes.json]");
            Map<String, String> options = options(args);
            String inputText = Files.readString(Path.of(args[1]));
            var input = validator.validate(inputText); // Scope and change plan cannot bypass input validation.
            List<Action> actions = Arrays.stream(options.getOrDefault("actions", "DOCUMENT_ANALYSIS").split(",", -1))
                    .map(Action::valueOf).toList();
            Phase phase = Phase.valueOf(options.getOrDefault("phase", "ANALYSIS"));
            Mode mode = Mode.valueOf(input.path("mode").asText());
            if (mode == Mode.SPEC_ONLY && (options.containsKey("scope") || options.containsKey("changes")
                    || actions.stream().anyMatch(action -> action != Action.DOCUMENT_ANALYSIS && action != Action.DOC_JEV)))
                throw new IllegalArgumentException("Code actions and scopes are forbidden in SPEC_ONLY");
            Scope scope = options.containsKey("scope") ? scope(Path.of(options.get("scope"))) : null;
            List<Change> changes = options.containsKey("changes") ? changes(Path.of(options.get("changes"))) : List.of();
            var result = workflow.execute(new Request(inputText, actions, scope, phase, changes));
            String status = result.path("status").asText();
            int exit = status.equals("FAILED") ? 1 : status.equals("NEEDS_REVIEW") ? 3 : 0;
            return new Response(validator.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result), exit);
        } catch (IllegalArgumentException exception) {
            return new Response("Error: " + safeMessage(exception), 2);
        } catch (java.io.IOException exception) {
            return new Response("Error: could not read requirements input or execution metadata", 2);
        } catch (RuntimeException exception) {
            return new Response("Error: requirements workflow failed; no safety conclusion can be drawn", 2);
        }
    }

    private Map<String,String> options(String[] args) {
        Map<String,String> result = new LinkedHashMap<>();
        for (int index = 2; index < args.length; index++) {
            String token = args[index];
            if (!token.startsWith("--")) throw new IllegalArgumentException("Unexpected requirements argument");
            String[] split = token.substring(2).split("=", 2);
            if (!Set.of("actions", "scope", "phase", "changes").contains(split[0])) throw new IllegalArgumentException("Unknown requirements option");
            String value;
            if (split.length == 2) value = split[1];
            else {
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) throw new IllegalArgumentException("Option requires a value");
                value = args[++index];
            }
            if (value.isBlank() || result.putIfAbsent(split[0], value) != null) throw new IllegalArgumentException("Empty or repeated requirements option");
        }
        return result;
    }

    private Scope scope(Path path) throws java.io.IOException {
        var scope = validator.parse(Files.readString(path));
        fields(scope, Set.of("project", "files", "actions"));
        if (!scope.path("project").isTextual() || scope.path("project").asText().isBlank()) throw new IllegalArgumentException("Scope requires a project path");
        Set<String> files = strings(scope.path("files"));
        Set<Action> actions = new LinkedHashSet<>();
        for (String action : strings(scope.path("actions"))) actions.add(Action.valueOf(action));
        return new Scope(Path.of(scope.path("project").asText()), files, actions);
    }
    private List<Change> changes(Path path) throws java.io.IOException {
        var plan = validator.parse(Files.readString(path));
        fields(plan, Set.of("changes"));
        if (!plan.path("changes").isArray()) throw new IllegalArgumentException("Change plan requires a changes array");
        List<Change> result = new ArrayList<>();
        for (JsonNode change : plan.path("changes")) {
            fields(change, Set.of("path", "expectedSha256", "content"));
            for (String field : List.of("path", "expectedSha256", "content")) if (!change.path(field).isTextual())
                throw new IllegalArgumentException("Change plan requires textual path, digest and content");
            result.add(new Change(change.path("path").asText(), change.path("expectedSha256").asText(), change.path("content").asText()));
        }
        return result;
    }
    private void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject() || !node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(expected))
            throw new IllegalArgumentException("Execution metadata contains missing or unknown fields");
    }
    private Set<String> strings(JsonNode values) {
        if (!values.isArray()) throw new IllegalArgumentException("Scope files and actions must be arrays");
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) if (!value.isTextual() || value.asText().isBlank() || !result.add(value.asText()))
            throw new IllegalArgumentException("Scope arrays require distinct nonblank strings");
        return result;
    }
    private String safeMessage(IllegalArgumentException exception) {
        // Enum parsing otherwise echoes arbitrary input; policy messages contain only fixed action names.
        return exception.getMessage() != null && !exception.getMessage().startsWith("No enum constant")
                ? exception.getMessage() : "Unknown action or phase";
    }
}
