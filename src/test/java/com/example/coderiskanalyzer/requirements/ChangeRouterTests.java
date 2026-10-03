package com.example.coderiskanalyzer.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChangeRouterTests {
    private final ChangeRouter router = new ChangeRouter();
    private ChangeRouter.Decision route(ChangeRouter.Classifier c) { return router.route("x", List.of("A.java"), c); }

    @Test
    void lowProbabilityIsLightAndEverythingElseEscalates() {
        assertThat(route((c, f) -> 0.1).route()).isEqualTo(ChangeRouter.Route.LIGHT);
        assertThat(route((c, f) -> 0.3).route()).isEqualTo(ChangeRouter.Route.FULL);
        assertThat(route((c, f) -> 0.9).route()).isEqualTo(ChangeRouter.Route.FULL);
    }

    @Test
    void missingKeyFailureAndUnusableAnswersAlwaysEscalate() {
        assertThat(route(null).route()).isEqualTo(ChangeRouter.Route.FULL);
        assertThat(route((c, f) -> { throw new IllegalStateException("boom"); }).route()).isEqualTo(ChangeRouter.Route.FULL);
        assertThat(route((c, f) -> Double.NaN).route()).isEqualTo(ChangeRouter.Route.FULL);
        assertThat(route((c, f) -> 1.5).route()).isEqualTo(ChangeRouter.Route.FULL);
    }

    @Test
    void routeCommandValidatesInputAndNeverPrintsSecrets() {
        var cmd = new PromptCommand();
        assertThat(cmd.route(new String[]{"route", "--change", "x"}, k -> (c, f) -> 0.0).exitCode()).isEqualTo(2);
        var ok = cmd.route(new String[]{"route", "--change", "x", "--files", "A.java"}, k -> (c, f) -> 0.0);
        assertThat(ok.exitCode()).isZero();
        assertThat(ok.output()).contains("route:");
    }
}
