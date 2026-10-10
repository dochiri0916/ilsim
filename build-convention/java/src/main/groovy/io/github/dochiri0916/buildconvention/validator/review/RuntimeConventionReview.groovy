package io.github.dochiri0916.buildconvention.validator.review

import groovy.json.JsonSlurper
import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.source.java.SourceInspector
import io.github.dochiri0916.buildconvention.source.java.SourceTypeIndex
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/** Links review scenarios to real JUnit results without claiming their assertions prove business semantics. */
final class RuntimeConventionReview {
    static final Map<String, List<String>> SCENARIOS = [
            'QUERY-PAGINATION': ['invalid-page-and-size', 'large-offset', 'stable-order-with-ties'],
            'QUERY-RESULTS': ['list-count-same-filters', 'joined-row-deduplication', 'batch-children-without-n-plus-one'],
            'PERSISTENCE-CONTRACT': ['same-port-results', 'same-constraint-errors', 'flush-and-original-cause'],
            'EVENT-CONTRACT': ['mapper-payload-and-version', 'aggregate-sequence-and-revision', 'replay-preserves-identifiers'],
            'OUTBOX-DELIVERY': ['atomic-business-and-outbox', 'broker-confirmation-before-completion', 'polling-recovers-lost-wake',
                                'concurrent-pollers-and-order', 'bounded-retry-jitter-and-dlq', 'retention-and-cdc-boundary'],
            'INBOX-DELIVERY': ['concurrent-consumer-idempotency', 'atomic-handler-and-outbox', 'ack-after-commit',
                               'failed-is-not-completed', 'dlq-replay-and-retention'],
            'WEB-CONVERSION': ['request-to-usecase-contract', 'result-to-response-contract', 'validation-and-error-response']
    ].asImmutable()

    static Map report(Project project) {
        List<File> sources = SourceInspector.collectMainSourceFiles(project).findAll { it.name.endsWith('.java') }
        def inspections = JavaSourceAstInspector.inspectAll(sources).values()
        if (inspections.any { !it.valid }) return [status: 'REVIEW', scope: 'RUNTIME_SEMANTICS', requirements: [],
                                                   errors: ['Runtime review applicability cannot be determined from unparseable source.']]
        SourceTypeIndex index = new SourceTypeIndex(inspections)
        List<Map> requirements = []
        index.entries.values().each { entry ->
            String source = project.relativePath(entry.inspection.file)
            String name = entry.type.simpleName
            String pkg = entry.inspection.packageName
            Set<String> ids = [] as Set
            if (name.endsWith('QueryRepository')) {
                ids.add('QUERY-RESULTS')
                if (entry.type.methods.any { method -> method.invocations.any { it.selector.endsWith('.offset') || it.selector.endsWith('.limit') } }) ids.add('QUERY-PAGINATION')
            }
            if (pkg.contains('.application.port.out.event') && entry.type.kind != 'INTERFACE') ids.add('EVENT-CONTRACT')
            if (entry.type.annotation('Entity') != null) {
                def hierarchy = index.hierarchy(entry)
                if (pkg.contains('.persistence.outbox') || hierarchy.any { it.type.simpleName ==~ /.*Outbox(?:Entry|Entity)/ }) ids.add('OUTBOX-DELIVERY')
                if (pkg.contains('.persistence.inbox') || hierarchy.any { it.type.simpleName ==~ /.*Inbox(?:Entry|Entity)/ }) ids.add('INBOX-DELIVERY')
            }
            if (pkg.contains('.adapter.out.persistence') && name.endsWith('Adapter')) {
                List<String> ports = entry.type.implementedTypes.collect { index.resolve(it, entry) }
                if (index.entries.values().any { other -> !other.is(entry) && other.inspection.packageName.contains('.adapter.out.persistence')
                        && other.type.simpleName.endsWith('Adapter') && other.type.implementedTypes.any { ports.contains(index.resolve(it, other)) } }) {
                    ids.add('PERSISTENCE-CONTRACT')
                }
            }
            if (pkg.contains('.adapter.in.web') && (name.endsWith('Controller') || entry.type.annotation('RestController') != null)) ids.add('WEB-CONVERSION')
            ids.each { id -> requirements.add([id: id, source: source, scenarios: SCENARIOS[id]]) }
        }
        Map evidence = readEvidence(project)
        if (evidence.items?.any { item -> !requirements.any { requirement ->
            requirement.id == item.id && requirement.source == item.source && requirement.scenarios.contains(item.scenario)
        } }) {
            evidence = [items: [], error: 'Runtime evidence names an unknown requirement, source or scenario.']
        }
        Map tests = successfulTests(project)
        requirements.each { requirement ->
            requirement.status = 'REVIEW'
            requirement.evidence = requirement.scenarios.collect { scenario ->
                def item = evidence.items?.find { it.id == requirement.id && it.source == requirement.source && it.scenario == scenario }
                String key = item ? item.testClass + '#' + item.testName : ''
                Map execution = tests[key]
                [scenario: scenario, status: execution ? 'EXECUTED' : 'MISSING', test: item ? key : null,
                 report: execution?.report, review: item?.review instanceof String ? item.review : null]
            }
        }
        return [status: requirements.empty && !evidence.error ? 'NOT_APPLIED' : 'REVIEW', scope: 'RUNTIME_SEMANTICS',
                requirements: requirements, errors: evidence.error ? [evidence.error] : []]
    }

    private static Map readEvidence(Project project) {
        File file = project.file('.build-convention/runtime-evidence.json')
        if (!file.isFile()) return [items: []]
        try {
            def data = new JsonSlurper().parse(file)
            if (!(data instanceof Map) || data.schemaVersion != 1 || !(data.items instanceof List)
                    || data.items.any { !(it instanceof Map) || !['id', 'source', 'scenario', 'testClass', 'testName'].every { key -> it[key] instanceof String && it[key] } }
                    || data.items.collect { [it.id, it.source, it.scenario] }.unique().size() != data.items.size()) {
                return [items: [], error: 'Invalid or duplicate runtime evidence declarations.']
            }
            return data
        } catch (RuntimeException ignored) {
            return [items: [], error: 'Cannot read runtime evidence JSON.']
        }
    }

    private static Map successfulTests(Project project) {
        Map results = [:]
        project.tasks.withType(Test).each { task ->
            boolean succeeded = task.state.failure == null && (task.state.executed && !task.state.skipped ||
                    task.state.upToDate || task.state.skipMessage == 'FROM-CACHE')
            if (!succeeded || !task.reports.junitXml.required.get()) return
            File directory = task.reports.junitXml.outputLocation.get().asFile
            if (!directory.isDirectory()) return
            directory.listFiles().findAll { it.name.startsWith('TEST-') && it.name.endsWith('.xml') }.each { file ->
                try {
                    def factory = DocumentBuilderFactory.newInstance()
                    factory.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true)
                    factory.setFeature('http://xml.org/sax/features/external-general-entities', false)
                    factory.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    factory.setXIncludeAware(false)
                    factory.setExpandEntityReferences(false)
                    def root = factory.newDocumentBuilder().parse(file).documentElement
                    def cases = root.getElementsByTagName('testcase')
                    for (int position = 0; position < cases.length; position++) {
                        def test = cases.item(position)
                        if (test.getElementsByTagName('failure').length || test.getElementsByTagName('error').length || test.getElementsByTagName('skipped').length) continue
                        String key = test.getAttribute('classname') + '#' + test.getAttribute('name')
                        results[key] = [report: project.relativePath(file)]
                    }
                } catch (Exception ignored) {
                    // Unreadable evidence is never a successful execution.
                }
            }
        }
        return results
    }
}
