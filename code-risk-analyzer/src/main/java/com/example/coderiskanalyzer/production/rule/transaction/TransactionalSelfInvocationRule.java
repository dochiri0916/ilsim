package com.example.coderiskanalyzer.production.rule.transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

@Component
public class TransactionalSelfInvocationRule implements RiskRule {

    @Override public String id() { return "TX-001"; }
    @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                List<MethodDeclaration> methods = type.getMethods();
                List<MethodDeclaration> transactional = methods.stream()
                        .filter(TransactionalSelfInvocationRule::isTransactional).toList();
                for (MethodDeclaration caller : methods) {
                    for (MethodCallExpr call : caller.findAll(MethodCallExpr.class)) {
                        if (call.getScope().isPresent() && !call.getScope().get().isThisExpr()) continue;
                        for (MethodDeclaration callee : transactional) {
                            if (callee == caller || !call.getNameAsString().equals(callee.getNameAsString())) continue;
                            if (call.getArguments().size() != callee.getParameters().size()) continue;
                            if (TransactionRuleSupport.isTransactional(caller, type) && !changesPropagation(callee)) continue;
                            findings.add(finding(source, callee, call));
                        }
                    }
                }
            }
        }
        return findings;
    }

    private static boolean changesPropagation(MethodDeclaration method) {
        return method.getAnnotations().stream().filter(annotation -> annotation.getNameAsString().endsWith("Transactional"))
                .anyMatch(annotation -> annotation.toString().contains("propagation"));
    }

    private static boolean isTransactional(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getNameAsString().endsWith("Transactional"));
    }

    private Finding finding(ParsedSource source, MethodDeclaration callee, MethodCallExpr call) {
        SourceLocation location = location(source, call);
        Evidence callEvidence = new Evidence("INTERNAL_CALL",
                "Method invokes a transactional method on the same class",
                location, Map.of("callee", callee.getNameAsString()));
        Evidence annotationEvidence = new Evidence("TRANSACTIONAL_ANNOTATION",
                "The invoked method is annotated with @Transactional",
                location(source, callee), Map.of("method", callee.getNameAsString()));
        return new Finding(id(), category(), Severity.HIGH, Confidence.HIGH,
                "Transactional self invocation",
                "Spring proxy based transaction interception may be bypassed when this method calls "
                        + callee.getNameAsString() + "() on the same object.",
                location, List.of(callEvidence, annotationEvidence), FindingStatus.CONFIRMED);
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
