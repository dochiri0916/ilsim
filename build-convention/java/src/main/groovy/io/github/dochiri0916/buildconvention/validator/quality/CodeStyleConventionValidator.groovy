package io.github.dochiri0916.buildconvention.validator.quality

import io.github.dochiri0916.buildconvention.source.java.JavaLexicalScanner
import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.validator.domain.collection.ImmutableCollectionConventionValidator
import java.util.regex.Pattern
import org.gradle.api.Project

/** Common declarations plus mechanically decidable object-calisthenics rules. */
final class CodeStyleConventionValidator {
    private static final Set<String> PROXY_ANNOTATIONS = ['Transactional', 'Async', 'Cacheable', 'CachePut', 'CacheEvict'] as Set

    private CodeStyleConventionValidator() {
    }

    static List<String> validate(Project project, File file, String packageName,
                                 JavaSourceAstInspector.TypeModel type) {
        if (!packageName.tokenize('.').any { segment -> segment in ['domain', 'application', 'adapter'] }) {
            return []
        }
        String path = project.relativePath(file)
        List<String> violations = []
        boolean entity = type.annotation('Entity') != null
        boolean adapter = packageName.contains('.adapter.')
        boolean inheritanceBase = type.modifiers.contains('ABSTRACT')
        boolean proxy = !packageName.contains('.domain.') && requiresSuperclassProxy(type)
        if (type.kind == 'CLASS' && !type.modifiers.contains('FINAL') && !inheritanceBase && !proxy && !entity) {
            violations.add("${path} code convention class '${type.simpleName}' must be final except inheritance bases, superclass proxies, and JPA entities")
        }
        boolean frameworkDto = adapter && (type.kind == 'RECORD' || type.simpleName.endsWith('Request')
                || type.simpleName.endsWith('Response') || type.simpleName.endsWith('Row'))
        Set<String> stateNames = ImmutableCollectionConventionValidator.stateFields(type).collect { field -> field.name }.toSet()
        type.methods.each { method ->
            boolean compact = type.kind == 'RECORD' && method.constructor
                    && (method.declarationHeader =~ /\b${Pattern.quote(type.simpleName)}\s*$/).find()
            // coding-conventions: 본문이 없는 인터페이스·추상 메서드의 파라미터에는 final이 효과가 없고 PMD·Checkstyle이 거부하므로 제외한다.
            if (!compact && method.hasBody && method.finalParameters.any { parameter -> !parameter }) {
                violations.add("${path} code convention method '${method.name}' must declare every parameter final")
            }
            if (entity || frameworkDto) {
                return
            }
            if (method.hasElse) {
                violations.add("${path} code convention method '${method.name}' must use early returns instead of else")
            }
            if (method.controlDepth > 1) {
                violations.add("${path} code convention method '${method.name}' must use at most one level of control-flow nesting")
            }
            if (method.name ==~ /set[A-Z].*/) {
                violations.add("${path} code convention method '${method.name}' must not use setter naming")
            }
            if (method.name ==~ /get[A-Z].*/ && method.parameterTypes.isEmpty()) {
                String property = method.name.substring(3)
                property = property.substring(0, 1).toLowerCase(Locale.ROOT) + property.substring(1)
                if (stateNames.contains(property)) {
                    violations.add("${path} code convention accessor '${method.name}' must use the field name without get")
                }
            }
            if (hasMultipleDotsOnLine(type, method)) {
                violations.add("${path} code convention method '${method.name}' must use one dot per line except builder, Stream, and Optional chains")
            }
        }
        type.nestedTypes.each { nested ->
            violations.addAll(validate(project, file, packageName, nested))
        }
        return violations
    }

    static boolean requiresSuperclassProxy(JavaSourceAstInspector.TypeModel type) {
        type.annotations.any { annotation -> PROXY_ANNOTATIONS.contains(annotation.simpleName) }
                || type.methods.any { method -> method.annotations.any { annotation -> PROXY_ANNOTATIONS.contains(annotation.simpleName) } }
    }

    private static boolean hasMultipleDotsOnLine(JavaSourceAstInspector.TypeModel type,
                                                 JavaSourceAstInspector.MethodModel method) {
        String body = method.body ?: ''
        // Javac expands Unicode escapes before parsing; do not let encoded punctuation hide chains.
        if (body.contains('\\u')) {
            body = method.codeBody ?: ''
        }
        String code = JavaLexicalScanner.codeOnly(body)
                .replaceAll(/\b\d+\.\d+\b/, '0')
                .replaceAll(/\b(?:[a-z][A-Za-z0-9_]*\s*\.\s*)+[A-Z][A-Za-z0-9_]*/, 'Type')
        Set<String> chainVariables = type.fields.findAll { field -> field.type ==~ /.*(?:Optional|Stream|Builder)(?:<.*>)?/ }
                .collect { field -> field.name }.toSet()
        method.parameterTypes.eachWithIndex { String parameter, int index ->
            if (parameter ==~ /.*(?:Optional|Stream|Builder)(?:<.*>)?/) {
                chainVariables.add(method.parameterNames[index])
            }
        }
        def locals = code =~ /\b(?:Optional|Stream|[A-Z][A-Za-z0-9]*Builder)(?:<[^;=]*>)?\s+([A-Za-z_][A-Za-z0-9_]*)\s*=/
        while (locals.find()) {
            chainVariables.add(locals.group(1))
        }
        code.split(';').any { String statement ->
            boolean chain = (statement =~ /\b(?:Optional|Stream)\s*\.|\b(?:stream|parallelStream|builder)\s*\(/).find()
                    || chainVariables.any { name -> (statement =~ /\b${Pattern.quote(name)}\s*\./).find() }
            !chain && statement.readLines().any { line -> line.count('.') > 1 }
        }
    }
}
