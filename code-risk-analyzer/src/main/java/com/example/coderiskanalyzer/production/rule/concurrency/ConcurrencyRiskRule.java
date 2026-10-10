package com.example.coderiskanalyzer.production.rule.concurrency;

import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyAssessment;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyOutcome;

public interface ConcurrencyRiskRule extends RiskRule {
    List<ConcurrencyAssessment> assess(AnalysisContext context);
    Finding finding(ConcurrencyAssessment assessment);

    @Override
    default List<Finding> analyze(AnalysisContext context) {
        return assess(context).stream().filter(item -> item.outcome() != ConcurrencyOutcome.SAFE_BY_PROOF)
                .map(this::finding).toList();
    }
}
