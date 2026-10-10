package io.github.dochiri0916.buildconvention.validator.domain.factory

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.source.java.SourceInspector
import org.gradle.api.Project

class DomainStaticFactoryValidator {
    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        if (!convention.enforceDomainStaticFactoryMethod) {
            return []
        }

        List<String> violations = []

        List<File> javaFiles = SourceInspector.collectMainSourceFiles(project).findAll { file ->
            file.name.endsWith('.java')
        }
        Collection<JavaSourceAstInspector.Inspection> inspections = JavaSourceAstInspector.inspectAll(javaFiles).values()
        inspections.each { inspection ->
            if (!inspection.valid) {
                violations.add(
                        "${project.relativePath(inspection.file)} could not be parsed as Java source: "
                                + inspection.errors.join('; ')
                )
                return
            }

            File file = inspection.file
            String packageName = inspection.packageName
            if (!SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
                return
            }

            // Events and Outbox records keep their canonical constructors; value objects are classes.
            inspection.types.each { JavaSourceAstInspector.TypeModel type ->
                if (type.kind == 'RECORD' && (packageName.contains('.domain.vo') || packageName.endsWith('.global.domain'))) {
                    violations.add("${project.relativePath(file)} domain Value Object '${type.simpleName}' must be a final class with a private constructor")
                }
                if (type.kind != 'CLASS' || packageName.contains('.domain.outbox')) {
                    return
                }

                String className = type.simpleName
                if (className.endsWith('Exception') || className.endsWith('Service') || className.endsWith('Policy')) {
                    return
                }

                if (hasNonPrivateConstructor(type)) {
                    violations.add("${project.relativePath(file)} class '${className}' must not expose public/protected constructor (use static factory)")
                }

                if (!hasPrivateConstructor(type)) {
                    violations.add("${project.relativePath(file)} class '${className}' must declare a private constructor")
                }

                if (!hasPublicStaticFactoryMethod(type)) {
                    violations.add("${project.relativePath(file)} class '${className}' must declare at least one public static factory method returning '${className}'")
                }
            }
        }

        return violations
    }

    private static boolean hasNonPrivateConstructor(JavaSourceAstInspector.TypeModel type) {
        return constructors(type).any { constructor ->
            !constructor.modifiers.contains('PRIVATE')
        }
    }

    private static boolean hasPrivateConstructor(JavaSourceAstInspector.TypeModel type) {
        if (constructors(type).any { constructor -> constructor.modifiers.contains('PRIVATE') }) {
            return true
        }

        JavaSourceAstInspector.AnnotationModel lombokConstructor = type.annotation('NoArgsConstructor')
        String access = lombokConstructor?.arguments?.get('access')
        return access != null && access.replaceAll(/\s+/, '').endsWith('AccessLevel.PRIVATE')
    }

    private static boolean hasPublicStaticFactoryMethod(JavaSourceAstInspector.TypeModel type) {
        return type.methods.any { method ->
            !method.constructor
                    && method.modifiers.contains('PUBLIC')
                    && method.modifiers.contains('STATIC')
                    && rawSimpleType(method.returnType) == type.simpleName
                    && (method.name in ['of', 'generate', 'parse', 'empty', 'create', 'restore'])
        }
    }

    private static List<JavaSourceAstInspector.MethodModel> constructors(
            JavaSourceAstInspector.TypeModel type
    ) {
        return type.methods.findAll { method -> method.constructor }
    }

    private static String rawSimpleType(String type) {
        if (type == null) {
            return ''
        }
        String rawType = type.replaceAll(/<.*>/, '').replaceAll(/\[\]/, '').strip()
        return rawType.substring(rawType.lastIndexOf('.') + 1)
    }
}
