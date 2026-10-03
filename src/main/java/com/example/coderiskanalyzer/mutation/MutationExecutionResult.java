package com.example.coderiskanalyzer.mutation;

import java.nio.file.Path;
import java.util.List;

public record MutationExecutionResult(Outcome outcome, List<MutationEvidence> mutations,
                                      String diagnostic, int processExitCode, Path reportPath) {
    public MutationExecutionResult {
        mutations = List.copyOf(mutations);
    }

    public enum Outcome { MUTATION_COMPLETED, UNSUPPORTED_BUILD_SYSTEM, PIT_NOT_CONFIGURED,
        BUILD_FAILED, TESTS_FAILED_BEFORE_MUTATION, MUTATION_EXECUTION_FAILED, TIMED_OUT }
}
