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
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

@Component
public class AsyncTransactionBoundaryRule implements RiskRule {

    @Override public String id() { return "TX-002"; }
    @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                List<MethodDeclaration> methods = type.getMethods();
                List<MethodDeclaration> asyncMethods = methods.stream()
                        .filter(method -> TransactionRuleSupport.hasAnnotation(method, "Async")).toList();
                for (MethodDeclaration asyncMethod : asyncMethods) {
                    for (MethodDeclaration caller : methods) {
                        if (caller == asyncMethod || !TransactionRuleSupport.isTransactional(caller, type)) continue;
                        for (MethodCallExpr call : caller.findAll(MethodCallExpr.class)) {
                            if (call.getNameAsString().equals(asyncMethod.getNameAsString())
                                    && call.getArguments().size() == asyncMethod.getParameters().size()
                                    && (call.getScope().isEmpty() || call.getScope().get().isThisExpr())) {
                                findings.add(finding(source, call,
                                        "A transactional method invokes an @Async method; transaction state does not cross the executor boundary."));
                            }
                        }
                    }
                }
            }
        }
        return findings.stream().distinct().toList();
    }

    private Finding finding(ParsedSource source, com.github.javaparser.ast.Node node, String message) {
        var location = TransactionRuleSupport.location(source, node);
        Evidence evidence = new Evidence("ASYNC_TRANSACTION_BOUNDARY", message, location,
                Map.of("rule", id()));
        return new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                "Transaction crosses async boundary", message, location, List.of(evidence), FindingStatus.REVIEW);
    }
}
