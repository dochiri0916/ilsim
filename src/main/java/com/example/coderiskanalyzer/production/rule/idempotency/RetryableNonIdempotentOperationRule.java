package com.example.coderiskanalyzer.production.rule.idempotency;

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
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

@Component
public class RetryableNonIdempotentOperationRule implements RiskRule {

    private final SideEffectDetector detector;

    public RetryableNonIdempotentOperationRule(SideEffectDetector detector) { this.detector = detector; }

    @Override public String id() { return "IDEM-003"; }
    @Override public RiskCategory category() { return RiskCategory.IDEMPOTENCY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                if (!IdempotencyRuleSupport.isRetryable(method)) continue;
                for (MethodCallExpr call : IdempotencyRuleSupport.effects(method, detector)) {
                    SideEffectType effect = detector.classify(call).orElseThrow();
                    if (effect != SideEffectType.DATABASE_WRITE && effect != SideEffectType.DATABASE_DELETE
                            && effect != SideEffectType.EXTERNAL_HTTP_CALL && effect != SideEffectType.MESSAGE_PUBLISH) continue;
                    SourceLocation location = location(source, call);
                    Evidence evidence = new Evidence("RETRYABLE_SIDE_EFFECT",
                            "A retryable method performs a persistent or external side effect that may run more than once.",
                            location, Map.of("operation", call.getNameAsString(), "receiver",
                                    call.getScope().map(Node::toString).orElse(""), "sideEffect", effect.name()));
                    findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.HIGH,
                            "Non-idempotent operation in retryable method", "A retry can repeat this side effect unless the operation is idempotent or protected by a deduplication key.",
                            location, detector.withEvidence(source, List.of(call), List.of(evidence)), FindingStatus.REVIEW));
                }
            }
        }
        return findings.stream().distinct().toList();
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
