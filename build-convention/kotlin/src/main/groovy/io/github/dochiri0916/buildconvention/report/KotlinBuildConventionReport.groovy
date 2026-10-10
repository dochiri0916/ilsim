package io.github.dochiri0916.buildconvention.report

import groovy.json.JsonOutput
import org.gradle.api.Project

/** Preserve the consumer's report schema and statuses; Java-only checks are explicitly NOT_APPLIED. */
final class KotlinBuildConventionReport {
    private KotlinBuildConventionReport() {
    }

    static void write(Project project) {
        def extras = project.extensions.extraProperties
        Map result = extras.has('kotlinConventionResult') ? extras.get('kotlinConventionResult') : null
        String architecture = result == null ? taskStatus(project, ['validateKotlinDomainConventions']) :
                !result.violations.isEmpty() ? 'FAIL' : result.domainFiles > 0 ? 'PASS' : 'NOT_APPLIED'
        String archUnit = extras.has('kotlinArchUnitApplied') && !extras.get('kotlinArchUnitApplied') ? 'NOT_APPLIED' :
                taskStatus(project, ['validateKotlinArchUnitArchitecture'])
        Map checks = [architecture: architecture, archUnit: archUnit,
                      compilation: taskStatus(project, ['compileKotlin', 'compileTestKotlin']),
                      tests: taskStatus(project, ['test']),
                      conventionIntegrity: extras.has('kotlinIntegrityFailure') ? 'FAIL' :
                              extras.has('kotlinIntegrityPassed') ? 'PASS' : 'SKIPPED',
                      checkstyle: 'NOT_APPLIED', pmd: 'NOT_APPLIED', spotBugs: 'NOT_APPLIED',
                      migration: 'NOT_APPLIED', changedCodeCoverage: 'NOT_APPLIED', coverageVerification: 'NOT_APPLIED',
                      coverageReport: 'NOT_APPLIED', javaVersion: 'NOT_APPLIED', mutationGate: 'NOT_APPLIED']
        Properties version = new Properties()
        KotlinBuildConventionReport.getResourceAsStream('/build-convention-version.properties').withCloseable { stream ->
            version.load(stream)
        }
        Map report = [schemaVersion: '1.0', pluginVersion: version.getProperty('version'),
                      status: checks.values().contains('FAIL') ? 'FAIL' : 'PASS', checks: checks,
                      coverage: [line: null, branch: null], mutation: [enabled: false, score: null],
                      kotlin: [files: result?.files, domainFiles: result?.domainFiles, violations: result?.violations ?: []]]
        String filename = project.plugins.hasPlugin('io.github.dochiri0916.build-convention') ||
                project.plugins.hasPlugin('io.github.dochiri0916.build-convention.java') ? 'report-kotlin.json' : 'report.json'
        File output = project.layout.buildDirectory.file("reports/build-convention/${filename}").get().asFile
        output.parentFile.mkdirs()
        output.text = JsonOutput.prettyPrint(JsonOutput.toJson(report)) + System.lineSeparator()
    }

    private static String taskStatus(Project project, List<String> names) {
        List tasks = names.collect { project.tasks.findByName(it) }.findAll()
        if (tasks.isEmpty()) return 'NOT_APPLIED'
        if (tasks.any { it.state.failure != null }) return 'FAIL'
        if (tasks.any { it.state.executed && !it.state.skipped || it.state.upToDate || it.state.skipMessage == 'FROM-CACHE' }) return 'PASS'
        if (tasks.every { it.state.noSource }) return 'NOT_APPLIED'
        return 'SKIPPED'
    }
}
