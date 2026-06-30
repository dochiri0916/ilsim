package com.dochiri.convention.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

class LintConventionPluginTest {
    @Test
    void 'check fails when required convention task is disabled'() {
        Project project = sampleProject()
        project.tasks.named('validateClaudeConventions').get().enabled = false

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('validateClaudeConventions')
    }

    @Test
    void 'check fails when quality task ignores failures'() {
        Project project = sampleProject()
        project.tasks.named('pmdMain').get().ignoreFailures = true

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('pmdMain')
    }

    private static Project sampleProject() {
        File projectDir = File.createTempDir('lint-convention-plugin-test', '')
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.pluginManager.apply('java')
        project.pluginManager.apply(LintConventionPlugin)
        return project
    }

    private static void executeCheckActions(Project project) {
        def checkTask = project.tasks.named('check').get()
        checkTask.actions.each { action ->
            action.execute(checkTask)
        }
    }
}
