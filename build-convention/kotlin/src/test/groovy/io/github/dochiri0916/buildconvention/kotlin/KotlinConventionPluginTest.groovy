package io.github.dochiri0916.buildconvention.kotlin

import static org.junit.jupiter.api.Assertions.assertThrows

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class KotlinConventionPluginTest {
    @Test
    @DisplayName('Kotlin 규칙이 정의되기 전에는 적용을 통과시키지 않고 실패한다')
    void failsUntilKotlinRulesExist() {
        // given
        Project project = ProjectBuilder.builder().build()

        // when
        GradleException failure = assertThrows(GradleException) {
            project.pluginManager.apply('io.github.dochiri0916.build-convention.kotlin')
        }

        // then
        assert failure.cause instanceof GradleException
        assert failure.cause.message.contains('no Kotlin rules yet')
    }
}
