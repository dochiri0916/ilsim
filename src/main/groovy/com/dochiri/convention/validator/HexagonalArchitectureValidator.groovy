package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.nio.charset.StandardCharsets

class HexagonalArchitectureValidator {
    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        List<String> violations = []

        SourceInspector.collectMainSourceFiles(project).each { File file ->
            String content = file.getText(StandardCharsets.UTF_8.name())
            String packageName = SourceInspector.extractPackageName(content)
            String layer = detectLayer(packageName, convention)
            if (layer == null) {
                return
            }

            List<String> imports = SourceInspector.extractImports(content)
            imports.each { String imported ->
                if (layer == 'domain' && (SourceInspector.isInLayer(imported, convention.applicationPackageSegment)
                        || SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)
                        || SourceInspector.isInLayer(imported, convention.presentationPackageSegment))) {
                    violations.add("${project.relativePath(file)} imports ${imported} (domain -> application/infrastructure/presentation forbidden)")
                }

                if (layer == 'application' && (SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)
                        || SourceInspector.isInLayer(imported, convention.presentationPackageSegment))) {
                    violations.add("${project.relativePath(file)} imports ${imported} (application -> infrastructure/presentation forbidden)")
                }
            }
        }

        return violations
    }

    private static String detectLayer(String packageName, HexagonalConventionExtension convention) {
        if (SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
            return 'domain'
        }
        if (SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)) {
            return 'application'
        }
        if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)) {
            return 'infrastructure'
        }
        if (SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)) {
            return 'presentation'
        }
        return null
    }
}
