package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.nio.charset.StandardCharsets

class EntityNamingConventionValidator {
    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        List<String> violations = []
        Set<String> entityExceptions = new HashSet<>(convention.entitySingularNameExceptions ?: [])
        Set<String> tableExceptions = new HashSet<>(convention.pluralTableNameExceptions ?: [])

        SourceInspector.collectMainSourceFiles(project).findAll { file ->
            file.name.endsWith('.java')
        }.each { File file ->
            String content = file.getText(StandardCharsets.UTF_8.name())
            String packageName = SourceInspector.extractPackageName(content)
            boolean inDomainLayer = SourceInspector.isInLayer(packageName, convention.domainPackageSegment)
            boolean entityClass = SourceInspector.isEntityClass(content)

            if (convention.enforceDomainEntitySeparation && inDomainLayer && entityClass) {
                violations.add("${project.relativePath(file)} uses @Entity in domain package (separate domain model from persistence entity)")
            }

            if (!entityClass) {
                return
            }

            String className = SourceInspector.extractClassName(content)
            if (className == null) {
                violations.add("${project.relativePath(file)} has @Entity but class name was not parsed")
                return
            }

            if (isLikelyPluralEntityName(className, entityExceptions)) {
                violations.add("${project.relativePath(file)} entity '${className}' looks plural (entity name must be singular)")
            }

            String tableName = SourceInspector.extractTableName(content)
            if (convention.requireTableAnnotation && tableName == null) {
                violations.add("${project.relativePath(file)} entity '${className}' must declare @Table(name = \"...\")")
                return
            }

            if (tableName != null && !isLikelyPluralTableName(tableName, tableExceptions)) {
                violations.add("${project.relativePath(file)} table '${tableName}' looks singular (table name must be plural)")
            }
        }

        return violations
    }

    private static boolean isLikelyPluralEntityName(String className, Set<String> exceptions) {
        if (exceptions.contains(className)) {
            return false
        }
        String normalized = className.toLowerCase(Locale.ROOT)
        return normalized.endsWith('s') && !normalized.endsWith('ss')
    }

    private static boolean isLikelyPluralTableName(String tableName, Set<String> exceptions) {
        if (exceptions.contains(tableName)) {
            return true
        }
        String normalized = tableName.toLowerCase(Locale.ROOT)
        return normalized.endsWith('s')
    }
}
