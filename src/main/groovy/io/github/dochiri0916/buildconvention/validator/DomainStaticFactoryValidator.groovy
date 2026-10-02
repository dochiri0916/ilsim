package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.support.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.support.SourceInspector
import org.gradle.api.Project

import java.util.regex.Pattern

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
        Map<String, JavaSourceAstInspector.Inspection> valueObjects = collectValueObjects(inspections, convention)
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
            violations.addAll(validateValueObjectInstantiation(project, inspection, valueObjects))
            if (!SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
                return
            }

            inspection.types.each { JavaSourceAstInspector.TypeModel type ->
                if (type.kind == 'RECORD' && valueObjects.containsKey(type.qualifiedName)
                        && !hasPublicStaticFactoryMethod(type, 'of')) {
                    violations.add("${project.relativePath(file)} value object '${type.simpleName}' must declare a public static 'of' factory method returning '${type.simpleName}'")
                }
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

    private static Map<String, JavaSourceAstInspector.Inspection> collectValueObjects(
            Collection<JavaSourceAstInspector.Inspection> inspections,
            HexagonalConventionExtension convention
    ) {
        Map<String, JavaSourceAstInspector.Inspection> valueObjects = new LinkedHashMap<>()
        inspections.findAll { inspection ->
            inspection.valid
                    && SourceInspector.isInLayer(inspection.packageName, convention.domainPackageSegment)
                    && !SourceInspector.isInLayer(inspection.packageName, "${convention.domainPackageSegment}.event")
                    && !SourceInspector.isInLayer(inspection.packageName, "${convention.domainPackageSegment}.exception")
        }.each { inspection ->
            JavaSourceAstInspector.TypeModel type = inspection.primaryType()
            if (type.kind == 'RECORD') {
                valueObjects.put(type.qualifiedName, inspection)
            }
        }
        return valueObjects
    }

    private static List<String> validateValueObjectInstantiation(
            Project project,
            JavaSourceAstInspector.Inspection inspection,
            Map<String, JavaSourceAstInspector.Inspection> valueObjects
    ) {
        List<String> violations = []
        String source = stripCommentsAndStrings(inspection.file.getText('UTF-8'))
        valueObjects.each { String qualifiedName, JavaSourceAstInspector.Inspection owner ->
            if (owner.file == inspection.file) {
                return
            }
            String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
            boolean visible = owner.packageName == inspection.packageName
                    || inspection.allImports.contains(qualifiedName)
                    || source.contains(qualifiedName)
            if (!visible) {
                return
            }
            String pattern = "\\bnew\\s+(?:${Pattern.quote(qualifiedName)}|${Pattern.quote(simpleName)})\\s*(?:<[^>]*>)?\\s*\\("
            if ((source =~ pattern).find()) {
                violations.add("${project.relativePath(inspection.file)} value object '${simpleName}' must be created with ${simpleName}.of(...), not new")
            }
        }
        return violations
    }

    private static String stripCommentsAndStrings(String source) {
        return source
                .replaceAll(/(?s)\/\*.*?\*\//, ' ')
                .replaceAll(/(?m)\/\/.*$/, ' ')
                .replaceAll(/(?s)"(?:\\.|[^"\\])*"/, '""')
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

    private static boolean hasPublicStaticFactoryMethod(
            JavaSourceAstInspector.TypeModel type,
            String methodName = null
    ) {
        return type.methods.any { method ->
            !method.constructor
                    && (methodName == null || method.name == methodName)
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
