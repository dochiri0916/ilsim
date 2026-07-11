package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.JavaSourceAstInspector
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.nio.charset.StandardCharsets

class HexagonalArchitectureValidator {
    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        List<String> violations = []
        String inboundAdapterPackageSegment = inboundAdapterPackageSegment(convention)
        List<File> sourceFiles = SourceInspector.collectMainSourceFiles(project)
        List<File> javaFiles = sourceFiles.findAll { file -> file.name.endsWith('.java') }
        Map<String, JavaSourceAstInspector.Inspection> parsedInspections =
                JavaSourceAstInspector.inspectAll(javaFiles)

        parsedInspections.values().each { inspection ->
            if (!inspection.valid) {
                violations.add(
                        "${project.relativePath(inspection.file)} could not be parsed as Java source: "
                                + inspection.errors.join('; ')
                )
                return
            }
            validateDependencies(
                    project,
                    inspection.file,
                    inspection.packageName,
                    inspection.allImports.toList(),
                    convention,
                    inboundAdapterPackageSegment,
                    violations
            )
        }

        sourceFiles.findAll { file -> file.name.endsWith('.groovy') }.each { File file ->
            String content = file.getText(StandardCharsets.UTF_8.name())
            String packageName = SourceInspector.extractPackageName(content)
            validateDependencies(
                    project,
                    file,
                    packageName,
                    SourceInspector.extractImports(content),
                    convention,
                    inboundAdapterPackageSegment,
                    violations
            )
        }

        return violations
    }

    private static void validateDependencies(
            Project project,
            File file,
            String packageName,
            List<String> imports,
            HexagonalConventionExtension convention,
            String inboundAdapterPackageSegment,
            List<String> violations
    ) {
        String layer = detectLayer(packageName, convention, inboundAdapterPackageSegment)
        if (layer == null) {
            return
        }

        imports.each { String imported ->
            if (layer == 'domain' && (SourceInspector.isInLayer(imported, convention.applicationPackageSegment)
                    || SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)
                    || SourceInspector.isInLayer(imported, inboundAdapterPackageSegment))) {
                violations.add("${project.relativePath(file)} imports ${imported} (domain -> application/adapter forbidden)")
            }

            if (layer == 'application' && (SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)
                    || SourceInspector.isInLayer(imported, inboundAdapterPackageSegment))) {
                violations.add("${project.relativePath(file)} imports ${imported} (application -> adapter forbidden)")
            }

            if (layer == 'adapter.in' && SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)) {
                violations.add("${project.relativePath(file)} imports ${imported} (adapter.in -> adapter.out forbidden)")
            }

            if (layer == 'adapter.out' && SourceInspector.isInLayer(imported, inboundAdapterPackageSegment)) {
                violations.add("${project.relativePath(file)} imports ${imported} (adapter.out -> adapter.in forbidden)")
            }
        }
    }

    private static String detectLayer(
            String packageName,
            HexagonalConventionExtension convention,
            String inboundAdapterPackageSegment
    ) {
        if (SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
            return 'domain'
        }
        if (SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)) {
            return 'application'
        }
        if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)) {
            return 'adapter.out'
        }
        if (SourceInspector.isInLayer(packageName, inboundAdapterPackageSegment)) {
            return 'adapter.in'
        }
        return null
    }

    private static String inboundAdapterPackageSegment(HexagonalConventionExtension convention) {
        if (convention.presentationPackageSegment?.startsWith('adapter.in.')) {
            return 'adapter.in'
        }
        return convention.presentationPackageSegment
    }
}
