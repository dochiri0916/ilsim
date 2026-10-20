package com.example.coderiskanalyzer.production.judge;

import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Finding;

public class CallLimitedSemanticJudge implements SemanticJudge {
    private final SemanticJudge delegate;
    private final int maxCalls;
    private int calls;
    private int skipped;

    public CallLimitedSemanticJudge(SemanticJudge delegate, int maxCalls) {
        this.delegate = delegate;
        this.maxCalls = maxCalls;
    }

    @Override
    public JudgeResult judge(Finding candidate, Map<String, Object> evidence) {
        if (!tryAcquire()) return new JudgeResult(false, false, 0, "JEV call limit reached.");
        return delegate.judge(candidate, evidence);
    }

    @Override
    public JudgeResult reviewRisk(Finding candidate, Map<String, Object> evidence) {
        if (!tryAcquire()) return new JudgeResult(false, false, 0, "JEV call limit reached.");
        return delegate.reviewRisk(candidate, evidence);
    }

    @Override
    public TestAdequacyReview reviewTestAdequacy(Finding candidate, Map<String, Object> evidence) {
        if (!tryAcquire()) return new TestAdequacyReview(false, 0, "JEV call limit reached.");
        return delegate.reviewTestAdequacy(candidate, evidence);
    }

    private boolean tryAcquire() {
        if (calls >= maxCalls) {
            skipped++;
            return false;
        }
        calls++;
        return true;
    }

    public int skipped() {
        return skipped;
    }
}
