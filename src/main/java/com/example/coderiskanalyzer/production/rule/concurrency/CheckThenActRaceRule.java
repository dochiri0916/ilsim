package com.example.coderiskanalyzer.production.rule.concurrency;

import java.util.ArrayList;
import java.util.HashSet;
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
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;

@Component
public class CheckThenActRaceRule implements RiskRule {

    private static final Set<String> WRITE_METHODS = Set.of(
            "save", "saveAll", "insert", "persist", "update", "delete", "deleteById");

    @Override public String id() { return "CON-001"; }
    @Override public RiskCategory category() { return RiskCategory.CONCURRENCY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        DatabaseUniquenessProtection protection = new DatabaseUniquenessProtection(context);
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                for (IfStmt branch : method.findAll(IfStmt.class)) {
                    List<MethodCallExpr> checks = branch.getCondition().findAll(MethodCallExpr.class).stream()
                            .filter(this::isCheck).toList();
                    List<MethodCallExpr> writes = branch.getThenStmt().findAll(MethodCallExpr.class).stream()
                            .filter(this::isWrite).toList();
                    for (MethodCallExpr check : checks) {
                        Set<String> checkKeys = argumentNames(check);
                        for (MethodCallExpr write : writes) {
                            Set<String> writeKeys = argumentNames(write);
                            Set<String> common = new HashSet<>(checkKeys);
                            common.retainAll(writeKeys);
                            if (!common.isEmpty() && !protection.protects(check, write)) {
                                findings.add(finding(source, check, write, common));
                            }
                        }
                    }
                }
            }
        }
        return findings;
    }

    private boolean isCheck(MethodCallExpr call) {
        String name = call.getNameAsString().toLowerCase();
        return name.startsWith("exists") || name.startsWith("find") || name.startsWith("count")
                || name.startsWith("select");
    }

    private boolean isWrite(MethodCallExpr call) {
        String name = call.getNameAsString();
        return WRITE_METHODS.contains(name) || name.startsWith("deleteBy") || name.startsWith("update")
                || name.startsWith("insertIfAbsent") || name.startsWith("saveIfAbsent");
    }

    private Set<String> argumentNames(MethodCallExpr call) {
        Set<String> names = new HashSet<>();
        call.getArguments().forEach(argument -> argument.findAll(NameExpr.class)
                .forEach(name -> names.add(name.getNameAsString())));
        return names;
    }

    private Finding finding(ParsedSource source, MethodCallExpr check, MethodCallExpr write, Set<String> keys) {
        SourceLocation location = location(source, write);
        Evidence checkEvidence = new Evidence("EXISTENCE_CHECK", "A read/check call is used as a condition",
                location(source, check), Map.of("method", check.getNameAsString()));
        Evidence writeEvidence = new Evidence("MUTATION", "A mutation call follows in the conditional branch",
                location, Map.of("method", write.getNameAsString(), "matchingVariables", List.copyOf(keys)));
        List<Evidence> evidence = new ArrayList<>(List.of(checkEvidence, writeEvidence));
        if (check.findAncestor(MethodDeclaration.class).filter(MethodDeclaration::isSynchronized).isPresent()
                || check.findAncestor(SynchronizedStmt.class).isPresent()) {
            evidence.add(new Evidence("JVM_LOCAL_LOCK",
                    "synchronized protects only one JVM; database races across instances remain possible",
                    location(source, check), Map.of("scope", "JVM")));
        }
        return new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                "Check-then-act race",
                check.getNameAsString() + " is checked before " + write.getNameAsString()
                        + "; concurrent executions may both pass the check.",
                location, evidence, FindingStatus.REVIEW);
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
