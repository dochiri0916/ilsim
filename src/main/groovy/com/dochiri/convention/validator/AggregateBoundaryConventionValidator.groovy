package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.JavaSourceAstInspector
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.util.regex.Pattern

class AggregateBoundaryConventionValidator {
    private static final Pattern TYPE_TOKEN = Pattern.compile('[A-Za-z_$][A-Za-z0-9_$]*')
    private static final Pattern AGGREGATE_MUTATION_METHOD = Pattern.compile(
            '(?:create|delete|update|insert|persist|remove|save|upsert)[A-Za-z0-9_]*'
    )

    static Analysis analyze(Project project, HexagonalConventionExtension convention) {
        List<File> javaFiles = SourceInspector.collectMainSourceFiles(project)
                .findAll { file -> file.name.endsWith('.java') }
                .sort { left, right -> project.relativePath(left) <=> project.relativePath(right) }
        Map<String, JavaSourceAstInspector.Inspection> inspectionsByPath = new LinkedHashMap<>()
        List<String> violations = []

        Map<String, JavaSourceAstInspector.Inspection> parsedInspections =
                JavaSourceAstInspector.inspectAll(javaFiles)
        javaFiles.each { file ->
            JavaSourceAstInspector.Inspection inspection = parsedInspections.get(normalizedPath(file))
            inspectionsByPath.put(normalizedPath(file), inspection)
            if (!inspection.valid) {
                violations.add(
                        "${project.relativePath(file)} could not be parsed as Java source: ${inspection.errors.join('; ')}"
                )
            }
        }

        Map<String, TypeEntry> domainTypes = collectDomainTypes(inspectionsByPath.values(), convention)
        Map<String, Set<String>> repositoryRoots = new LinkedHashMap<>()
        inspectionsByPath.values().findAll { inspection -> inspection.valid }.each { inspection ->
            inspection.types.findAll { type ->
                isRepositoryPort(inspection.packageName, type, convention)
            }.each { repositoryType ->
                Set<String> roots = inferRepositoryRoots(repositoryType, inspection, domainTypes)
                repositoryRoots.put(repositoryType.qualifiedName, roots)
                if (roots.isEmpty()) {
                    violations.add(
                            "${project.relativePath(inspection.file)} repository port '${repositoryType.simpleName}' "
                                    + 'must expose one aggregate root in a mutation parameter or return type'
                    )
                } else if (roots.size() > 1) {
                    String rootNames = roots.collect { root -> simpleName(root) }.sort().join(', ')
                    violations.add(
                            "${project.relativePath(inspection.file)} repository port '${repositoryType.simpleName}' "
                                    + "must manage exactly one aggregate root but references: ${rootNames}"
                    )
                }
            }
        }

        Set<String> aggregateRoots = repositoryRoots.values().flatten().toSet()
        domainTypes.values().each { entry ->
            Set<String> referencedRoots = entry.type.memberTypes.collectMany { memberType ->
                resolveKnownTypes(memberType, entry.inspection, domainTypes.keySet())
            }.findAll { referencedType ->
                aggregateRoots.contains(referencedType)
                        && referencedType != entry.type.qualifiedName
                        && sameBoundedContext(
                                entry.type.qualifiedName,
                                referencedType,
                                convention.domainPackageSegment
                        )
            }.toSet()
            referencedRoots.sort().each { referencedRoot ->
                violations.add(
                        "${project.relativePath(entry.inspection.file)} domain must reference aggregate root "
                                + "'${simpleName(referencedRoot)}' through an identifier VO instead of a direct object reference"
                )
            }
        }

        return new Analysis(inspectionsByPath, repositoryRoots, violations)
    }

    private static Map<String, TypeEntry> collectDomainTypes(
            Collection<JavaSourceAstInspector.Inspection> inspections,
            HexagonalConventionExtension convention
    ) {
        Map<String, TypeEntry> domainTypes = new LinkedHashMap<>()
        inspections.findAll { inspection ->
            inspection.valid && SourceInspector.isInLayer(
                    inspection.packageName,
                    "${convention.domainPackageSegment}.model"
            )
        }.each { inspection ->
            inspection.types.each { type ->
                domainTypes.put(type.qualifiedName, new TypeEntry(inspection, type))
            }
        }
        return domainTypes
    }

    private static boolean isRepositoryPort(
            String packageName,
            JavaSourceAstInspector.TypeModel type,
            HexagonalConventionExtension convention
    ) {
        return type.kind == 'INTERFACE'
                && type.simpleName.endsWith('RepositoryPort')
                && SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)
                && packageName.contains('.port.out')
    }

    private static Set<String> inferRepositoryRoots(
            JavaSourceAstInspector.TypeModel repositoryType,
            JavaSourceAstInspector.Inspection inspection,
            Map<String, TypeEntry> domainTypes
    ) {
        Set<String> mutationRoots = repositoryType.methods.findAll { method ->
            AGGREGATE_MUTATION_METHOD.matcher(method.name).matches()
        }.collectMany { method ->
            method.parameterTypes.collectMany { parameterType ->
                resolveKnownTypes(parameterType, inspection, domainTypes.keySet())
            }
        }.findAll { typeName -> !isIdentifierValueObject(typeName) }.toSet()
        if (!mutationRoots.isEmpty()) {
            return mutationRoots
        }

        return repositoryType.methods.collectMany { method ->
            resolveKnownTypes(method.returnType, inspection, domainTypes.keySet())
        }.findAll { typeName -> !isIdentifierValueObject(typeName) }.toSet()
    }

    private static Set<String> resolveKnownTypes(
            String typeSyntax,
            JavaSourceAstInspector.Inspection inspection,
            Set<String> knownTypes
    ) {
        Set<String> resolved = []
        knownTypes.findAll { knownType -> typeSyntax.contains(knownType) }.each { knownType ->
            resolved.add(knownType)
        }

        Map<String, String> explicitImports = inspection.imports
                .findAll { imported -> !imported.endsWith('.*') }
                .collectEntries { imported -> [(simpleName(imported)): imported] }
        Set<String> wildcardPackages = inspection.imports
                .findAll { imported -> imported.endsWith('.*') }
                .collect { imported -> imported.substring(0, imported.length() - 2) }
                .toSet()
        def matcher = TYPE_TOKEN.matcher(typeSyntax)
        while (matcher.find()) {
            String token = matcher.group()
            String importedType = explicitImports.get(token)
            if (importedType != null && knownTypes.contains(importedType)) {
                resolved.add(importedType)
            }
            String samePackageType = inspection.packageName.isBlank()
                    ? token
                    : "${inspection.packageName}.${token}"
            if (knownTypes.contains(samePackageType)) {
                resolved.add(samePackageType)
            }
            wildcardPackages.each { wildcardPackage ->
                String wildcardType = "${wildcardPackage}.${token}"
                if (knownTypes.contains(wildcardType)) {
                    resolved.add(wildcardType)
                }
            }
        }
        return resolved
    }

    private static boolean sameBoundedContext(String leftType, String rightType, String domainSegment) {
        String leftContext = boundedContextName(leftType, domainSegment)
        String rightContext = boundedContextName(rightType, domainSegment)
        return leftContext != null && leftContext == rightContext
    }

    private static String boundedContextName(String qualifiedType, String layerSegment) {
        List<String> typeSegments = qualifiedType.tokenize('.')
        String firstLayerSegment = layerSegment.tokenize('.').first()
        int layerIndex = typeSegments.indexOf(firstLayerSegment)
        return layerIndex > 0 ? typeSegments.get(layerIndex - 1) : null
    }

    private static boolean isIdentifierValueObject(String qualifiedType) {
        return simpleName(qualifiedType).endsWith('Id')
    }

    private static String simpleName(String qualifiedType) {
        return qualifiedType.substring(qualifiedType.lastIndexOf('.') + 1)
    }

    private static String normalizedPath(File file) {
        return file.absoluteFile.toPath().normalize().toString()
    }

    static final class Analysis {
        final Map<String, JavaSourceAstInspector.Inspection> inspectionsByPath
        final Map<String, Set<String>> repositoryRoots
        final List<String> violations

        private Analysis(
                Map<String, JavaSourceAstInspector.Inspection> inspectionsByPath,
                Map<String, Set<String>> repositoryRoots,
                List<String> violations
        ) {
            this.inspectionsByPath = Collections.unmodifiableMap(new LinkedHashMap<>(inspectionsByPath))
            this.repositoryRoots = repositoryRoots.collectEntries { repositoryType, roots ->
                [(repositoryType): Collections.unmodifiableSet(new LinkedHashSet<>(roots))]
            }.asImmutable()
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations))
        }

        String aggregateRootForRepository(File sourceFile, String repositoryType) {
            String normalizedRepositoryType = repositoryType
                    .replaceAll(/<.*>/, '')
                    .replaceAll(/\[\]/, '')
                    .trim()
            if (repositoryRoots.containsKey(normalizedRepositoryType)) {
                return singleRoot(repositoryRoots.get(normalizedRepositoryType))
            }

            JavaSourceAstInspector.Inspection inspection = inspectionsByPath.get(normalizedPath(sourceFile))
            String repositorySimpleName = simpleName(normalizedRepositoryType)
            if (inspection != null && inspection.valid) {
                String importedType = inspection.imports.find { imported ->
                    !imported.endsWith('.*') && simpleName(imported) == repositorySimpleName
                }
                if (importedType != null && repositoryRoots.containsKey(importedType)) {
                    return singleRoot(repositoryRoots.get(importedType))
                }
            }

            Collection<Map.Entry<String, Set<String>>> candidates = repositoryRoots.entrySet().findAll { entry ->
                simpleName(entry.key) == repositorySimpleName
            }
            if (candidates.size() == 1) {
                return singleRoot(candidates.first().value)
            }
            return null
        }

        JavaSourceAstInspector.Inspection inspectionFor(File sourceFile) {
            return inspectionsByPath.get(normalizedPath(sourceFile))
        }

        private static String singleRoot(Set<String> roots) {
            return roots.size() == 1 ? roots.first() : null
        }
    }

    private static final class TypeEntry {
        final JavaSourceAstInspector.Inspection inspection
        final JavaSourceAstInspector.TypeModel type

        private TypeEntry(
                JavaSourceAstInspector.Inspection inspection,
                JavaSourceAstInspector.TypeModel type
        ) {
            this.inspection = inspection
            this.type = type
        }
    }
}
