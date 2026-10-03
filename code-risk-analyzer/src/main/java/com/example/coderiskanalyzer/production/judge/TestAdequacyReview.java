package com.example.coderiskanalyzer.production.judge;

/** Advisory JEV opinion on a test obligation; it never changes the static coverage status. */
public record TestAdequacyReview(boolean reviewed, double adequateProbability, String explanation) {
    public static TestAdequacyReview notReviewed() {
        return new TestAdequacyReview(false, 0, "Semantic review is disabled.");
    }
}
