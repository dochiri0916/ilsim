package io.github.dochiri0916.buildconvention.plugin

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BuildConventionPluginTest {
    @TempDir
    File tempDir

    @Test
    @DisplayName('기존 플러그인 ID는 Java 컨벤션을 적용한다')
    void appliesJavaConventions() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        project.pluginManager.apply('java')

        // when
        project.pluginManager.apply('io.github.dochiri0916.build-convention')

        // then
        assert project.plugins.hasPlugin(LintConventionPlugin)
        assert project.extensions.findByName('hexagonalConvention') != null
        assert project.tasks.findByName('validateArchitectureConventions') != null
    }

    @Test
    @DisplayName('기존 ID와 Java 전용 ID를 함께 적용해도 컨벤션은 한 번만 적용된다')
    void appliesOnceWithBothPluginIds() {
        // given
        Project project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        project.pluginManager.apply('java')

        // when
        project.pluginManager.apply('io.github.dochiri0916.build-convention.java')
        project.pluginManager.apply('io.github.dochiri0916.build-convention')

        // then
        assert project.plugins.withType(LintConventionPlugin).size() == 1
        assert project.pluginManager.hasPlugin('io.github.dochiri0916.build-convention')
    }

    @Test
    @DisplayName('기존 플러그인 ID로 빌드하면 컨벤션 검증 태스크가 등록된다')
    void registersConventionTasksInBuild() {
        // given
        new File(tempDir, 'settings.gradle').text = "rootProject.name = 'sample'\n"
        new File(tempDir, 'build.gradle').text = '''
                plugins {
                    id 'java'
                    id 'io.github.dochiri0916.build-convention'
                }
                '''.stripIndent().trim() + System.lineSeparator()

        // when
        def result = GradleRunner.create()
                .withProjectDir(tempDir)
                .withArguments('tasks', '--all')
                .withPluginClasspath()
                .build()

        // then
        assert result.output.contains('validateArchitectureConventions')
        assert result.output.contains('generateBuildConventionReport')
    }

    @Test
    @DisplayName('플러그인 JAR 자원에 버전과 컨벤션 지침 묶음이 들어 있다')
    void bundlesVersionAndGuidance() {
        // given
        Properties properties = new Properties()

        // when
        BuildConventionPlugin.getResourceAsStream('/build-convention-version.properties').withCloseable {
            properties.load(it)
        }

        // then
        assert properties.getProperty('version') ==~ /\d+\.\d+\.\d+.*/
        ['conventions/README.md', 'conventions/coverage.json', 'templates/README.md', 'CONVENTION_ROLLOUT.md'].each {
            assert BuildConventionPlugin.getResource("/build-convention-guidance/${it}") != null
        }
    }
}
