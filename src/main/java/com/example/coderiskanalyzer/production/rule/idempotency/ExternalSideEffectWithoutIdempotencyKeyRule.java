package com.example.coderiskanalyzer.production.rule.idempotency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector;
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
public class ExternalSideEffectWithoutIdempotencyKeyRule implements RiskRule {

    private final SideEffectDetector detector;

    public ExternalSideEffectWithoutIdempotencyKeyRule(SideEffectDetector detector) { this.detector = detector; }

    @Override public String id() { return "IDEM-002"; }
    @Override public RiskCategory category() { return RiskCategory.IDEMPOTENCY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                if (!IdempotencyRuleSupport.isRetryable(method) && !IdempotencyRuleSupport.isListener(method)) continue;
                if (IdempotencyRuleSupport.hasIdempotencyKey(method)) continue;
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (!IdempotencyRuleSupport.isExternalMutation(call, detector)) continue;
                    SourceLocation location = location(source, call);
                    Evidence evidence = new Evidence("RETRYABLE_EXTERNAL_MUTATION",
                            "A retryable or redelivered method performs an external mutation without a visible idempotency key.",
                            location, Map.of("method", call.getNameAsString(), "receiver",
                                    call.getScope().map(Node::toString).orElse(""),
                                    "retryable", IdempotencyRuleSupport.isRetryable(method),
                                    "messageListener", IdempotencyRuleSupport.isListener(method)));
                    findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                            "External mutation may be repeated", "A retry or message redelivery can invoke this external mutation again; no Idempotency-Key evidence was found.",
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
