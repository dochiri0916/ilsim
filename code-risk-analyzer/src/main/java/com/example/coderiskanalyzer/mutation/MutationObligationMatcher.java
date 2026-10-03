package com.example.coderiskanalyzer.mutation;

import java.util.ArrayList;
import java.util.List;
import com.example.coderiskanalyzer.testanalysis.TestAdequacyResult;
import org.springframework.stereotype.Component;

/** Links only unambiguous class/method mutations; overloaded or method-level ambiguity remains unmapped. */
@Component
public class MutationObligationMatcher {
    public List<Match> match(List<TestAdequacyResult> adequacy, List<MutationEvidence> mutations) {
        List<Match> matches = new ArrayList<>();
        for (MutationEvidence mutation : mutations) {
            List<TestAdequacyResult> candidates = adequacy.stream().filter(result ->
                    normalizeClass(result.obligation().owner()).equals(normalizeClass(mutation.mutatedClass()))
                            && result.obligation().method().equals(mutation.mutatedMethod())
                            && relevantType(result.obligation().type(), mutation.mutator())
                            && result.obligation().location().startLine() <= mutation.line()
                            && result.obligation().location().endLine() >= mutation.line()).toList();
            // Multiple obligations commonly share a method range. Do not infer the semantic obligation.
            matches.add(new Match(mutation, candidates.size() == 1 ? candidates.get(0) : null,
                    candidates.size() == 1 ? "MATCHED" : "UNMAPPED"));
        }
        return List.copyOf(matches);
    }

    private String normalizeClass(String name) { return name == null ? "" : name.replace('$', '.'); }

    private boolean relevantType(String type, String mutator) {
        String name = mutator.toUpperCase(java.util.Locale.ROOT);
        if (name.contains("CONDITIONALS_BOUNDARY") || name.contains("CONDITIONALBOUNDARY")
                || name.contains("CONDITIONALSBOUNDARY")) return type.equals("BOUNDARY");
        if (name.contains("CONDITIONALS_NEGATE") || name.contains("NEGATE_CONDITIONALS")
                || name.contains("NEGATECONDITIONALS")) return type.equals("BRANCH");
        if (name.contains("RETURN")) return type.equals("RETURN_VALUE");
        return false;
    }

    public record Match(MutationEvidence mutation, TestAdequacyResult adequacy, String mappingStatus) {}
}
