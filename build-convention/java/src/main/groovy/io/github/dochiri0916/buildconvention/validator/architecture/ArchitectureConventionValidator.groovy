package io.github.dochiri0916.buildconvention.validator.architecture

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import org.gradle.api.Project

final class ArchitectureConventionValidator {

    private ArchitectureConventionValidator() {
    }

    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        return JavaSourceArchitectureValidator.validate(project, convention)
    }
}
