package com.example.coderiskanalyzer.production.judge;

public record JudgeResult(boolean reviewed, boolean likelyDuplicateRisk, double probability, String explanation) {
}
