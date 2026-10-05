package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.support.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.support.SourceInspector
import org.gradle.api.Project

import java.util.regex.Pattern

/** Structural domain rules; business meaning and invariant completeness still require review. */
final class DomainValueObjectConventionValidator {
    private DomainValueObjectConventionValidator() {
    }

    static List<String> validate(Project project, File file, String packageName,
                                 JavaSourceAstInspector.TypeModel type) {
        String path = project.relativePath(file)
        List<String> violations = []
        boolean valueObject = packageName.contains('.domain.vo') || packageName.endsWith('.global.domain')
        boolean outbox = packageName.contains('.domain.outbox')
        if (type.simpleName.endsWith('Exception') || packageName.contains('.exception')) {
            return violations
        }
        if (type.simpleName.endsWith('Id') && !valueObject && !outbox) {
            violations.add("${path} identifier Value Object '${type.simpleName}' must live in domain.vo package")
        }
        if (type.kind == 'ENUM') {
            if (!valueObject && !outbox) {
                violations.add("${path} domain state enum '${type.simpleName}' must live in domain.vo package")
            }
            return violations
        }
        if (valueObject && type.kind != 'CLASS') {
            violations.add("${path} domain Value Object '${type.simpleName}' must be a final class with a private constructor")
            return violations
        }
        List<JavaSourceAstInspector.FieldModel> fields = type.fields.findAll { field -> !field.staticField }
        if (type.kind == 'CLASS') {
            if (!fields.every { field -> field.finalField && field.modifiers.contains('PRIVATE') }) {
                violations.add("${path} domain object '${type.simpleName}' state must use private final fields")
            }
            if (valueObject) {
                validateValueObject(path, type, fields, violations)
            }
            Set<String> eventNames = SourceInspector.extractImports(file.getText('UTF-8'))
                    .findAll { imported -> imported.contains('.domain.event.') }
                    .collect { imported -> imported.tokenize('.').last() }.toSet()
            if (!outbox && fields.any { field ->
                field.name in ['event', 'events', 'domainEvents'] || field.type.contains('.event.')
                        || eventNames.any { name -> containsName(field.type, name) }
            }) {
                violations.add("${path} domain aggregate must not retain domain events as state")
            }
        }
        type.nestedTypes.each { nested ->
            violations.addAll(validate(project, file, packageName, nested))
        }
        return violations
    }

    private static void validateValueObject(String path, JavaSourceAstInspector.TypeModel type,
                                            List<JavaSourceAstInspector.FieldModel> fields,
                                            List<String> violations) {
        if (!type.modifiers.contains('FINAL')) {
            violations.add("${path} domain Value Object '${type.simpleName}' must be final")
        }
        List<JavaSourceAstInspector.MethodModel> constructors = type.methods.findAll { method -> method.constructor }
        if (constructors.isEmpty() || constructors.any { method -> !method.modifiers.contains('PRIVATE') }) {
            violations.add("${path} domain Value Object '${type.simpleName}' must declare only private constructors")
        }
        if (constructors.any { method ->
            !(code(method.body) =~ /\b(?:if|throw|validate[A-Za-z0-9_]*|validated|requireNonNull)\b/).find()
        }) {
            violations.add("${path} domain Value Object '${type.simpleName}' must validate invariants in every private constructor")
        }
        if (!hasFactory(type, 'of')) {
            violations.add("${path} domain Value Object '${type.simpleName}' must expose an of factory")
        }
        if (type.methods.any { method ->
            !method.constructor && method.modifiers.contains('STATIC') && method.returnType == type.simpleName
                    && !(method.name in ['of', 'generate', 'parse', 'empty'])
        }) {
            violations.add("${path} domain Value Object factories must use of, generate, parse, or empty")
        }
        ['equals', 'hashCode'].each { String name ->
            def method = type.methods.find { candidate ->
                candidate.name == name && candidate.modifiers.contains('PUBLIC')
                        && (name == 'equals' ? candidate.returnType == 'boolean'
                        && candidate.parameterTypes == ['Object']
                        : candidate.returnType == 'int' && candidate.parameterTypes.isEmpty())
            }
            if (method == null || !fields.every { field -> containsName(code(method.body), field.name) }) {
                violations.add("${path} domain Value Object '${type.simpleName}' ${name} must use all component values")
            }
        }
        List<JavaSourceAstInspector.FieldModel> collections = fields.findAll { field -> collectionKind(field.type) != null }
        if (!collections.isEmpty() && fields.size() != 1) {
            violations.add("${path} first-class collection '${type.simpleName}' must have exactly one collection instance field")
        }
        collections.each { field ->
            String kind = collectionKind(field.type)
            if (constructors.any { method ->
                !(code(method.body) =~ /\b(?:this\s*\.\s*)?${Pattern.quote(field.name)}\s*=\s*(?:java\s*\.\s*util\s*\.\s*)?${kind}\s*\.\s*copyOf\s*\(/).find()
            }) {
                violations.add("${path} first-class collection '${type.simpleName}' must defensively copy in every constructor")
            }
            if (type.methods.any { method ->
                !method.constructor && (code(method.body) =~ /\b${Pattern.quote(field.name)}\s*\.\s*(?:add|remove|clear|put|set|sort|replaceAll|removeIf|retainAll)\s*\(/).find()
            }) {
                violations.add("${path} first-class collection '${type.simpleName}' must not mutate its collection")
            }
        }
        if (type.simpleName.endsWith('Id')) {
            validateIdentifier(path, type, fields, constructors, violations)
        }
    }

    private static void validateIdentifier(String path, JavaSourceAstInspector.TypeModel type,
                                          List<JavaSourceAstInspector.FieldModel> fields,
                                          List<JavaSourceAstInspector.MethodModel> constructors,
                                          List<String> violations) {
        if (fields.size() != 1 || !(fields.first().type in ['UUID', 'java.util.UUID'])) {
            violations.add("${path} identifier Value Object '${type.simpleName}' must wrap a UUID v7 value")
        }
        if (constructors.isEmpty() || constructors.any { method ->
            !(code(method.body) =~ /\.\s*version\s*\(\s*\)\s*!=\s*7\b/).find()
                    || !(code(method.body) =~ /\bthrow\b/).find()
        }) {
            violations.add("${path} identifier Value Object '${type.simpleName}' must reject non-v7 UUIDs in its constructor")
        }
        if (!type.simpleName.endsWith('ReferenceId') && !hasFactory(type, 'generate')) {
            violations.add("${path} identifier Value Object '${type.simpleName}' must expose a generate factory")
        }
        if (type.methods.any { method -> (code(method.body) =~ /\brandomUUID\s*\(/).find() }) {
            violations.add("${path} identifier Value Object '${type.simpleName}' must generate UUID v7 instead of randomUUID")
        }
    }

    private static boolean hasFactory(JavaSourceAstInspector.TypeModel type, String name) {
        type.methods.any { method ->
            method.name == name && method.modifiers.containsAll(['PUBLIC', 'STATIC'])
                    && method.returnType?.tokenize('.')?.last() == type.simpleName
        }
    }

    private static boolean containsName(String body, String name) {
        (body =~ /\b${Pattern.quote(name)}\b/).find()
    }

    private static String collectionKind(String type) {
        def matcher = type =~ /^(?:java\.util\.)?(List|Set|Map|Collection|Iterable|ArrayList|HashSet|HashMap|SortedSet|SortedMap|NavigableSet|NavigableMap|Queue|Deque)</
        matcher.find() ? matcher.group(1) : null
    }

    private static String code(String body) {
        // Match strings before comments so comment markers inside literals cannot hide code.
        (body ?: '').replaceAll(/(?s)""".*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\*.*?\*\/|\/\/[^\r\n]*/, ' ')
    }
}
