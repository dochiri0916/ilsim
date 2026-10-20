package io.github.dochiri0916.buildconvention.validator.messaging

import io.github.dochiri0916.buildconvention.context.BoundedContexts
import io.github.dochiri0916.buildconvention.source.java.SourceTypeIndex
import org.gradle.api.Project

final class MessagingConventionValidator {
    private static final Map<String, String> ENVELOPE = [eventId: 'String', source: 'String', eventType: 'String',
            aggregateId: 'String', sequence: 'long', occurredAt: 'Instant', payload: 'String']
    private static final Set<String> RELATIONS = ['OneToMany', 'ManyToOne', 'OneToOne', 'ManyToMany', 'ElementCollection'] as Set

    static List<String> validate(Project project, SourceTypeIndex index) {
        List<String> violations = []
        index.entries.values().each { entry ->
            String path = project.relativePath(entry.inspection.file)
            String packageName = entry.inspection.packageName
            def type = entry.type
            if (packageName.endsWith('.global.messaging') && type.simpleName == 'MessageEnvelope') {
                Map fields = type.recordComponents.collectEntries { [(it.name): index.resolve(it.type, entry)] }
                if (type.kind != 'RECORD' || ENVELOPE.any { name, expected ->
                    String resolved = expected == 'Instant' ? 'java.time.Instant' : expected == 'String' ? 'java.lang.String' : expected
                    fields[name] != resolved
                }) {
                    violations.add("${path} messaging MessageEnvelope must be a record with eventId, source, eventType, aggregateId, sequence, occurredAt and payload of their contract types")
                }
            }
            if (packageName.contains('.application.port.out.event') && type.kind != 'INTERFACE') {
                validateEvent(path, entry, index, violations)
            }
            if (packageName.contains('.application.port.out.event') && type.simpleName.endsWith('Port')) {
                violations.add("${path} messaging public integration event must not use a Port suffix")
            }
            if (packageName.contains('.adapter.in.messaging')) {
                if (type.annotations.any { it.simpleName == 'EventListener' }
                        || type.methods.any { method -> method.annotations.any { it.simpleName == 'EventListener' } }
                        || (entry.inspection.allImports + type.qualifiedTypeReferences).any { it.startsWith('org.springframework.kafka.') }) {
                    violations.add("${path} messaging consumer must keep EventListener and Kafka transport in a separate receiving transport adapter")
                }
            }
            validateForeignReferences(path, entry, violations)
            if (type.annotation('Entity') != null) validateStorage(path, entry, index, violations)
        }
        return violations.unique()
    }

    private static void validateEvent(String path, Map entry, SourceTypeIndex index, List<String> violations) {
        def type = entry.type
        def eventType = type.methods.find { it.name == 'eventType' && it.parameterTypes.empty && it.modifiers.contains('PUBLIC') }
        String body = eventType?.codeBody ?: ''
        def returned = body =~ /(?s)^\s*\{\s*return\s+("[^"]+"|[\w]+)\s*;\s*}\s*$/
        String value = returned.find() ? returned.group(1) : ''
        Object constant = value.startsWith('"') ? value.substring(1, value.length() - 1) :
                type.fields.find { it.name == value && it.staticField && it.finalField }?.literalInitializer
        if (index.resolve(eventType?.returnType, entry) != 'java.lang.String'
                || !(constant instanceof String) || !(constant ==~ /[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+\.v[1-9][0-9]*/)) {
            violations.add("${path} messaging public integration event must return an explicit versioned eventType such as catalog.product-changed.v1")
        }
        boolean aggregate = type.recordComponents.any { it.name == 'aggregateId' && index.resolve(it.type, entry) == 'java.lang.String' }
                || type.methods.any { it.name == 'aggregateId' && it.parameterTypes.empty && it.modifiers.contains('PUBLIC')
                    && index.resolve(it.returnType, entry) == 'java.lang.String' }
        if (!aggregate) violations.add("${path} messaging public integration event must expose a String aggregateId")
        String application = entry.inspection.packageName.substring(0, entry.inspection.packageName.indexOf('.application.')) + '.application'
        if (!index.entries.values().any { mapper -> mapper.inspection.packageName == application && mapper.type.simpleName.endsWith('EventMapper')
                && mapper.type.methods.any { method -> method.modifiers.contains('PUBLIC') && method.modifiers.contains('STATIC')
                    && method.hasBody && index.resolve(method.returnType, mapper) == entry.name
                    && method.parameterTypes.any { index.resolve(it, mapper).contains('.domain.event.') } } }) {
            violations.add("${path} messaging public integration event requires an EventMapper in its own application package")
        }
        Map port = index.entries[application + '.port.out.IntegrationEventPort']
        Set<String> eventTypes = index.hierarchy(entry).collect { it.name }.toSet()
        if (port == null || port.type.kind != 'INTERFACE' || !port.type.methods.any { method ->
            !method.hasBody && !method.modifiers.contains('STATIC') && method.parameterTypes.any { eventTypes.contains(index.resolve(it, port)) }
        }) {
            violations.add("${path} messaging public integration event requires its own application.port.out.IntegrationEventPort")
        }
    }

    private static void validateForeignReferences(String path, Map entry, List<String> violations) {
        String packageName = entry.inspection.packageName
        if (!packageName.contains('.adapter.')) return
        String context = BoundedContexts.contextOf(packageName)
        (entry.inspection.allImports + entry.type.qualifiedTypeReferences).each { reference ->
            String other = BoundedContexts.contextOf(reference)
            if (!context || !other || context == other) return
            if (!packageName.contains('.adapter.in.messaging') || !reference.contains('.application.port.out.event.')) {
                violations.add("${path} messaging cross-context adapter dependency '${reference}' must be a public integration event consumed in adapter.in.messaging")
            }
        }
    }

    private static void validateStorage(String path, Map entry, SourceTypeIndex index, List<String> violations) {
        List<Map> hierarchy = index.hierarchy(entry)
        boolean outbox = entry.inspection.packageName.endsWith('.persistence.outbox') || hierarchy.any { it.type.simpleName ==~ /.*Outbox(?:Entity|Entry)/ }
        boolean inbox = entry.inspection.packageName.endsWith('.persistence.inbox') || hierarchy.any { it.type.simpleName ==~ /.*Inbox(?:Entity|Entry)/ }
        boolean projection = entry.inspection.packageName.endsWith('.persistence.projection')
        if (!outbox && !inbox && !projection) return
        def fields = hierarchy.collectMany { it.type.fields }.findAll { !it.staticField }
        if (fields.any { it.modifiers.contains('PUBLIC') || it.annotations.any { RELATIONS.contains(it.simpleName) } }) {
            violations.add("${path} messaging persistence Entity must not expose public fields or use JPA object relation mappings")
        }
        if (hierarchy.any { parent -> parent.type.methods.any { method -> method.annotations.any { RELATIONS.contains(it.simpleName) } } }) {
            violations.add("${path} messaging persistence Entity must not expose public fields or use JPA object relation mappings")
        }
        if (!outbox && !inbox) return
        def eventId = fields.find { it.name == 'eventId' }
        def column = eventId?.annotation('Column')
        Map eventOwner = hierarchy.find { it.type.fields.contains(eventId) }
        if (!eventOwner || index.resolve(eventId?.type, eventOwner) != 'java.lang.String' || !eventId.modifiers.contains('PRIVATE')
                || !jpaAnnotation(column, eventOwner, index) || column?.arguments?.get('nullable') != 'false' || column.arguments.get('length') != '36') {
            violations.add("${path} messaging persistence eventId must be a private required String column with length = 36")
        }
        String eventColumn = column?.arguments?.get('name') ?: 'event_id'
        def table = entry.type.annotation('Table')
        Set<Set<String>> uniqueKeys = (jpaAnnotation(table, entry, index) ? table.nestedAnnotations : [])?.findAll {
            it.simpleName == 'UniqueConstraint' && jpaAnnotation(it, entry, index)
        }?.collect { constraint ->
            String syntax = constraint.arguments.get('columnNames') ?: ''
            Set<String> columns = [] as Set
            if (!constraint.literalArguments.contains('columnNames')
                    && !(syntax ==~ /\{\s*"[^"]+"(?:\s*,\s*"[^"]+")*\s*}/)) return columns
            def literals = syntax =~ /"([^"]+)"/
            while (literals.find()) columns.add(literals.group(1))
            if (syntax ==~ /[a-z][a-z0-9_]*/) columns.add(syntax)
            columns
        }?.toSet() ?: [] as Set
        if (outbox && column?.arguments?.get('unique') != 'true' && !uniqueKeys.contains([eventColumn] as Set)) {
            violations.add("${path} messaging Outbox must declare a singleton UNIQUE constraint on event_id")
        }
        if (inbox) {
            def consumer = fields.find { it.name == 'consumer' }
            Map consumerOwner = hierarchy.find { it.type.fields.contains(consumer) }
            String consumerColumn = consumer?.annotation('Column')?.arguments?.get('name') ?: 'consumer'
            if (!consumerOwner || index.resolve(consumer?.type, consumerOwner) != 'java.lang.String' || consumer.annotation('Column')?.arguments?.get('nullable') != 'false'
                    || !jpaAnnotation(consumer?.annotation('Column'), consumerOwner, index)
                    || !uniqueKeys.contains([eventColumn, consumerColumn] as Set)) {
                violations.add("${path} messaging Inbox must declare a required String consumer and an exact UNIQUE constraint on (event_id, consumer)")
            }
        }
    }

    private static boolean jpaAnnotation(def annotation, Map entry, SourceTypeIndex index) {
        if (!annotation || !entry) return false
        String qualified = index.resolve(annotation.qualifiedName, entry)
        if (qualified == 'jakarta.persistence.' + annotation.simpleName) return true
        return !annotation.qualifiedName.contains('.') && entry.inspection.imports.contains('jakarta.persistence.*')
                && !entry.inspection.imports.any { it.endsWith('.' + annotation.simpleName) }
                && !index.entries.containsKey(entry.inspection.packageName + '.' + annotation.simpleName)
    }
}
