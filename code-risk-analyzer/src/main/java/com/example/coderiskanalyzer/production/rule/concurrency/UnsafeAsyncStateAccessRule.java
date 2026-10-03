package com.example.coderiskanalyzer.production.rule.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

@Component
public class UnsafeAsyncStateAccessRule implements RiskRule {

    private static final Set<String> CONTEXT_HOLDERS = Set.of(
            "RequestContextHolder", "SecurityContextHolder", "TransactionSynchronizationManager");

    @Override public String id() { return "CON-004"; }
    @Override public RiskCategory category() { return RiskCategory.CONCURRENCY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                if (method.getAnnotations().stream().noneMatch(annotation -> simpleName(annotation.getNameAsString()).equals("Async"))) continue;
                List<Node> unsafeAccesses = new ArrayList<>();
                method.getParameters().stream()
                        .filter(parameter -> Set.of("HttpServletRequest", "ServerHttpRequest", "ServerRequest")
                                .contains(simpleName(parameter.getTypeAsString())))
                        .forEach(unsafeAccesses::add);
                method.findAll(MethodCallExpr.class).stream()
                        .filter(this::usesThreadBoundContext).forEach(unsafeAccesses::add);
                for (Node access : unsafeAccesses) {
                    SourceLocation location = location(source, access);
                    String contextName = access instanceof MethodCallExpr call
                            ? call.getScope().map(Object::toString).orElse(call.getNameAsString())
                            : "request parameter";
                    Evidence evidence = new Evidence("ASYNC_THREAD_CONTEXT_ACCESS",
                            "An @Async method accesses request-scoped or thread-bound context that may not exist on the executor thread.",
                            location, Map.of("method", method.getNameAsString(), "context", contextName));
                    findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.HIGH,
                            "Thread-bound state accessed from async method",
                            "The async executor thread may not carry the caller's request, security, or transaction context.",
                            location, List.of(evidence), FindingStatus.REVIEW));
                }
            }
        }
        return findings.stream().distinct().toList();
    }

    private boolean usesThreadBoundContext(MethodCallExpr call) {
        String receiver = call.getScope().map(Object::toString).orElse("");
        String name = simpleName(receiver);
        if (CONTEXT_HOLDERS.contains(name)) return true;
        return receiver.contains("TransactionSynchronizationManager") || receiver.contains("RequestContextHolder")
                || receiver.contains("SecurityContextHolder");
    }

    private String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
