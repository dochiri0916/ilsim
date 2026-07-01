package com.dochiri.convention.plugin

import com.dochiri.convention.extension.HexagonalConventionExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

class LintConventionPluginTest {
    @Test
    void 'plugin configures lombok dependencies for constructor injection'() {
        Project project = sampleProject()

        assert hasDependency(project, 'compileOnly', 'org.projectlombok', 'lombok')
        assert hasDependency(project, 'annotationProcessor', 'org.projectlombok', 'lombok')
        assert hasDependency(project, 'testCompileOnly', 'org.projectlombok', 'lombok')
        assert hasDependency(project, 'testAnnotationProcessor', 'org.projectlombok', 'lombok')
    }

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
    void 'check fails when test task is disabled'() {
        Project project = sampleProject()
        project.tasks.named('test').get().enabled = false

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('disabled tasks: test')
    }

    @Test
    void 'check fails when check dependencies are removed'() {
        Project project = sampleProject()
        project.tasks.named('check').get().setDependsOn([])

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('check missing dependencies')
        assert exception.message.contains('validateClaudeConventions')
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
    void 'check fails when quality task excludes source files'() {
        Project project = sampleProject()
        writeJava(project, 'com/example/order/domain/model/Order.java', '''
                package com.example.order.domain.model;

                public record Order() {
                }
                ''')
        writeJava(project, 'com/example/order/domain/model/HiddenOrder.java', '''
                package com.example.order.domain.model;

                public record HiddenOrder() {
                }
                ''')
        project.tasks.named('checkstyleMain').configure { task ->
            task.exclude '**/HiddenOrder.java'
        }

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('quality tasks missing source files')
        assert exception.message.contains('checkstyleMain')
        assert exception.message.contains('HiddenOrder.java')
    }

    @Test
    void 'check fails when pmd ruleset file is replaced'() {
        Project project = sampleProject()
        project.tasks.named('pmdMain').get().ruleSetFiles = project.files('config/pmd/ruleset.xml')

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('modified quality rule configurations')
        assert exception.message.contains('pmdMain.ruleSetFiles')
    }

    @Test
    void 'check fails when pmd built in rule sets are added'() {
        Project project = sampleProject()
        project.tasks.named('pmdMain').get().ruleSets = ['category/java/bestpractices.xml']

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('pmdMain.ruleSets')
    }

    @Test
    void 'check fails when checkstyle config file is replaced'() {
        Project project = sampleProject()
        project.tasks.named('checkstyleMain').get().configFile = project.file('config/checkstyle/checkstyle.xml')

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('checkstyleMain.configFile')
    }

    @Test
    void 'check fails when spotbugs filter file is replaced'() {
        Project project = sampleProject()
        project.tasks.named('spotbugsMain').get().excludeFilter.set(
                project.layout.projectDirectory.file('config/spotbugs/exclude.xml')
        )

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('spotbugsMain.excludeFilter')
    }

    @Test
    void 'check fails when spotbugs class dirs are narrowed'() {
        Project project = sampleProject()
        new File(project.projectDir, 'build/classes/java/main').mkdirs()
        project.tasks.named('spotbugsMain').get().classDirs.setFrom(project.files())

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('modified bytecode analysis inputs')
        assert exception.message.contains('spotbugsMain.classDirs')
    }

    @Test
    void 'check fails when jacoco class dirs are narrowed'() {
        Project project = sampleProject()
        File compiledClass = new File(project.projectDir, 'build/classes/java/main/com/example/Sample.class')
        compiledClass.parentFile.mkdirs()
        compiledClass.text = 'compiled'
        project.tasks.named('jacocoTestCoverageVerification').get().classDirectories.setFrom(project.files())

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('modified coverage inputs')
        assert exception.message.contains('jacocoTestCoverageVerification.classDirectories')
    }

    @Test
    void 'check fails when jacoco execution data is emptied'() {
        Project project = sampleProject()
        project.tasks.named('jacocoTestReport').get().executionData.setFrom(project.files())

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('jacocoTestReport.executionData')
    }

    @Test
    void 'check fails when test filters skip tests'() {
        Project project = sampleProject()
        project.tasks.named('test').configure { task ->
            task.filter.excludeTestsMatching '*'
        }

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('test.filter.excludePatterns')
    }

    @Test
    void 'check fails when test jacoco is disabled'() {
        Project project = sampleProject()
        project.tasks.named('test').get().extensions.getByType(JacocoTaskExtension).enabled = false

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('test.jacoco.enabled=false')
    }

    @Test
    void 'check fails when package segment is changed'() {
        Project project = sampleProject()
        project.extensions.getByType(HexagonalConventionExtension).domainPackageSegment = 'not-domain'

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('domainPackageSegment=not-domain != domain')
    }

    @Test
    void 'check fails when convention exception list is used'() {
        Project project = sampleProject()
        project.extensions.getByType(HexagonalConventionExtension).domainStaticFactoryExceptions = ['Order']

        GradleException exception = assertThrows(GradleException) {
            executeCheckActions(project)
        }

        assert exception.message.contains('Build convention verification must not be disabled')
        assert exception.message.contains('domainStaticFactoryExceptions=[Order]')
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

    private static boolean hasDependency(Project project, String configurationName, String group, String name) {
        project.configurations.getByName(configurationName).dependencies.any { dependency ->
            dependency.group == group && dependency.name == name
        }
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
