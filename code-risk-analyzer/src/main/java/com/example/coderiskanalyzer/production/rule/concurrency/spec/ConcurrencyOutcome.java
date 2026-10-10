package com.example.coderiskanalyzer.production.rule.concurrency.spec;

/** Specification assessment, separate from the existing finding status and CI policy. */
public enum ConcurrencyOutcome {
    DEFINITE_PATTERN, POTENTIAL_RISK, REQUIRES_VALIDATION, UNKNOWN, SAFE_BY_PROOF
}
