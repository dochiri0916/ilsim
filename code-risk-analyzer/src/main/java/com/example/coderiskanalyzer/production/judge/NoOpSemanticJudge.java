package com.example.coderiskanalyzer.production.judge;

import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Finding;

public class NoOpSemanticJudge implements SemanticJudge {
    @Override
    public JudgeResult judge(Finding candidate, Map<String, Object> evidence) {
        return new JudgeResult(false, false, 0, "Semantic review is disabled.");
    }
}
