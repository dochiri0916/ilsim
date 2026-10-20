package com.example.coderiskanalyzer.production.judge;

import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Finding;

public interface SemanticJudge {
    JudgeResult judge(Finding candidate, Map<String, Object> evidence);

    default JudgeResult reviewRisk(Finding candidate, Map<String, Object> evidence) {
        return new JudgeResult(false, false, 0, "Semantic review is disabled.");
    }

    default TestAdequacyReview reviewTestAdequacy(Finding candidate, Map<String, Object> evidence) {
        return TestAdequacyReview.notReviewed();
    }
}
