package com.example.coderiskanalyzer.mutation;

import java.util.List;
import java.util.Map;

public record MutationExecutionRequest(Map<String, List<String>> projectTargets) {
    public MutationExecutionRequest {
        projectTargets = projectTargets.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public List<String> targetClasses() { return projectTargets.values().stream().flatMap(List::stream).distinct().sorted().toList(); }
}
