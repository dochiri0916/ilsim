package com.example.coderiskanalyzer.production.analysis.callgraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.springframework.stereotype.Component;

@Component
public class CallGraphBuilder {

    public static final int DEFAULT_MAX_DEPTH = 5;

    public static String methodId(String owner, MethodDeclaration method) {
        return owner + "#" + method.getNameAsString() + "("
                + String.join(",", method.getParameters().stream().map(parameter -> parameter.getTypeAsString()).toList()) + ")";
    }

    public CallGraph build(List<ParsedSource> sources) {
        List<MethodNode> methods = new ArrayList<>();
        for (ParsedSource source : sources) {
            if (source.compilationUnit() == null) continue;
            String packageName = source.compilationUnit().getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString()).orElse("");
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                String owner = packageName.isBlank() ? type.getNameAsString() : packageName + "." + type.getNameAsString();
                for (MethodDeclaration method : type.getMethods()) methods.add(new MethodNode(owner, type, method));
            }
        }

        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        for (MethodNode method : methods) {
            outgoing.computeIfAbsent(method.id(), ignored -> new LinkedHashSet<>());
            for (MethodCallExpr call : method.method().findAll(MethodCallExpr.class)) {
                List<MethodNode> candidates = resolveLocalCandidates(call, method, methods);
                if (!candidates.isEmpty()) {
                    candidates.forEach(candidate -> outgoing.get(method.id()).add(candidate.id()));
                } else {
                    outgoing.get(method.id()).add(externalId(call, method));
                }
            }
        }
        Map<String, Set<String>> incoming = new LinkedHashMap<>();
        outgoing.forEach((caller, targets) -> targets.forEach(target ->
                incoming.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(caller)));
        return new CallGraph(outgoing, incoming);
    }

    private List<MethodNode> resolveLocalCandidates(MethodCallExpr call, MethodNode caller, List<MethodNode> methods) {
        try {
            var resolved = call.resolve();
            String owner = resolved.declaringType().getQualifiedName();
            List<String> parameters = java.util.stream.IntStream.range(0, resolved.getNumberOfParams())
                    .mapToObj(index -> resolved.getParam(index).getType().describe()).toList();
            return methods.stream().filter(candidate -> candidate.owner().equals(owner)
                    && candidate.method().getNameAsString().equals(call.getNameAsString())
                    && candidate.method().getParameters().size() == parameters.size()
                    && java.util.stream.IntStream.range(0, parameters.size()).allMatch(index ->
                            sameType(candidate.method().getParameter(index).getTypeAsString(), parameters.get(index))))
                    .toList();
        } catch (RuntimeException ignored) {
            // Fall back to an unambiguous local name/arity match when symbol resolution is unavailable.
        }
        String owner = localOwner(call, caller);
        if (owner == null) return List.of();
        List<MethodNode> candidates = methods.stream().filter(candidate -> candidate.owner().equals(owner)
                && candidate.method().getNameAsString().equals(call.getNameAsString())
                && candidate.method().getParameters().size() == call.getArguments().size()).toList();
        return candidates.size() == 1 ? candidates : List.of();
    }

    private boolean sameType(String sourceType, String resolvedType) {
        String source = sourceType.replaceAll("\\s+", "");
        String resolved = resolvedType.replaceAll("\\s+", "");
        return source.equals(resolved) || resolved.endsWith("." + source) || source.endsWith("." + resolved);
    }

    private String localOwner(MethodCallExpr call, MethodNode caller) {
        if (call.getScope().isEmpty() || call.getScope().get().isThisExpr()) return caller.owner();
        String scope = call.getScope().get().toString();
        if (scope.equals("super")) return null;
        if (scope.startsWith("this.")) scope = scope.substring("this.".length());
        if (scope.contains(".")) {
            String first = scope.substring(0, scope.indexOf('.'));
            String packageName = caller.owner().contains(".")
                    ? caller.owner().substring(0, caller.owner().lastIndexOf('.')) : "";
            return packageName.isBlank() ? first : packageName + "." + first;
        }
        for (VariableDeclarator variable : caller.method().findAll(VariableDeclarator.class)) {
            if (variable.getNameAsString().equals(scope)) return qualify(caller.owner(), variable.getTypeAsString());
        }
        String variableName = scope;
        for (var field : caller.type().getFields()) {
            if (field.getVariables().stream().anyMatch(variable -> variable.getNameAsString().equals(variableName))) {
                return qualify(caller.owner(), field.getElementType().asString());
            }
        }
        for (var parameter : caller.method().getParameters()) {
            if (parameter.getNameAsString().equals(variableName)) return qualify(caller.owner(), parameter.getTypeAsString());
        }
        return null;
    }

    private String qualify(String currentOwner, String type) {
        String simpleType = type.replaceAll("<.*>", "").replaceAll("\\[\\]", "");
        if (simpleType.contains(".")) return simpleType;
        String packageName = currentOwner.contains(".") ? currentOwner.substring(0, currentOwner.lastIndexOf('.')) : "";
        return packageName.isBlank() ? simpleType : packageName + "." + simpleType;
    }

    private String externalId(MethodCallExpr call, MethodNode caller) {
        String receiver = call.getScope().map(Object::toString).orElse("this");
        String receiverType = receiver;
        if (call.getScope().isPresent()) {
            try {
                receiverType = call.getScope().get().calculateResolvedType().describe();
            } catch (RuntimeException ignored) {
                // Unresolved external symbols remain explicit unresolved graph nodes.
            }
        }
        return "external:" + receiverType + "#" + call.getNameAsString() + "/" + call.getArguments().size();
    }

    private record MethodNode(String owner, TypeDeclaration<?> type, MethodDeclaration method) {
        String id() { return methodId(owner, method); }
    }
}
