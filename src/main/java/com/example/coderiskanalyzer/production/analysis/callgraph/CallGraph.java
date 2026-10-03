package com.example.coderiskanalyzer.production.analysis.callgraph;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A deterministic, bounded project call graph; unresolved library calls are represented as external nodes. */
public record CallGraph(Map<String, Set<String>> outgoing, Map<String, Set<String>> incoming) {

    public CallGraph {
        outgoing = immutableAdjacency(outgoing);
        incoming = immutableAdjacency(incoming);
    }

    public Set<String> reachableFrom(String methodId, int maxDepth) {
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth must not be negative");
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<Visit> queue = new ArrayDeque<>();
        visited.add(methodId);
        queue.add(new Visit(methodId, 0));
        while (!queue.isEmpty()) {
            Visit current = queue.removeFirst();
            if (current.depth() == maxDepth) continue;
            for (String target : outgoing.getOrDefault(current.id(), Set.of())) {
                if (visited.add(target) && !target.startsWith("external:")) {
                    queue.addLast(new Visit(target, current.depth() + 1));
                }
            }
        }
        return Set.copyOf(visited);
    }

    private static Map<String, Set<String>> immutableAdjacency(Map<String, Set<String>> source) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), Collections.unmodifiableSet(
                        new LinkedHashSet<>(entry.getValue()))));
        return Collections.unmodifiableMap(result);
    }

    private record Visit(String id, int depth) {}
}
