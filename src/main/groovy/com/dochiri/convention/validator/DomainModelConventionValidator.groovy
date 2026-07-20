package com.dochiri.convention.validator

import com.dochiri.convention.support.JavaSourceAstInspector
import org.gradle.api.Project

import java.util.regex.Pattern

/** Aggregate state and identity rules extracted from the source architecture validator. */
final class DomainModelConventionValidator {
    private DomainModelConventionValidator() {
    }

    static List<String> validate(Project project, File file, String source, JavaSourceAstInspector.TypeModel type) {
        if (type.kind != 'CLASS' || !type.modifiers.contains('FINAL') || !hasIdentifierField(type)) {
            return []
        }
        String path = project.relativePath(file)
        List<String> violations = []
        if (!hasPrivateFinalState(type)) {
            violations.add("${path} domain aggregate state must use private final fields")
        }
        if (!hasEqualsAndHashCode(type)) {
            violations.add("${path} domain aggregate with identifier VO must override equals and hashCode using id")
        } else if (hasNonIdStateInEqualityMethods(type)) {
            violations.add("${path} domain aggregate equals and hashCode must use identifier field 'id' only")
        }
        if (hasRestoreSideEffect(type)) {
            violations.add("${path} domain aggregate restore must not create a new id, time, UUID, or domain event")
        }
        if (hasInPlaceMutation(type)) {
            violations.add("${path} domain aggregate state changes must return a new aggregate instead of mutating this")
        }
        return violations
    }

    private static boolean hasIdentifierField(JavaSourceAstInspector.TypeModel type) {
        type.fields.any { field ->
            !field.staticField && field.name == 'id' && field.type.replaceAll(/\s+/, '').endsWith('Id')
        }
    }

    private static boolean hasPrivateFinalState(JavaSourceAstInspector.TypeModel type) {
        type.fields.findAll { field -> !field.staticField }.every { field ->
            field.finalField && field.modifiers.contains('PRIVATE')
        }
    }

    private static boolean hasEqualsAndHashCode(JavaSourceAstInspector.TypeModel type) {
        boolean equals = type.methods.any { method ->
            method.name == 'equals' && method.modifiers.contains('PUBLIC')
                    && method.parameterTypes.size() == 1 && simplify(method.returnType) == 'boolean'
        }
        boolean hashCode = type.methods.any { method ->
            method.name == 'hashCode' && method.modifiers.contains('PUBLIC')
                    && method.parameterTypes.isEmpty() && simplify(method.returnType) == 'int'
        }
        equals && hashCode
    }

    private static boolean hasNonIdStateInEqualityMethods(JavaSourceAstInspector.TypeModel type) {
        Set<String> stateFields = type.fields.findAll { field -> !field.staticField && field.name != 'id' }
                .collect { field -> field.name }.toSet()
        String body = type.methods.findAll { method -> method.name in ['equals', 'hashCode'] }
                .collect { method -> method.body ?: '' }.join('\n')
        stateFields.any { field -> (body =~ /\b${Pattern.quote(field)}\b/).find() }
    }

    private static boolean hasRestoreSideEffect(JavaSourceAstInspector.TypeModel type) {
        type.methods.any { method ->
            method.name in ['restore', 'reconstitute'] && method.body != null
                    && (method.body =~ /\b(?:UUID\s*\.|Instant\s*\.|LocalDateTime\s*\.|ZonedDateTime\s*\.|OffsetDateTime\s*\.|now\s*\(|generate\s*\(|new\s+[A-Za-z_][A-Za-z0-9_]*Event\b)/).find()
        }
    }

    private static boolean hasInPlaceMutation(JavaSourceAstInspector.TypeModel type) {
        type.methods.any { method ->
            !method.constructor && !method.modifiers.contains('STATIC') && method.body != null
                    && simplify(method.returnType) == type.simpleName
                    && (method.body =~ /\bthis\s*\.\s*[A-Za-z_][A-Za-z0-9_]*\s*=/).find()
        }
    }

    private static String simplify(String type) {
        type == null ? null : type.replaceAll(/<.*>/, '').tokenize('.').last()
    }
}
