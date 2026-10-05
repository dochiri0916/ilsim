package com.example.coderiskanalyzer.production.rule.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowAnalyzer;
import com.example.coderiskanalyzer.production.analysis.dataflow.TaintSinkKind;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;

@Component
public class CommandInjectionRule implements RiskRule {

    private final DataFlowAnalyzer dataFlowAnalyzer;

    public CommandInjectionRule(DataFlowAnalyzer dataFlowAnalyzer) {
        this.dataFlowAnalyzer = dataFlowAnalyzer;
    }

    @Override public String id() { return "SEC-002"; }
    @Override public RiskCategory category() { return RiskCategory.SECURITY; }
    @Override public Severity defaultSeverity() { return Severity.CRITICAL; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                for (var path : dataFlowAnalyzer.analyze(method, source, TaintSinkKind.COMMAND, context.resolutionSources())) {
                    Evidence evidence = new Evidence("TAINTED_COMMAND_INPUT",
                            "Request-controlled value reaches process execution",
                            path.sinkLocation(), Map.of("source", path.source().variable(),
                                    "sourceKind", path.source().kind(), "sink", path.sinkMethod()));
                    findings.add(new Finding(id(), category(), Severity.CRITICAL, Confidence.HIGH,
                            "Command injection", "User-controlled input reaches Runtime.exec or ProcessBuilder.",
                            path.sinkLocation(), List.of(evidence), FindingStatus.CONFIRMED));
                }
            }
        }
        return findings.stream().distinct().toList();
    }
}
