package com.example.coderiskanalyzer.production.judge.jev;

import java.util.Map;

import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.judge.JudgeResult;
import com.example.coderiskanalyzer.production.judge.SemanticJudge;
import com.example.coderiskanalyzer.production.judge.TestAdequacyReview;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;

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

    private static NoulQuestion riskQuestion(String id, String question, String yes) {
        return NoulQuestion.of(id, question).withCriteria(yes,
                "No, the evidence shows an effective protection, or does not show the risk.");
    }

    private static final Map<String, NoulQuestion> RISK_QUESTIONS = Map.ofEntries(
            Map.entry("IDEM-002", riskQuestion("external_mutation_without_key",
                    "Can a retry repeat this external mutation because no idempotency key is sent with it?",
                    "Yes, the evidence shows a retried external mutation without any stable idempotency key.")),
            Map.entry("IDEM-003", riskQuestion("retry_repeats_side_effect",
                    "Can a retry of this operation repeat its persistent or external side effect?",
                    "Yes, the evidence shows a retryable side effect with no deduplication or natural idempotency.")),
            Map.entry("IDEM-004", riskQuestion("handler_without_stable_key",
                    "Can this handler repeat its side effect because it has no stable business key to deduplicate on?",
                    "Yes, the evidence shows side effects without a stable business key.")),
            Map.entry("CON-001", riskQuestion("check_then_act_race",
                    "Can two concurrent executions both pass the check and then both perform the mutation?",
                    "Yes, the evidence shows no lock, unique constraint or atomic operation covering the check and act.")),
            Map.entry("CON-002", riskQuestion("lost_update",
                    "Can a concurrent update be overwritten because the value is changed from previously read state?",
                    "Yes, the evidence shows no version check, lock or atomic update.")),
            Map.entry("CON-003", riskQuestion("shared_singleton_state",
                    "Can concurrent requests corrupt or leak state that this singleton bean keeps in a mutable field?",
                    "Yes, the evidence shows unsynchronized mutable state shared across concurrent calls.")),
            Map.entry("CON-004", riskQuestion("async_thread_context",
                    "Does this async method depend on caller-thread context (security, transaction, request or MDC) that is absent on the async thread?",
                    "Yes, the evidence shows caller-thread context used without being propagated.")),
            Map.entry("CON-005", riskQuestion("unsafe_double_checked_publication",
                    "Does this double-checked publication lack volatile visibility, a consistent monitor, or completed initialization? Review the stated missing evidence without assuming protection.",
                    "Yes, the evidence shows non-volatile publication or initialization after publication, or supports the reported unresolved protection risk.")),
            Map.entry("TX-001", riskQuestion("self_invocation_bypass",
                    "Does this internal call bypass the transactional proxy so the intended transaction settings are not applied?",
                    "Yes, the evidence shows a this-call to a method whose transaction settings matter and are skipped.")),
            Map.entry("TX-002", riskQuestion("async_transaction_boundary",
                    "Does work on the async thread assume it runs inside the caller's transaction?",
                    "Yes, the evidence shows async work that relies on the caller's uncommitted transaction or rollback.")),
            Map.entry("TX-003", riskQuestion("remote_call_in_transaction",
                    "Can this remote call leave the remote system and the database inconsistent, or hold the transaction open too long?",
                    "Yes, the evidence shows a remote call whose effect is not undone on rollback, or a long blocking call inside the transaction.")),
            Map.entry("TX-004", riskQuestion("partial_commit",
                    "Can these database writes partially commit so that business data becomes inconsistent?",
                    "Yes, the evidence shows related writes that are not covered by one transaction.")),
            Map.entry("TX-005", riskQuestion("swallowed_exception_commit",
                    "Can the caught exception let the transaction commit after only part of the work succeeded?",
                    "Yes, the evidence shows the exception is swallowed without rollback or rethrow.")),
            Map.entry("SEC-001", riskQuestion("sql_injection",
                    "Can untrusted input change the structure of this SQL statement?",
                    "Yes, the evidence shows input concatenated into SQL without binding or a strict allowlist.")),
            Map.entry("SEC-002", riskQuestion("command_injection",
                    "Can untrusted input change which program or arguments this process runs?",
                    "Yes, the evidence shows input reaching the command without a strict allowlist.")),
            Map.entry("SEC-003", riskQuestion("path_traversal",
                    "Can untrusted input make this file operation reach a path outside the intended directory?",
                    "Yes, the evidence shows no normalization and containment check before the file operation.")),
            Map.entry("SEC-004", riskQuestion("ssrf",
                    "Can untrusted input direct this outbound request to an unintended host?",
                    "Yes, the evidence shows the target host is not restricted to an allowlist.")),
            Map.entry("SEC-006", riskQuestion("object_authorization",
                    "Can a caller read or change another user's resource by passing its identifier?",
                    "Yes, the evidence shows the resource is loaded by a client identifier without an ownership or permission check on the call path.")),
            Map.entry("SEC-007", riskQuestion("tenant_isolation",
                    "Can this data access return or change rows of another tenant?",
                    "Yes, the evidence shows no tenant condition, filter or check on the call path.")),
            Map.entry("REL-001", riskQuestion("db_external_consistency",
                    "Can the database change and the external side effect diverge when one of them fails?",
                    "Yes, the evidence shows no outbox, compensation or after-commit handling.")));

    private static NoulQuestion question(String ruleId) {
        return RISK_QUESTIONS.get(ruleId);
    }

    public static boolean supportsRiskReview(String ruleId) {
        return question(ruleId) != null;
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
        NoulQuestion question = question(candidate.ruleId());
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
