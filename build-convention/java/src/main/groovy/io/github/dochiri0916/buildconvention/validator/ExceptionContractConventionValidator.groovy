package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import org.gradle.api.Project

/** Each concrete failure belongs to the sealed hierarchy owned by its context and layer. */
final class ExceptionContractConventionValidator {
    private ExceptionContractConventionValidator() {}

    static List<String> validate(Project project, List<File> sourceFiles,
                                 AggregateBoundaryConventionValidator.Analysis analysis,
                                 HexagonalConventionExtension convention) {
        Map<String, Map> declarations = [:]
        sourceFiles.each { File file ->
            def inspection = analysis.inspectionFor(file)
            if (inspection == null || !inspection.valid) return
            Closure collectType
            collectType = { type, String owner ->
                String name = owner == null ? type.qualifiedName : owner + '.' + type.simpleName
                declarations[name] = [name: name, type: type, inspection: inspection, file: file]
                type.nestedTypes.each { nested -> collectType(nested, name) }
            }
            inspection.types.each { type -> collectType(type, null) }
        }
        List<String> violations = []
        declarations.values().each { declaration ->
            def type = declaration.type
            def inspection = declaration.inspection
            String layer = exceptionLayer(inspection.packageName, convention)
            if (layer == null || type.kind != 'CLASS' || !type.simpleName.endsWith('Exception')) return
            // Shared storage failure has no business meaning and preserves the original technical cause.
            if (inspection.packageName.endsWith('.global.application.exception')
                    && declaration.name == inspection.packageName + '.RepositoryOperationException') return
            String path = project.relativePath(declaration.file)
            String suffix = layer == 'domain' ? 'DomainException' : 'ApplicationException'
            boolean sharedDomain = inspection.packageName.endsWith('.global.domain.exception')
            if (type.modifiers.contains('ABSTRACT')) {
                if (!type.modifiers.contains('SEALED') || resolve(type.superType, declaration, declarations) != 'java.lang.RuntimeException'
                        || !type.simpleName.endsWith(suffix) || type.simpleName == suffix) {
                    violations.add("${path} exception base '${type.simpleName}' must be abstract sealed '{Context}${suffix}' extending RuntimeException")
                }
                def constructors = type.methods.findAll { it.constructor }
                if (constructors.empty || constructors.any { !it.modifiers.contains('PROTECTED') }) {
                    violations.add("${path} exception base '${type.simpleName}' must keep constructors protected")
                }
                if (sharedDomain && type.simpleName != 'GlobalDomainException') {
                    violations.add("${path} shared VO exception base must be named GlobalDomainException")
                }
                Set<String> permitted = type.permittedTypes.collect { resolve(it, declaration, declarations) }.toSet()
                permitted.each { name ->
                    def child = declarations[name]
                    if (child == null || child.inspection.packageName != inspection.packageName
                            || !child.type.modifiers.contains('FINAL')
                            || resolve(child.type.superType, child, declarations) != declaration.name) {
                        violations.add("${path} exception base '${type.simpleName}' permits must name existing final direct exceptions in its own context layer: '${name}'")
                    }
                }
                declarations.values().findAll { child ->
                    resolve(child.type.superType, child, declarations) == declaration.name
                }.each { child ->
                    if (!permitted.contains(child.name)) {
                        violations.add("${path} exception base '${type.simpleName}' permits must include concrete exception '${child.name}'")
                    }
                }
                return
            }
            if (!type.modifiers.contains('FINAL')) {
                violations.add("${path} concrete ${layer} exception '${type.simpleName}' must be final")
            }
            String parentName = resolve(type.superType, declaration, declarations)
            def parent = declarations[parentName]
            if (parent == null || parent.inspection.packageName != inspection.packageName
                    || !parent.type.simpleName.endsWith(suffix) || parent.type.simpleName == suffix
                    || (sharedDomain && parent.type.simpleName != 'GlobalDomainException')
                    || !parent.type.modifiers.contains('ABSTRACT') || !parent.type.modifiers.contains('SEALED')) {
                violations.add("${path} concrete ${layer} exception '${type.simpleName}' must extend its own context's abstract sealed '{Context}${suffix}'")
            }
        }
        return violations
    }

    private static String resolve(String name, Map declaration, Map<String, Map> declarations) {
        if (name == null) return null
        def inspection = declaration.inspection
        if (declarations.containsKey(name)) return name
        String scope = declaration.name
        while (scope.startsWith(inspection.packageName + '.')) {
            String candidate = scope + '.' + name
            if (declarations.containsKey(candidate)) return candidate
            scope = scope.substring(0, scope.lastIndexOf('.'))
        }
        String imported = inspection.imports.find { it.endsWith('.' + name) }
        if (imported != null) return imported
        String local = inspection.packageName + '.' + name
        if (declarations.containsKey(local)) return local
        if (name.contains('.')) return name
        return name == 'RuntimeException' ? 'java.lang.RuntimeException' : local
    }

    private static String exceptionLayer(String packageName, HexagonalConventionExtension convention) {
        if (packageName.endsWith(".${convention.domainPackageSegment}.exception")) return 'domain'
        if (packageName.endsWith(".${convention.applicationPackageSegment}.exception")) return 'application'
        return null
    }
}
