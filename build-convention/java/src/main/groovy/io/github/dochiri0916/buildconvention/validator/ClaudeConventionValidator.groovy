package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import org.gradle.api.Project

@Deprecated
final class ClaudeConventionValidator {

    private ClaudeConventionValidator() {
    }

    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        return JavaSourceArchitectureValidator.validate(project, convention)
    }
}
