package io.github.dochiri0916.buildconvention.validator.domain.collection

import io.github.dochiri0916.buildconvention.source.java.JavaLexicalScanner
import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import java.util.regex.Pattern

/** Shared collection contract for application wrappers and external DTO/event exceptions. */
final class ImmutableCollectionConventionValidator {
    private ImmutableCollectionConventionValidator() {
    }

    static List<String> validate(String path, JavaSourceAstInspector.TypeModel type, boolean externalContract) {
        List<String> violations = []
        def fields = stateFields(type)
        def collections = fields.findAll { field -> isCollection(field.type) }
        if (collections.isEmpty()) {
            return violations
        }
        if (!externalContract && !isFirstClassCollection(type)) {
            violations.add("${path} collection contract '${type.simpleName}' must wrap each collection in a first-class collection with one instance field")
            return violations
        }
        if (type.kind == 'CLASS' && (!type.modifiers.contains('FINAL')
                || !fields.every { field -> field.finalField && field.modifiers.contains('PRIVATE') })) {
            violations.add("${path} first-class collection '${type.simpleName}' must be an immutable final class with private final state")
        }
        def constructors = type.methods.findAll { method -> method.constructor }
        collections.each { field ->
            String kind = collectionKind(field.type)
            if (!(kind in ['List', 'Set', 'Map']) || constructors.isEmpty() || constructors.any { method ->
                !(JavaLexicalScanner.codeOnly(method.codeBody) =~ /\b(?:this\s*\.\s*)?${Pattern.quote(field.name)}\s*=\s*(?:java\s*\.\s*util\s*\.\s*)?${kind}\s*\.\s*copyOf\s*\(/).find()
            }) {
                violations.add("${path} collection contract '${type.simpleName}' field '${field.name}' must defensively copy with List/Set/Map.copyOf in every constructor")
            }
            if (type.methods.any { method ->
                !method.constructor && (JavaLexicalScanner.codeOnly(method.codeBody) =~ /\b${Pattern.quote(field.name)}\s*\.\s*(?:add(?:All)?|remove(?:All|If)?|clear|put(?:All|IfAbsent)?|set|sort|replace(?:All)?|retainAll|compute(?:IfAbsent|IfPresent)?|merge)\s*\(/).find()
            }) {
                violations.add("${path} collection contract '${type.simpleName}' must not mutate its stored collection")
            }
        }
        return violations
    }

    static boolean isFirstClassCollection(JavaSourceAstInspector.TypeModel type) {
        def fields = stateFields(type)
        fields.size() == 1 && isCollection(fields.first().type) && type.kind in ['CLASS', 'RECORD']
    }

    static List<JavaSourceAstInspector.FieldModel> stateFields(JavaSourceAstInspector.TypeModel type) {
        type.kind == 'RECORD' ? type.recordComponents : type.fields.findAll { field -> !field.staticField }
    }

    static boolean isCollection(String type) {
        collectionKind(type) != null || type?.contains('[]')
    }

    private static String collectionKind(String type) {
        def matcher = (type ?: '') =~ /(?:^|[<,\s])(?:java\.util\.)?(List|Set|Map|Collection|Iterable|ArrayList|HashSet|HashMap|SortedSet|SortedMap|NavigableSet|NavigableMap|Queue|Deque)</
        matcher.find() ? matcher.group(1) : null
    }
}
