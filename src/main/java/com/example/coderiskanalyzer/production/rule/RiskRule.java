package com.example.coderiskanalyzer.production.rule;

import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;

public interface RiskRule {
    String id();
    RiskCategory category();
    Severity defaultSeverity();
    List<Finding> analyze(AnalysisContext context);
}
