package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.support.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.support.SourceInspector
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

            // Value object records are created with their canonical constructor; only classes need static factories.
            inspection.types.each { JavaSourceAstInspector.TypeModel type ->
                if (type.kind != 'CLASS') {
                    return
                }

                String className = type.simpleName
                if (className.endsWith('Exception') || className.endsWith('Service')) {
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
            constructor.modifiers.contains('PUBLIC') || constructor.modifiers.contains('PROTECTED')
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
