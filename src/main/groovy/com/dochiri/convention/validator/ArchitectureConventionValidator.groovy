package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.Project

final class ArchitectureConventionValidator {

    private ArchitectureConventionValidator() {
    }

    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        return JavaSourceArchitectureValidator.validate(project, convention)
    }
}
