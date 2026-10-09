package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.support.JavaLexicalScanner
import io.github.dochiri0916.buildconvention.support.JavaSourceAstInspector
import org.gradle.api.Project

/** Contract shape and service orchestration rules, independent of Gradle wiring. */
final class ApplicationContractConventionValidator {
    private static final Set<String> METADATA = ['page', 'size', 'limit', 'offset', 'sequence', 'revision'] as Set
    private static final Set<String> SCALARS = ['String', 'boolean', 'int', 'long', 'double', 'float',
                                               'Boolean', 'Integer', 'Long', 'Double', 'Float', 'UUID', 'BigDecimal'] as Set

    private ApplicationContractConventionValidator() {
    }

    static List<String> validate(Project project, File file, String packageName,
                                 JavaSourceAstInspector.TypeModel type) {
        String path = project.relativePath(file)
        List<String> violations = []
        boolean publishedEvent = packageName.contains('.port.out.event')
        boolean firstClass = ImmutableCollectionConventionValidator.isFirstClassCollection(type)
        boolean portContract = packageName.contains('.application.port.')
        if (portContract) {
            violations.addAll(ImmutableCollectionConventionValidator.validate(path, type, publishedEvent))
            if (!firstClass && !publishedEvent) {
                type.methods.findAll { method -> !method.constructor }.each { method ->
                    if (ImmutableCollectionConventionValidator.isCollection(method.returnType)
                            || method.parameterTypes.any { parameter -> ImmutableCollectionConventionValidator.isCollection(parameter) }) {
                        violations.add("${path} application port method '${method.name}' must use first-class collections in parameters and returns")
                    }
                }
            }
        }
        if (type.simpleName.endsWith('UseCase')) {
            type.methods.findAll { method -> !method.constructor && !method.hasBody }.each { method ->
                if ((type.simpleName ==~ /(?:Get|Find|Search|List|Read|Query|Count|Exists)[A-Z].*UseCase/
                        || method.name ==~ /(?:get|find|search|list|read|query|count|exists)[A-Z].*/)
                        && !method.parameterTypes.isEmpty()
                        && (method.parameterTypes.size() != 1 || !simpleType(method.parameterTypes.first()).endsWith('Query'))) {
                    violations.add("${path} query UseCase '${type.simpleName}' must receive its query conditions through one Query contract")
                }
            }
        }
        if (type.simpleName.endsWith('Query') && type.kind == 'RECORD' && type.recordComponents.isEmpty()) {
            violations.add("${path} application Query '${type.simpleName}' must contain query conditions instead of an empty contract")
        }
        if (type.simpleName.endsWith('SearchCondition')
                && (!packageName.endsWith('.port.out') || type.kind != 'RECORD')) {
            violations.add("${path} application search condition '${type.simpleName}' must be a record in application.port.out package")
        }
        if (type.simpleName.endsWith('SearchCondition') && type.kind == 'RECORD'
                && type.recordComponents.every { it.name in ['page', 'size', 'offset', 'limit'] }) {
            violations.add("${path} application search condition '${type.simpleName}' must contain search criteria; pagination-only queries must not create a SearchCondition")
        }
        if (type.kind == 'INTERFACE' && (type.simpleName.endsWith('UseCase') || type.simpleName.endsWith('Port'))) {
            def abstractMethods = type.methods.findAll { method ->
                !method.modifiers.contains('STATIC') && !method.modifiers.contains('PRIVATE') && !method.hasBody
            }
            if (abstractMethods.size() == 1 && type.annotation('FunctionalInterface') == null) {
                violations.add("${path} application port '${type.simpleName}' with one abstract method must declare @FunctionalInterface")
            }
        }
        if (type.simpleName.endsWith('RepositoryPort') && packageName.endsWith('.port.out')) {
            type.methods.each { method ->
                method.parameterTypes.eachWithIndex { String parameter, int index ->
                    if (SCALARS.contains(simpleType(parameter)) && !METADATA.contains(method.parameterNames[index])) {
                        violations.add("${path} repository port '${type.simpleName}' parameter '${method.parameterNames[index]}' must use a domain Value Object; only pagination/sequence/revision metadata may be scalar")
                    }
                }
                if (simpleType(method.returnType)?.endsWith('Id') && method.returnType.contains('.adapter.')) {
                    violations.add("${path} repository port '${type.simpleName}' must not expose a persistence identifier")
                }
            }
        }
        if (packageName.endsWith('.application.service')) {
            type.methods.each { method ->
                String body = JavaLexicalScanner.codeOnly(method.codeBody)
                if ((body =~ /\bnew\s+(?:[a-z][\w]*\s*\.\s*)*[A-Z][A-Za-z0-9]*Result\s*\(/).find()) {
                    violations.add("${path} application service '${type.simpleName}' must map domain objects to Result through an application ResultMapper")
                }
                if ((body =~ /\b[A-Z][A-Za-z0-9]*\s*\.\s*restore\s*\(/).find()) {
                    violations.add("${path} application service '${type.simpleName}' must not bypass domain state transitions with restore")
                }
            }
        }
        if (publishedEvent) {
            def fields = ImmutableCollectionConventionValidator.stateFields(type)
            if (fields.any { field -> field.type.contains('.domain.') || field.type.contains('.application.') }) {
                violations.add("${path} public integration event must contain primitive values and immutable external DTO collections, not domain/application models")
            }
        }
        type.nestedTypes.each { nested ->
            violations.addAll(validate(project, file, packageName, nested))
        }
        return violations
    }

    private static String simpleType(String type) {
        type == null ? null : type.replaceAll(/<.*>/, '').tokenize('.').last()
    }
}
