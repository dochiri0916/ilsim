package com.example.coderiskanalyzer.production.judge;

public record TestAdequacyReview(boolean reviewed, double adequateProbability, String explanation) {
    public static TestAdequacyReview notReviewed() {
        return new TestAdequacyReview(false, 0, "Semantic review is disabled.");
    }
}
