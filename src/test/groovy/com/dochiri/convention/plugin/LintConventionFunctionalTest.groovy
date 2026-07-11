package com.dochiri.convention.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LintConventionFunctionalTest {

    @TempDir
    File tempDir

    @Test
    @DisplayName('실제 Gradle 프로젝트에서 아키텍처 검증 태스크를 실행한다')
    void runsArchitectureConventionTaskInRealGradleBuild() {
        // given
        writeBuildFiles()
        writeJava('com/example/TestApplication.java', '''
                package com.example;

                public class TestApplication {
                }
                ''')

        // when
        BuildResult result = runner('validateArchitectureConventions').build()

        // then
        assert result.task(':validateArchitectureConventions').outcome == TaskOutcome.SUCCESS
    }

    @Test
    @DisplayName('실제 Gradle 프로젝트의 Application 기술 의존 위반을 실패시킨다')
    void rejectsApplicationTechnicalDependencyInRealGradleBuild() {
        // given
        writeBuildFiles()
        writeJava('com/example/TestApplication.java', '''
                package com.example;

                public class TestApplication {
                }
                ''')
        writeJava('com/example/order/application/port/out/OrderQueryPort.java', '''
                package com.example.order.application.port.out;

                public interface OrderQueryPort {
                    org.springframework.data.domain.Page<String> findAll();
                }
                ''')

        // when
        BuildResult result = runner('validateArchitectureConventions').buildAndFail()

        // then
        assert result.output.contains('Architecture convention violations')
        assert result.output.contains('application must not depend on technical framework type')
    }

    private void writeBuildFiles() {
        new File(tempDir, 'settings.gradle').text = "rootProject.name = 'functional-test'\n"
        new File(tempDir, 'build.gradle').text = '''
                plugins {
                    id 'java'
                    id 'com.dochiri.lint-convention'
                }
                '''.stripIndent().trim() + System.lineSeparator()
    }

    private void writeJava(String path, String source) {
        File file = new File(tempDir, "src/main/java/${path}")
        file.parentFile.mkdirs()
        file.text = source.stripIndent().trim() + System.lineSeparator()
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(tempDir)
                .withArguments(arguments.toList())
                .withPluginClasspath()
                .forwardOutput()
    }
}
