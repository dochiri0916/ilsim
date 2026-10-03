package com.example.coderiskanalyzer.production.rule.reliability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

@Component
public class DatabaseExternalSideEffectConsistencyRule implements RiskRule {

    private final SideEffectDetector detector;

    public DatabaseExternalSideEffectConsistencyRule(SideEffectDetector detector) { this.detector = detector; }

    @Override public String id() { return "REL-001"; }
    @Override public RiskCategory category() { return RiskCategory.RELIABILITY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
                List<MethodCallExpr> writes = calls.stream().filter(call -> isDatabaseWrite(call)).toList();
                List<MethodCallExpr> external = calls.stream().filter(call -> isExternalCall(call)).toList();
                if (writes.isEmpty() || external.isEmpty() || hasConsistencyProtection(method)) continue;
                SourceLocation location = location(source, external.getFirst());
                List<Evidence> evidence = new ArrayList<>();
                evidence.add(new Evidence("DATABASE_WRITE", "Persistent state is changed in the same method flow.",
                        location(source, writes.getFirst()), Map.of("method", writes.getFirst().getNameAsString())));
                evidence.add(new Evidence("EXTERNAL_SIDE_EFFECT", "An external call can succeed or fail independently of the database transaction.",
                        location, Map.of("method", external.getFirst().getNameAsString(),
                                "receiver", external.getFirst().getScope().map(Node::toString).orElse(""))));
                findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                        "Database and external side effects may diverge", "Partial failure can commit one side effect while the other fails; no outbox, idempotency, or compensation evidence was found.",
                        location, detector.withEvidence(source,
                                java.util.stream.Stream.concat(writes.stream(), external.stream()).toList(), evidence),
                        FindingStatus.REVIEW));
            }
        }
        return findings.stream().distinct().toList();
    }

    private boolean isDatabaseWrite(MethodCallExpr call) {
        SideEffectType type = detector.classify(call).orElse(null);
        return type == SideEffectType.DATABASE_WRITE || type == SideEffectType.DATABASE_DELETE;
    }

    private boolean isExternalCall(MethodCallExpr call) {
        SideEffectType type = detector.classify(call).orElse(null);
        return type == SideEffectType.EXTERNAL_HTTP_CALL || type == SideEffectType.MESSAGE_PUBLISH;
    }

    private boolean hasConsistencyProtection(MethodDeclaration method) {
        String body = method.toString().toLowerCase(Locale.ROOT);
        return body.contains("outbox") || body.contains("compensat") || body.contains("rollback")
                || body.contains("idempotency-key") || body.contains("idempotencykey")
                || body.contains("processed_event") || body.contains("dedup");
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
