package io.github.dochiri0916.buildconvention.plugin

import com.github.spotbugs.snom.SpotBugsTask
import groovy.json.JsonOutput
import io.github.dochiri0916.buildconvention.validator.RuntimeConventionReview
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.Pmd

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class BuildConventionReport {
    private enum Status { PASS, FAIL, SKIPPED, NOT_APPLIED }

    static void write(Project project) {
        Map<String, List<String>> groups = [
                architecture       : ['validateHexagonalArchitecture', 'validateEntityNamingConvention',
                                      'validateDomainStaticFactoryConvention', 'validateArchitectureConventions'],
                archUnit           : ['validateArchUnitArchitecture'],
                checkstyle         : names(project, Checkstyle),
                pmd                : names(project, Pmd),
                spotBugs           : names(project, SpotBugsTask),
                migration          : ['validateMigrationConventions'],
                changedCodeCoverage: ['validateChangedCodeCoverage'],
                coverageVerification: ['jacocoTestCoverageVerification'],
                tests              : ['test'],
                compilation        : ['compileJava', 'compileTestJava'],
                javaVersion        : ['validateJavaVersionConvention'],
                conventionIntegrity: ['check'],
                mutationGate       : project.plugins.hasPlugin('info.solidsoft.pitest') ||
                        project.tasks.findByName('validatePitMutationGate')?.state?.failure != null
                        ? ['validatePitMutationGate', 'pitest'] : []
        ]
        Map<String, String> checks = groups.collectEntries { String key, List<String> taskNames ->
            [(key): status(project, taskNames).name()]
        }
        File jacocoXml = project.layout.buildDirectory.file('reports/jacoco/test/jacocoTestReport.xml').get().asFile
        Status jacocoStatus = status(project, ['jacocoTestReport'])
        boolean jacocoSucceeded = jacocoStatus == Status.PASS && jacocoXml.isFile()
        checks.coverageReport = jacocoStatus == Status.PASS && !jacocoXml.isFile()
                ? Status.SKIPPED.name() : jacocoStatus.name()
        Map coverage = jacocoSucceeded ? coverage(jacocoXml) : [line: null, branch: null]
        boolean pitEnabled = project.plugins.hasPlugin('info.solidsoft.pitest')
        File pitXml = project.layout.buildDirectory.file('reports/pitest/mutations.xml').get().asFile
        boolean pitSucceeded = taskSucceeded(project.tasks.findByName('pitest'))
        Map mutation = [enabled: pitEnabled, score: pitEnabled && pitSucceeded ? mutationScore(pitXml) : null]
        Map report = [
                schemaVersion: '1.0',
                pluginVersion: pluginVersion(),
                status       : checks.values().contains(Status.FAIL.name()) ? Status.FAIL.name() : Status.PASS.name(),
                checks       : checks,
                coverage     : coverage,
                mutation     : mutation,
                conventionReview: RuntimeConventionReview.report(project)
        ]
        File output = project.layout.buildDirectory.file('reports/build-convention/report.json').get().asFile
        output.parentFile.mkdirs()
        output.text = JsonOutput.prettyPrint(JsonOutput.toJson(report)) + System.lineSeparator()
    }

    private static List<String> names(Project project, Class type) {
        project.tasks.withType(type).collect { Task task -> task.name }.sort()
    }

    private static Status status(Project project, List<String> names) {
        if (names.isEmpty()) return Status.NOT_APPLIED
        List<Task> tasks = names.collect { String name -> project.tasks.findByName(name) }.findAll()
        if (tasks.isEmpty()) return Status.NOT_APPLIED
        if (tasks.any { Task task -> task.state.failure != null }) return Status.FAIL
        if (tasks.any { Task task -> taskSucceeded(task) }) return Status.PASS
        if (tasks.every { Task task -> task.state.noSource }) return Status.NOT_APPLIED
        return Status.SKIPPED
    }

    private static boolean taskSucceeded(Task task) {
        task != null && task.state.failure == null &&
                (task.state.executed && !task.state.skipped || task.state.upToDate ||
                        task.state.skipMessage == 'FROM-CACHE')
    }

    private static Map coverage(File xml) {
        if (!xml.isFile()) return [line: null, branch: null]
        def root = parse(xml)
        Map ratios = [line: null, branch: null]
        root.childNodes.each { node ->
            if (node.nodeName == 'counter') {
                String key = node.attributes.getNamedItem('type')?.nodeValue?.toLowerCase()
                if (ratios.containsKey(key)) {
                    long covered = node.attributes.getNamedItem('covered').nodeValue.toLong()
                    long missed = node.attributes.getNamedItem('missed').nodeValue.toLong()
                    ratios[key] = covered + missed == 0 ? null : covered / (covered + missed)
                }
            }
        }
        ratios
    }

    private static BigDecimal mutationScore(File xml) {
        if (!xml.isFile()) return null
        def mutations = parse(xml).getElementsByTagName('mutation')
        if (mutations.length == 0) return null
        int detected = 0
        for (int i = 0; i < mutations.length; i++) {
            if (mutations.item(i).attributes.getNamedItem('detected')?.nodeValue == 'true') detected++
        }
        detected / mutations.length
    }

    private static def parse(File xml) {
        def factory = DocumentBuilderFactory.newInstance()
        factory.setFeature('http://xml.org/sax/features/external-general-entities', false)
        factory.setFeature('http://xml.org/sax/features/external-parameter-entities', false)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setXIncludeAware(false)
        factory.setExpandEntityReferences(false)
        def builder = factory.newDocumentBuilder()
        builder.setEntityResolver { String publicId, String systemId -> new org.xml.sax.InputSource(new StringReader('')) }
        builder.parse(xml).documentElement
    }

    private static String pluginVersion() {
        Properties properties = new Properties()
        BuildConventionReport.getResourceAsStream('/build-convention-version.properties').withCloseable {
            properties.load(it)
        }
        properties.getProperty('version')
    }
}
