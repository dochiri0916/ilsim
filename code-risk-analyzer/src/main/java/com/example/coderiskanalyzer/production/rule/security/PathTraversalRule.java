package com.example.coderiskanalyzer.production.rule.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowAnalyzer;
import com.example.coderiskanalyzer.production.analysis.dataflow.SecurityProtectionDetector;
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
public class PathTraversalRule implements RiskRule {

    private final DataFlowAnalyzer dataFlowAnalyzer;

    public PathTraversalRule(DataFlowAnalyzer dataFlowAnalyzer) {
        this.dataFlowAnalyzer = dataFlowAnalyzer;
    }

    @Override public String id() { return "SEC-003"; }
    @Override public RiskCategory category() { return RiskCategory.SECURITY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                for (var path : dataFlowAnalyzer.analyze(method, source, TaintSinkKind.PATH)) {
                    if (SecurityProtectionDetector.hasPathContainmentGuard(method, path.source().variable(), path.sinkLocation())) continue;
                    Evidence evidence = new Evidence("TAINTED_FILE_PATH",
                            "Request-controlled value reaches a file read or write operation",
                            path.sinkLocation(), Map.of("source", path.source().variable(),
                                    "sourceKind", path.source().kind(), "sink", path.sinkMethod()));
                    findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                            "Path traversal", "User-controlled input reaches a file operation; containment validation "
                                    + "could not be established statically.",
                            path.sinkLocation(), List.of(evidence), FindingStatus.REVIEW));
                }
            }
        }
        return findings.stream().collect(java.util.stream.Collectors.toMap(Finding::location, finding -> finding,
                (first, second) -> first, java.util.LinkedHashMap::new)).values().stream().toList();
    }
}
