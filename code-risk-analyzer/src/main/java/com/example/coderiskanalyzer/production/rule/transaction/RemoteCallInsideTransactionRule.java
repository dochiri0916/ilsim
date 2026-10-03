package com.example.coderiskanalyzer.production.rule.transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
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
public class RemoteCallInsideTransactionRule implements RiskRule {

    private final SideEffectDetector sideEffectDetector;

    public RemoteCallInsideTransactionRule(SideEffectDetector sideEffectDetector) {
        this.sideEffectDetector = sideEffectDetector;
    }

    @Override public String id() { return "TX-003"; }
    @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
    @Override public Severity defaultSeverity() { return Severity.MEDIUM; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                TypeDeclaration<?> owner = TransactionRuleSupport.enclosingType(method);
                if (owner == null || !TransactionRuleSupport.isTransactional(method, owner)) continue;
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (sideEffectDetector.classify(call).orElse(null) != SideEffectType.EXTERNAL_HTTP_CALL) continue;
                    var location = TransactionRuleSupport.location(source, call);
                    Evidence evidence = new Evidence("REMOTE_CALL_IN_TRANSACTION",
                            "An outbound HTTP call occurs while a transaction is active", location,
                            Map.of("method", call.getNameAsString(),
                                    "receiver", call.getScope().map(Object::toString).orElse("")));
                    findings.add(new Finding(id(), category(), Severity.MEDIUM, Confidence.HIGH,
                            "Remote call inside transaction",
                            "The remote call can extend lock duration and can leave remote and database state inconsistent on partial failure.",
                            location, sideEffectDetector.withEvidence(source, List.of(call), List.of(evidence)), FindingStatus.REVIEW));
                }
            }
        }
        return findings;
    }
}
