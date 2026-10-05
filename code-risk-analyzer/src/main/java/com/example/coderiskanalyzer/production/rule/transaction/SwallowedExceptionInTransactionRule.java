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
        boolean[] unsafeReturn = {false};
        List<Boolean> remaining = catchPaths(catchClause.getBody(), List.of(false), unsafeReturn);
        return !unsafeReturn[0] && remaining.stream().allMatch(Boolean::booleanValue);
    }

    /** Each normal exit must mark rollback; throws only protect the paths that actually execute them. */
    private List<Boolean> catchPaths(com.github.javaparser.ast.stmt.Statement statement,
                                      List<Boolean> states, boolean[] unsafeReturn) {
        if (states.isEmpty()) return states;
        if (statement.isBlockStmt()) {
            List<Boolean> remaining = states;
            for (var child : statement.asBlockStmt().getStatements()) remaining = catchPaths(child, remaining, unsafeReturn);
            return remaining;
        }
        if (statement.isThrowStmt()) return List.of();
        if (statement.isReturnStmt()) {
            unsafeReturn[0] |= states.contains(false);
            return List.of();
        }
        if (statement.isIfStmt()) {
            var branch = statement.asIfStmt();
            if (branch.getCondition().isBooleanLiteralExpr()) {
                return branch.getCondition().asBooleanLiteralExpr().getValue()
                        ? catchPaths(branch.getThenStmt(), states, unsafeReturn)
                        : branch.getElseStmt().map(other -> catchPaths(other, states, unsafeReturn)).orElse(states);
            }
            List<Boolean> paths = new ArrayList<>(catchPaths(branch.getThenStmt(), states, unsafeReturn));
            paths.addAll(branch.getElseStmt().map(other -> catchPaths(other, states, unsafeReturn)).orElse(states));
            return paths.stream().distinct().toList();
        }
        if (statement.isExpressionStmt() && statement.asExpressionStmt().getExpression().isMethodCallExpr()) {
            var call = statement.asExpressionStmt().getExpression().asMethodCallExpr();
            if (call.getNameAsString().equals("setRollbackOnly") && call.getArguments().isEmpty()) return List.of(true);
        }
        // Loops, nested try/catch and deferred lambdas do not prove an escaping throw or rollback.
        return states;
    }
}
