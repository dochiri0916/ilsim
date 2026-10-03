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
public class SsrfRule implements RiskRule {

    private final DataFlowAnalyzer dataFlowAnalyzer;

    public SsrfRule(DataFlowAnalyzer dataFlowAnalyzer) {
        this.dataFlowAnalyzer = dataFlowAnalyzer;
    }

    @Override public String id() { return "SEC-004"; }
    @Override public RiskCategory category() { return RiskCategory.SECURITY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                for (var path : dataFlowAnalyzer.analyze(method, source, TaintSinkKind.SSRF)) {
                    if (SecurityProtectionDetector.hasHostAllowlistGuard(method, path.source().variable(), path.sinkLocation())) continue;
                    Evidence evidence = new Evidence("TAINTED_REQUEST_URI",
                            "Request-controlled value reaches an outbound HTTP request target",
                            path.sinkLocation(), Map.of("source", path.source().variable(),
                                    "sourceKind", path.source().kind(), "sink", path.sinkMethod()));
                    findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                            "Server-side request forgery", "User-controlled input determines an outbound request target; "
                                    + "an allowlist could not be established statically.",
                            path.sinkLocation(), List.of(evidence), FindingStatus.REVIEW));
                }
            }
        }
        return findings;
    }
}
