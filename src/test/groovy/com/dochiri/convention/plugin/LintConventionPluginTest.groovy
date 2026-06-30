package com.dochiri.convention.plugin

import com.dochiri.convention.extension.HexagonalConventionExtension
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

    @Test
    void 'check fails when convention enforcement flag is relaxed'() {
        Project project = sampleProject()
        project.extensions.getByType(HexagonalConventionExtension).enforceStrictClaudeConventions = false

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('enforceStrictClaudeConventions')
    }

    @Test
    void 'check fails when required task is excluded with dash x'() {
        Project project = sampleProject()
        project.gradle.startParameter.setExcludedTaskNames(['validateClaudeConventions'])

        try {
            GradleException exception = assertThrows(GradleException) {
                executeCheckActions(project)
            }

            assert exception.message.contains('Build convention verification must not be disabled')
            assert exception.message.contains('-x excluded tasks: validateClaudeConventions')
        } finally {
            project.gradle.startParameter.setExcludedTaskNames([])
        }
    }

    @Test
    void 'check fails when required task onlyIf skips execution'() {
        Project project = sampleProject()
        project.tasks.named('validateClaudeConventions').configure { task ->
            task.onlyIf { false }
        }

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('onlyIf-skipped tasks: validateClaudeConventions')
    }

    @Test
    void 'check fails when quality task source is emptied'() {
        Project project = sampleProject()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public record Order() {
                }
                ''')
        project.tasks.named('checkstyleMain').get().source = project.files()

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('empty source tasks: checkstyleMain')
    }

    @Test
    void 'check fails when coverage minimum is relaxed'() {
        Project project = sampleProject()
        project.extensions.getByType(HexagonalConventionExtension).domainLineCoverageMinimum = 0.10

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('domainLineCoverageMinimum=0.10 < 0.95')
    }

    private static Project sampleProject() {
        File projectDir = File.createTempDir('lint-convention-plugin-test', '')
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.pluginManager.apply('java')
        project.pluginManager.apply(LintConventionPlugin)
        return project
    }

    private static void writeJava(Project project, String path, String source) {
        File file = new File(project.projectDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }

    private static void executeCheckActions(Project project) {
        def checkTask = project.tasks.named('check').get()
        checkTask.actions.each { action ->
            action.execute(checkTask)
        }
    }
}
