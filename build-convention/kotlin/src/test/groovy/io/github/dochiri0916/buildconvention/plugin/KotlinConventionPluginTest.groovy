package io.github.dochiri0916.buildconvention.plugin

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class KotlinConventionPluginTest {
    @Test
    @DisplayName('Kotlin 규칙 구현 후에는 실제 도메인 검증 태스크를 등록한다')
    void registersDomainChecks() {
        // given
        Project project = ProjectBuilder.builder().build()

        // when
        project.pluginManager.apply('io.github.dochiri0916.build-convention.kotlin')

        // then
        def domain = project.tasks.named('validateKotlinDomainConventions').get()
        assert domain.actions.size() == 1
        assert project.tasks.named('check').get().taskDependencies
                .getDependencies(project.tasks.named('check').get()).contains(domain)
        assert project.tasks.findByName('validateKotlinArchUnitArchitecture') != null
    }
}
