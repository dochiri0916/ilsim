package com.example.coderiskanalyzer.production.rule.idempotency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public class MessageConsumerSideEffectRule implements RiskRule {

    private static final Set<String> LISTENER_ANNOTATIONS = Set.of("KafkaListener", "RabbitListener", "JmsListener");
    private final SideEffectDetector sideEffectDetector;

    public MessageConsumerSideEffectRule(SideEffectDetector sideEffectDetector) {
        this.sideEffectDetector = sideEffectDetector;
    }

    @Override public String id() { return "IDEM-001"; }
    @Override public RiskCategory category() { return RiskCategory.IDEMPOTENCY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                var listener = method.getAnnotations().stream()
                        .filter(annotation -> LISTENER_ANNOTATIONS.contains(simpleName(annotation.getNameAsString())))
                        .findFirst();
                if (listener.isEmpty() || hasDeduplicationEvidence(method)) continue;
                List<MethodCallExpr> effects = method.findAll(MethodCallExpr.class).stream()
                        .filter(call -> sideEffectDetector.classify(call).isPresent()).toList();
                if (!effects.isEmpty()) findings.add(finding(source, method, listener.get().getNameAsString(), effects));
            }
        }
        return findings;
    }

    private boolean hasDeduplicationEvidence(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream().map(call -> call.getNameAsString().toLowerCase())
                .anyMatch(name -> name.contains("processed") || name.contains("dedup") || name.contains("idempot")
                        || (name.startsWith("exists") || name.startsWith("find")) && name.contains("eventid"));
    }

    private Finding finding(ParsedSource source, MethodDeclaration method, String listener,
                            List<MethodCallExpr> effects) {
        SourceLocation location = location(source, method);
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("MESSAGE_LISTENER", "Message listener entry point detected", location,
                Map.of("annotation", listener)));
        for (MethodCallExpr effect : effects) {
            evidence.add(new Evidence("SIDE_EFFECT", "Potential side effect occurs in the listener",
                    location(source, effect), Map.of("method", effect.getNameAsString(),
                            "receiver", effect.getScope().map(Node::toString).orElse(""),
                            "type", sideEffectDetector.classify(effect).orElseThrow().name())));
        }
        return new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                "Message consumer may repeat side effects",
                "The message listener performs a side effect, but no deduplication evidence was found in the method.",
                location, sideEffectDetector.withEvidence(source, effects, evidence), FindingStatus.REVIEW);
    }

    private String simpleName(String annotation) {
        int dot = annotation.lastIndexOf('.');
        return dot < 0 ? annotation : annotation.substring(dot + 1);
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
