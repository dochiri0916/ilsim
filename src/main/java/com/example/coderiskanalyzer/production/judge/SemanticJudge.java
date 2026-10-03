package com.example.coderiskanalyzer.production.judge;

import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Finding;

public interface SemanticJudge {
    JudgeResult judge(Finding candidate, Map<String, Object> evidence);

    /** Advisory opinion for rules other than IDEM-001; it must never change the static status. */
    default JudgeResult reviewRisk(Finding candidate, Map<String, Object> evidence) {
        return new JudgeResult(false, false, 0, "Semantic review is disabled.");
    }

    default TestAdequacyReview reviewTestAdequacy(Finding candidate, Map<String, Object> evidence) {
        return TestAdequacyReview.notReviewed();
    }
}
