package com.example.coderiskanalyzer.production.rule.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowAnalyzer;
import com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowPath;
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
public class SqlInjectionRule implements RiskRule {

    private final DataFlowAnalyzer dataFlowAnalyzer;

    public SqlInjectionRule(DataFlowAnalyzer dataFlowAnalyzer) {
        this.dataFlowAnalyzer = dataFlowAnalyzer;
    }

    @Override public String id() { return "SEC-001"; }
    @Override public RiskCategory category() { return RiskCategory.SECURITY; }
    @Override public Severity defaultSeverity() { return Severity.CRITICAL; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                for (DataFlowPath path : dataFlowAnalyzer.analyze(method, source, TaintSinkKind.SQL, context.resolutionSources())) {
                    Evidence evidence = new Evidence("TAINTED_SQL_INPUT",
                            "Request-controlled value reaches a SQL execution argument",
                            path.sinkLocation(), Map.of("source", path.source().variable(),
                                    "sourceKind", path.source().kind(), "sink", path.sinkMethod()));
                    findings.add(new Finding(id(), category(), Severity.CRITICAL, Confidence.HIGH,
                            "SQL injection", "User-controlled input is concatenated into SQL before execution.",
                            path.sinkLocation(), List.of(evidence), FindingStatus.CONFIRMED));
                }
            }
        }
        return findings.stream().distinct().toList();
    }
}
