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
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;

@Component
public class EventHandlerWithoutStableBusinessKeyRule implements RiskRule {

    private final SideEffectDetector detector;

    public EventHandlerWithoutStableBusinessKeyRule(SideEffectDetector detector) { this.detector = detector; }

    @Override public String id() { return "IDEM-004"; }
    @Override public RiskCategory category() { return RiskCategory.IDEMPOTENCY; }
    @Override public Severity defaultSeverity() { return Severity.MEDIUM; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                if (!IdempotencyRuleSupport.isListener(method)
                        || IdempotencyRuleSupport.hasStableBusinessKey(method)
                        || IdempotencyRuleSupport.effects(method, detector).isEmpty()) continue;
                SourceLocation location = location(source, method);
                Evidence evidence = new Evidence("EVENT_HANDLER_KEY_NOT_FOUND",
                        "A message handler performs a side effect, but no recognizable stable event or business identifier is used.",
                        location, Map.of("listener", method.getAnnotations().stream()
                                .map(annotation -> IdempotencyRuleSupport.simpleName(annotation.getNameAsString()))
                                .filter(IdempotencyRuleSupport.LISTENER_ANNOTATIONS::contains).findFirst().orElse("listener"),
                                "method", method.getNameAsString()));
                findings.add(new Finding(id(), category(), Severity.MEDIUM, Confidence.LOW,
                        "Event handler has no recognizable stable key", "Without a stable event or business key, deduplication and safe replay are difficult to establish.",
                        location, detector.withEvidence(source, IdempotencyRuleSupport.effects(method, detector), List.of(evidence)), FindingStatus.REVIEW));
            }
        }
        return findings.stream().distinct().toList();
    }

    private SourceLocation location(ParsedSource source, com.github.javaparser.ast.Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
