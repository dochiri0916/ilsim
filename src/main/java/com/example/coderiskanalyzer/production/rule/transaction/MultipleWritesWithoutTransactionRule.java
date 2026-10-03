package com.example.coderiskanalyzer.production.rule.transaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.callgraph.CallGraphBuilder;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectType;
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
public class MultipleWritesWithoutTransactionRule implements RiskRule {

    private final SideEffectDetector sideEffectDetector;

    public MultipleWritesWithoutTransactionRule(SideEffectDetector sideEffectDetector) {
        this.sideEffectDetector = sideEffectDetector;
    }

    @Override public String id() { return "TX-004"; }
    @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
    @Override public Severity defaultSeverity() { return Severity.MEDIUM; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        Set<String> transactionallyReached = transactionallyReachedMethods(context);
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                List<MethodDeclaration> methods = type.getMethods();
                for (MethodDeclaration method : methods) {
                    if (transactionallyReached.contains(CallGraphBuilder.methodId(qualifiedName(source, type), method))) continue;
                    List<MethodCallExpr> writes = method.findAll(MethodCallExpr.class).stream()
                            .filter(call -> isDatabaseMutation(call)).toList();
                    if (writes.size() < 2) continue;
                    var location = TransactionRuleSupport.location(source, writes.get(1));
                    Evidence evidence = new Evidence("MULTIPLE_DATABASE_WRITES",
                            "Multiple database mutations occur in this method without a transaction annotation in the known caller chain",
                            location, Map.of("methods", writes.stream().map(MethodCallExpr::getNameAsString).toList()));
                    findings.add(new Finding(id(), category(), Severity.MEDIUM, Confidence.MEDIUM,
                            "Multiple persistent writes without transaction",
                            "Partial failure may leave only some database mutations committed; caller transaction coverage was not found.",
                            location, sideEffectDetector.withEvidence(source, writes, List.of(evidence)), FindingStatus.REVIEW));
                }
            }
        }
        return findings;
    }

    private Set<String> transactionallyReachedMethods(AnalysisContext context) {
        Set<String> transactionalMethods = new HashSet<>();
        for (ParsedSource candidateSource : context.parsedSources()) {
            if (candidateSource.compilationUnit() == null) continue;
            String packageName = candidateSource.compilationUnit().getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString()).orElse("");
            for (TypeDeclaration<?> candidateType : candidateSource.compilationUnit().findAll(TypeDeclaration.class)) {
                String candidateOwner = packageName.isBlank() ? candidateType.getNameAsString()
                        : packageName + "." + candidateType.getNameAsString();
                candidateType.getMethods().stream()
                        .filter(candidate -> TransactionRuleSupport.isTransactional(candidate, candidateType))
                        .map(candidate -> CallGraphBuilder.methodId(candidateOwner, candidate))
                        .forEach(transactionalMethods::add);
            }
        }
        Set<String> reached = new HashSet<>();
        for (String transactionalMethod : transactionalMethods) {
            reached.addAll(context.callGraph().reachableFrom(transactionalMethod, CallGraphBuilder.DEFAULT_MAX_DEPTH));
        }
        return reached;
    }

    private String qualifiedName(ParsedSource source, TypeDeclaration<?> owner) {
        String packageName = source.compilationUnit().getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString()).orElse("");
        return packageName.isBlank() ? owner.getNameAsString() : packageName + "." + owner.getNameAsString();
    }

    private boolean isDatabaseMutation(MethodCallExpr call) {
        SideEffectType type = sideEffectDetector.classify(call).orElse(null);
        return type == SideEffectType.DATABASE_WRITE || type == SideEffectType.DATABASE_DELETE;
    }
}
