package io.github.dochiri0916.buildconvention.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

class BuildConventionPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.pluginManager.apply(LintConventionPlugin)
    }
}
