package com.example.coderiskanalyzer.requirements;

import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;

/**
 * Chooses the light or full path. The classification can only escalate: LIGHT needs a successful JEV answer
 * below the threshold, and a missing key, failed call or uncertain answer always routes to FULL.
 */
public final class ChangeRouter {
    public enum Route { LIGHT, FULL }
    public record Decision(Route route, Double probability, String reason) {}

    static final double LIGHT_BELOW = 0.3;

    private static final NoulQuestion HIGH_RISK_CHANGE = NoulQuestion.of("high_risk_change",
            "Does this change touch money, stock, state transitions, retries, message handling, transactions or concurrent access?")
            .withCriteria("Yes, the change description or the file names indicate one of these areas.",
                    "No, the change is limited to presentation, wording, logging, simple reads or behavior-preserving refactoring.");

    /** Source of the probability; tests replace it so no remote call is needed. */
    public interface Classifier { double probability(String change, List<String> files); }

    public static Classifier jev(String apiKey) {
        return (change, files) -> {
            try (TypeSafeJevClient client = TypeSafeJevClient.builder().apiKey(apiKey).build()) {
                return client.evaluate(Map.of("change", change, "files", files), HIGH_RISK_CHANGE)
                        .answer(HIGH_RISK_CHANGE).probability();
            }
        };
    }

    public Decision route(String change, List<String> files, Classifier classifier) {
        if (classifier == null) return new Decision(Route.FULL, null, "JEV is unavailable (TYPESAFE_API_KEY missing); use the full path.");
        double probability;
        try {
            probability = classifier.probability(change, files);
        } catch (RuntimeException exception) {
            return new Decision(Route.FULL, null, "JEV call failed; use the full path.");
        }
        if (!Double.isFinite(probability) || probability < 0 || probability > 1)
            return new Decision(Route.FULL, null, "JEV returned an unusable answer; use the full path.");
        return probability < LIGHT_BELOW
                ? new Decision(Route.LIGHT, probability, "JEV found a low chance that this touches a high-risk area. The probability is advisory, not proof.")
                : new Decision(Route.FULL, probability, "JEV did not rule out a high-risk area.");
    }
}
