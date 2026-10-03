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
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;

@Component
public class SwallowedExceptionInTransactionRule implements RiskRule {

    private final SideEffectDetector sideEffectDetector;

    public SwallowedExceptionInTransactionRule(SideEffectDetector sideEffectDetector) {
        this.sideEffectDetector = sideEffectDetector;
    }

    @Override public String id() { return "TX-005"; }
    @Override public RiskCategory category() { return RiskCategory.TRANSACTION; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                TypeDeclaration<?> owner = TransactionRuleSupport.enclosingType(method);
                if (owner == null || !TransactionRuleSupport.isTransactional(method, owner)) continue;
                for (TryStmt tryStmt : method.findAll(TryStmt.class)) {
                    boolean persistentWrite = tryStmt.getTryBlock().findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)
                            .stream().anyMatch(call -> isDatabaseMutation(call));
                    if (!persistentWrite) continue;
                    for (CatchClause catchClause : tryStmt.getCatchClauses()) {
                        if (rethrowsOrRollsBack(catchClause)) continue;
                        var location = TransactionRuleSupport.location(source, catchClause);
                        Evidence evidence = new Evidence("SWALLOWED_TRANSACTION_EXCEPTION",
                                "An exception is caught after a database mutation without visible rethrow or rollbackOnly call",
                                location, Map.of("exception", catchClause.getParameter().getTypeAsString(),
                                        "method", method.getNameAsString()));
                        findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                                "Exception swallowed in transaction",
                                "Catching the failure may let the transaction commit despite the incomplete operation.",
                                location, sideEffectDetector.withEvidence(source,
                                        tryStmt.getTryBlock().findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)
                                                .stream().filter(this::isDatabaseMutation).toList(),
                                        List.of(evidence)), FindingStatus.REVIEW));
                    }
                }
            }
        }
        return findings;
    }

    private boolean isDatabaseMutation(com.github.javaparser.ast.expr.MethodCallExpr call) {
        SideEffectType type = sideEffectDetector.classify(call).orElse(null);
        return type == SideEffectType.DATABASE_WRITE || type == SideEffectType.DATABASE_DELETE;
    }

    private boolean rethrowsOrRollsBack(CatchClause catchClause) {
        if (!catchClause.findAll(com.github.javaparser.ast.stmt.ThrowStmt.class).isEmpty()) return true;
        return catchClause.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .map(call -> call.getNameAsString().toLowerCase())
                .anyMatch(name -> name.contains("rollbackonly") || name.contains("compensate")
                        || name.contains("rollback") || name.contains("restore") || name.contains("undo"));
    }
}
