package com.example.coderiskanalyzer.production.judge.jev;

import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.judge.JudgeResult;
import com.example.coderiskanalyzer.production.judge.SemanticJudge;
import com.example.coderiskanalyzer.production.judge.TestAdequacyReview;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;

/** JEV receives only the candidate summary and structured evidence, never the source file. */
public class JevSemanticJudge implements SemanticJudge, AutoCloseable {

    private static final NoulQuestion DUPLICATE_SIDE_EFFECT = NoulQuestion.of(
            "duplicate_side_effect",
            "Can processing the same message more than once cause the same business side effect to run more than once?")
            .withCriteria("Yes, the evidence shows no deduplication or idempotency protection.",
                    "No, the evidence shows an effective deduplication or idempotency protection.");

    private static final NoulQuestion TEST_ADEQUACY = NoulQuestion.of(
            "test_adequacy",
            "Do the matching tests verify the required evidence of this test obligation, including the missing cases?")
            .withCriteria("Yes, the evidence shows the tests assert the required outcome for every case.",
                    "No, the evidence shows missing, partial or unverifiable assertions.");

    /** Each question asks whether the evidence shows the risk, never assumes protection that is not in the evidence. */
    private static NoulQuestion riskQuestion(String id, String question, String yes) {
        return NoulQuestion.of(id, question).withCriteria(yes,
                "No, the evidence shows an effective protection, or does not show the risk.");
    }

    private static final Map<String, NoulQuestion> RISK_QUESTIONS = Map.of(
            "IDEM-002", riskQuestion("external_mutation_without_key",
                    "Can a retry repeat this external mutation because no idempotency key is sent with it?",
                    "Yes, the evidence shows a retried external mutation without any stable idempotency key."),
            "IDEM-003", riskQuestion("retry_repeats_side_effect",
                    "Can a retry of this operation repeat its persistent or external side effect?",
                    "Yes, the evidence shows a retryable side effect with no deduplication or natural idempotency."),
            "IDEM-004", riskQuestion("handler_without_stable_key",
                    "Can this handler repeat its side effect because it has no stable business key to deduplicate on?",
                    "Yes, the evidence shows side effects without a stable business key."),
            "CON-001", riskQuestion("check_then_act_race",
                    "Can two concurrent executions both pass the check and then both perform the mutation?",
                    "Yes, the evidence shows no lock, unique constraint or atomic operation covering the check and act."),
            "CON-002", riskQuestion("lost_update",
                    "Can a concurrent update be overwritten because the value is changed from previously read state?",
                    "Yes, the evidence shows no version check, lock or atomic update."));

    public static boolean supportsRiskReview(String ruleId) {
        return RISK_QUESTIONS.containsKey(ruleId);
    }

    private final TypeSafeJevClient client;

    public JevSemanticJudge(String apiKey) {
        this.client = TypeSafeJevClient.builder().apiKey(apiKey).build();
    }

    @Override
    public JudgeResult judge(Finding candidate, Map<String, Object> evidence) {
        var state = Map.of("ruleId", candidate.ruleId(), "category", candidate.category().name(),
                "finding", candidate.message(), "evidence", evidence);
        double probability = client.evaluate(state, DUPLICATE_SIDE_EFFECT).answer(DUPLICATE_SIDE_EFFECT).probability();
        return new JudgeResult(true, probability >= 0.5, probability,
                "JEV estimated duplicate-side-effect risk probability at " + probability + ".");
    }

    @Override
    public JudgeResult reviewRisk(Finding candidate, Map<String, Object> evidence) {
        NoulQuestion question = RISK_QUESTIONS.get(candidate.ruleId());
        if (question == null) return new JudgeResult(false, false, 0, "No JEV question for " + candidate.ruleId() + ".");
        var state = Map.of("ruleId", candidate.ruleId(), "category", candidate.category().name(),
                "finding", candidate.message(), "evidence", evidence);
        double probability = client.evaluate(state, question).answer(question).probability();
        return new JudgeResult(true, probability >= 0.5, probability,
                "JEV estimated " + candidate.ruleId() + " risk probability at " + probability + ".");
    }

    @Override
    public TestAdequacyReview reviewTestAdequacy(Finding candidate, Map<String, Object> evidence) {
        var state = Map.of("ruleId", candidate.ruleId(), "category", candidate.category().name(),
                "finding", candidate.message(), "evidence", evidence);
        double probability = client.evaluate(state, TEST_ADEQUACY).answer(TEST_ADEQUACY).probability();
        return new TestAdequacyReview(true, probability,
                "JEV estimated test adequacy probability at " + probability + ".");
    }

    @Override
    public void close() {
        client.close();
    }
}
