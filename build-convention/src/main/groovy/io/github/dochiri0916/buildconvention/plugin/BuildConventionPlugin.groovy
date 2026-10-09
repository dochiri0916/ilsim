package io.github.dochiri0916.buildconvention.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Keeps the original plugin ID working: it applies the Java conventions it always stood for. */
class BuildConventionPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.pluginManager.apply(LintConventionPlugin)
    }
}
