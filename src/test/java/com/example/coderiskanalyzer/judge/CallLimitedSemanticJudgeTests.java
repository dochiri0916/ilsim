package com.example.coderiskanalyzer.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.judge.CallLimitedSemanticJudge;
import com.example.coderiskanalyzer.production.judge.JudgeResult;
import com.example.coderiskanalyzer.production.judge.SemanticJudge;
import com.example.coderiskanalyzer.production.judge.TestAdequacyReview;

class CallLimitedSemanticJudgeTests {
    @Test
    void skipsCallsBeyondLimitAcrossBothQuestions() {
        int[] delegated = {0};
        SemanticJudge delegate = new SemanticJudge() {
            public JudgeResult judge(Finding f, Map<String, Object> e) {
                delegated[0]++;
                return new JudgeResult(true, true, 0.95, "x");
            }
            public TestAdequacyReview reviewTestAdequacy(Finding f, Map<String, Object> e) {
                delegated[0]++;
                return new TestAdequacyReview(true, 0.9, "x");
            }
        };
        var judge = new CallLimitedSemanticJudge(delegate, 2);
        assertThat(judge.judge(null, Map.of()).reviewed()).isTrue();
        assertThat(judge.reviewTestAdequacy(null, Map.of()).reviewed()).isTrue();
        assertThat(judge.judge(null, Map.of()).reviewed()).isFalse();
        assertThat(judge.reviewTestAdequacy(null, Map.of()).reviewed()).isFalse();
        assertThat(delegated[0]).isEqualTo(2);
        assertThat(judge.skipped()).isEqualTo(2);
    }

    @Test
    void zeroLimitSkipsEverything() {
        var judge = new CallLimitedSemanticJudge(new SemanticJudge() {
            public JudgeResult judge(Finding f, Map<String, Object> e) { throw new AssertionError(); }
        }, 0);
        assertThat(judge.judge(null, Map.of()).reviewed()).isFalse();
        assertThat(judge.skipped()).isEqualTo(1);
    }
}
