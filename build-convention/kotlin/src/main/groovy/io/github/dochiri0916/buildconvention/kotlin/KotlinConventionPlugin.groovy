package io.github.dochiri0916.buildconvention.kotlin

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Reserves the Kotlin plugin ID. It fails instead of passing a project that no Kotlin rule has checked. */
class KotlinConventionPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        throw new GradleException(
                'Build Convention has no Kotlin rules yet. '
                        + "Remove 'io.github.dochiri0916.build-convention.kotlin' until they are defined."
        )
    }
}
