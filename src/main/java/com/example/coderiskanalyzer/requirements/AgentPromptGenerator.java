package com.example.coderiskanalyzer.requirements;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** Deterministic, compact agent prompt; it rejects inputs whose required parts are missing instead of guessing them. */
public final class AgentPromptGenerator {
    private static final List<String> PERSPECTIVES = List.of("transaction", "concurrency", "idempotency", "consistency");
    /** Applicable perspective -> scenario type that must exercise it. */
    private static final Map<String, String> REQUIRED_SCENARIO = Map.of(
            "transaction", "ROLLBACK", "concurrency", "CONCURRENCY", "idempotency", "DUPLICATE");

    public String generate(JsonNode input, String rulesPath) {
        if (!"CODE_CHANGE".equals(input.path("mode").asText()))
            throw new IllegalArgumentException("Prompt generation requires mode CODE_CHANGE");
        List<String> problems = new ArrayList<>();
        var out = new StringBuilder();
        out.append("# Task: ").append(input.path("project").path("name").asText()).append('\n');
        if (input.hasNonNull("codeRevision")) out.append("Base revision: ").append(input.path("codeRevision").asText()).append('\n');
        out.append("DB: ").append(input.path("project").path("database").asText()).append('\n');
        out.append("Rules: ").append(rulesPath == null ? "follow the repository's existing structure and conventions" : "read and follow " + rulesPath).append("\n\n");
        input.path("requirements").properties().forEach(entry -> requirement(entry.getKey(), entry.getValue(), out, problems));
        if (!problems.isEmpty()) throw new IllegalArgumentException("Prompt inputs incomplete: " + String.join("; ", problems));
        out.append("""
                ## Workflow
                1. Write tests for every scenario first; run them and confirm they fail for the expected reason.
                2. Implement the minimum change to pass; touch only the scoped files.
                3. Run `scan <project> --fail-on` (no CONFIRMED findings, review remaining REVIEW and TEST-001 gaps) and `mutation <project>`.
                4. Do not weaken or delete existing tests. Do not claim safety; report unverified items.

                ## Done when
                - Every scenario has a passing test asserting its expected outcomes.
                - The scan and mutation checks above pass or each remaining item is reported with a reason.
                """);
        return out.toString();
    }

    /** Quotes the change verbatim; it adds no domain interpretation, so scope comes only from the given files. */
    public String generateChange(String change, List<String> files, String rulesPath) {
        return "# Change\n" + change.strip() + "\n"
                + "Files (touch only these): " + String.join(", ", files) + "\n"
                + "Rules: " + (rulesPath == null ? "follow the repository's existing structure and conventions" : "read and follow " + rulesPath) + "\n\n"
                + """
                ## Workflow
                1. Before editing, run `scan <project> --fail-on off` and `baseline create` to record the current findings.
                2. Add or update a test that fails for the stated change, then make the minimum change.
                3. Put the changed files in a file list and run `scan <project> --files-file <list> --baseline <baseline> --fail-on`.
                4. Do not weaken or delete existing tests. Do not claim safety; report unverified items.

                ## Escalate
                If the scan reports new TX/CON/IDEM findings, or the change touches money, stock, state transitions, retries or messages, stop and write a full requirements input for `prompt <input.json>` instead.

                ## Done when
                - A test covers the change and passes.
                - The scoped scan reports no new CONFIRMED finding and no new TEST-001 gap, or each is reported with a reason.
                """;
    }

    private void requirement(String id, JsonNode req, StringBuilder out, List<String> problems) {
        out.append("## ").append(id).append(": ").append(req.path("title").asText()).append('\n');
        list(out, "Scope", req.path("domain").path("entryPoints"));
        list(out, "Pre", req.path("preconditions"));
        list(out, "Post", req.path("postconditions"));
        list(out, "Invariants", req.path("invariants"));
        list(out, "Logic", req.path("coreLogic"));
        var types = new java.util.HashSet<String>();
        req.path("scenarios").properties().forEach(s -> types.add(s.getValue().path("type").asText()));
        for (String name : PERSPECTIVES) {
            JsonNode p = req.path(name);
            String applicability = p.path("applicability").asText();
            if (applicability.equals("UNKNOWN")) problems.add(id + "." + name + " is UNKNOWN; resolve it first");
            if (!applicability.equals("APPLICABLE")) continue;
            list(out, name, p.path("expectations"));
            String needed = REQUIRED_SCENARIO.get(name);
            if (needed != null && !types.contains(needed))
                problems.add(id + "." + name + " is APPLICABLE but has no " + needed + " scenario");
        }
        out.append("Scenarios:\n");
        req.path("scenarios").properties().forEach(s -> {
            JsonNode v = s.getValue();
            out.append("- ").append(s.getKey()).append(" [").append(v.path("type").asText()).append("; ")
                    .append(join(v.path("testTypes"))).append("] given ").append(v.path("given").asText())
                    .append(" / when ").append(v.path("when").asText()).append(" / expect ")
                    .append(join(v.path("expectedOutcomes"))).append('\n');
        });
        out.append('\n');
    }

    private void list(StringBuilder out, String label, JsonNode items) {
        if (items.isMissingNode() || items.isEmpty()) return;
        out.append(label).append(": ").append(join(items)).append('\n');
    }

    private String join(JsonNode items) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.asText()));
        return String.join(" | ", values);
    }
}
