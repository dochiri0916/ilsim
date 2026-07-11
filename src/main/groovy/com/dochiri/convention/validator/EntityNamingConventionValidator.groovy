package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.JavaSourceAstInspector
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

class EntityNamingConventionValidator {
    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        List<String> violations = []
        List<File> javaFiles = SourceInspector.collectMainSourceFiles(project).findAll { File file ->
            file.name.endsWith('.java')
        }

        JavaSourceAstInspector.inspectAll(javaFiles).values().each { inspection ->
            if (!inspection.valid) {
                violations.add(
                        "${project.relativePath(inspection.file)} could not be parsed as Java source: "
                                + inspection.errors.join('; ')
                )
                return
            }

            File file = inspection.file
            JavaSourceAstInspector.TypeModel type = inspection.primaryType()
            String packageName = inspection.packageName
            boolean inDomainLayer = SourceInspector.isInLayer(packageName, convention.domainPackageSegment)
            boolean entityClass = type.annotation('Entity') != null

            if (convention.enforceDomainEntitySeparation && inDomainLayer && entityClass) {
                violations.add("${project.relativePath(file)} uses @Entity in domain package (separate domain model from persistence entity)")
            }

            if (!entityClass) {
                return
            }

            String className = type.simpleName

            if (!className.endsWith('Entity')) {
                violations.add("${project.relativePath(file)} JPA entity '${className}' must end with Entity")
            }

            JavaSourceAstInspector.AnnotationModel table = type.annotation('Table')
            String tableName = table?.arguments?.get('name')
            if (convention.requireTableAnnotation && !hasNonBlankTableName(tableName)) {
                violations.add("${project.relativePath(file)} entity '${className}' must declare @Table(name = \"...\")")
                return
            }
        }

        return violations
    }

    private static boolean hasNonBlankTableName(String tableName) {
        if (tableName == null) {
            return false
        }
        String unquoted = tableName.strip().replaceAll(/^['\"]|['\"]$/, '')
        return !unquoted.isBlank()
    }

}
