package com.example.coderiskanalyzer.production.rule.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.callgraph.CallGraphBuilder;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

/** Project methods reachable from a method through the bounded call graph, for checks done deeper in the call chain. */
final class CallPaths {

    private final AnalysisContext context;
    private final Map<String, Declared> methods = new LinkedHashMap<>();

    record Declared(TypeDeclaration<?> type, MethodDeclaration method) {}

    /** Reachable project methods (the start included) and the number of unresolved calls that could hide a check. */
    record Reach(List<Declared> methods, int unresolvedCalls) {}

    CallPaths(AnalysisContext context) {
        this.context = context;
        for (var source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            String packageName = source.compilationUnit().getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                String owner = packageName.isBlank() ? type.getNameAsString() : packageName + "." + type.getNameAsString();
                for (MethodDeclaration method : type.getMethods()) {
                    methods.putIfAbsent(CallGraphBuilder.methodId(owner, method), new Declared(type, method));
                }
            }
        }
    }

    Reach from(TypeDeclaration<?> type, MethodDeclaration method) {
        String start = methods.entrySet().stream().filter(entry -> entry.getValue().method() == method)
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (start == null) return new Reach(List.of(new Declared(type, method)), 0);
        Set<String> reached = context.callGraph().reachableFrom(start, CallGraphBuilder.DEFAULT_MAX_DEPTH);
        List<Declared> found = new ArrayList<>();
        int unresolved = 0;
        for (String id : reached.stream().sorted().toList()) {
            if (id.startsWith("external:")) unresolved++;
            else if (methods.containsKey(id)) found.add(methods.get(id));
        }
        if (found.stream().noneMatch(declared -> declared.method() == method)) found.addFirst(new Declared(type, method));
        return new Reach(List.copyOf(found), unresolved);
    }
}
