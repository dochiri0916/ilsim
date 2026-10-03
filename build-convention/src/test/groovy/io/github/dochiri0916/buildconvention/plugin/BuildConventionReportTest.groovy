package io.github.dochiri0916.buildconvention.plugin

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.internal.tasks.TaskExecutionOutcome
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BuildConventionReportTest {
    @TempDir
    File tempDir

    @Test
    void recordsTaskOutcomesAndJacocoCounters() {
        Project project = project()
        File xml = new File(tempDir, 'build/reports/jacoco/test/jacocoTestReport.xml')
        xml.parentFile.mkdirs()
        xml.text = '''<?xml version="1.0"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="fixture">
                  <counter type="LINE" missed="1" covered="3"/>
                  <counter type="BRANCH" missed="1" covered="1"/>
                </report>'''.stripIndent()
        project.tasks.getByName('jacocoTestReport').state.outcome = TaskExecutionOutcome.EXECUTED
        project.tasks.getByName('validateArchitectureConventions').state.outcome = TaskExecutionOutcome.EXECUTED
        project.tasks.getByName('check').state.outcome = TaskExecutionOutcome.EXECUTED

        BuildConventionReport.write(project)
        Map report = readReport()

        assert report.schemaVersion == '1.0'
        assert report.pluginVersion == '1.2.0'
        assert report.status == 'PASS'
        assert report.checks.architecture == 'PASS'
        assert report.checks.coverageReport == 'PASS'
        assert report.checks.mutationGate == 'SKIPPED'
        assert report.coverage == [line: 0.75, branch: 0.5]
        assert report.mutation == [enabled: true, score: null]

        project.tasks.getByName('validateArchitectureConventions').state.outcome = new RuntimeException('violation')
        BuildConventionReport.write(project)

        assert readReport().status == 'FAIL'
        assert readReport().checks.architecture == 'FAIL'
    }

    @Test
    void doesNotInventCoverageWhenReportIsMissingOrNotRun() {
        Project project = project()

        BuildConventionReport.write(project)
        Map skipped = readReport()
        assert skipped.checks.architecture == 'SKIPPED'
        assert skipped.checks.coverageReport == 'SKIPPED'
        assert skipped.coverage == [line: null, branch: null]

        project.tasks.getByName('jacocoTestReport').state.outcome = TaskExecutionOutcome.EXECUTED
        BuildConventionReport.write(project)
        assert readReport().checks.coverageReport == 'SKIPPED'
        assert readReport().coverage == [line: null, branch: null]

        File noSourceDir = new File(tempDir, 'no-source')
        noSourceDir.mkdirs()
        Project noSourceProject = ProjectBuilder.builder().withProjectDir(noSourceDir).build()
        noSourceProject.pluginManager.apply('java')
        noSourceProject.pluginManager.apply(LintConventionPlugin)
        noSourceProject.tasks.getByName('jacocoTestReport').state.outcome = TaskExecutionOutcome.NO_SOURCE
        BuildConventionReport.write(noSourceProject)
        assert new JsonSlurper().parse(new File(noSourceDir, 'build/reports/build-convention/report.json'))
                .checks.coverageReport == 'NOT_APPLIED'
    }

    @Test
    void calculatesMutationScoreFromPitXml() {
        File xml = new File(tempDir, 'mutations.xml')
        xml.text = '''<mutations>
                <mutation detected="true"/>
                <mutation detected="true"/>
                <mutation detected="false"/>
                <mutation detected="false"/>
                </mutations>'''.stripIndent()
        def method = BuildConventionReport.getDeclaredMethod('mutationScore', File)
        method.accessible = true

        assert method.invoke(null, xml) == 0.5
        assert method.invoke(null, new File(tempDir, 'missing.xml')) == null
        xml.text = '<mutations/>'
        assert method.invoke(null, xml) == null
    }

    private Project project() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        project.pluginManager.apply('java')
        project.pluginManager.apply(LintConventionPlugin)
        project
    }

    private Map readReport() {
        new JsonSlurper().parse(new File(tempDir, 'build/reports/build-convention/report.json')) as Map
    }
}
