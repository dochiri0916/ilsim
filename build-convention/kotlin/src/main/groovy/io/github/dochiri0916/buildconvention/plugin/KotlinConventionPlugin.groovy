package io.github.dochiri0916.buildconvention.plugin

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.integrity.KotlinConventionIntegrity
import io.github.dochiri0916.buildconvention.report.KotlinBuildConventionReport
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinSourceInspector
import io.github.dochiri0916.buildconvention.validator.architecture.ArchUnitArchitectureValidator
import io.github.dochiri0916.buildconvention.validator.domain.KotlinDomainConventionValidator
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

class KotlinConventionPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.pluginManager.apply('base')
        def convention = project.extensions.findByType(HexagonalConventionExtension) ?:
                project.extensions.create('hexagonalConvention', HexagonalConventionExtension)
        Set<File> roots = [] as Set
        def validate = project.tasks.register('validateKotlinDomainConventions') { task ->
            task.group = 'verification'
            task.description = 'Validates Kotlin domain models, value objects, collections, and exception contracts.'
            task.inputs.files(project.provider { KotlinSourceInspector.collect(project, roots) })
            task.doLast {
                Map result = KotlinDomainConventionValidator.analyze(project, KotlinSourceInspector.collect(project, roots), convention)
                project.extensions.extraProperties.set('kotlinConventionResult', result)
                if (!result.violations.isEmpty()) {
                    throw new GradleException("Kotlin domain convention violations:\n - ${result.violations.join('\n - ')}")
                }
            }
            task.extensions.extraProperties.set('kotlinConventionAction', task.actions.first())
        }
        def archUnit = project.tasks.register('validateKotlinArchUnitArchitecture') { task ->
            task.group = 'verification'
            task.description = 'Validates compiled Kotlin/JVM dependencies with the common ArchUnit rules.'
            task.doLast {
                SourceSetContainer sets = project.extensions.findByType(SourceSetContainer)
                List<File> dirs = sets?.findByName('main')?.output?.classesDirs?.files?.toList() ?: []
                project.extensions.extraProperties.set('kotlinArchUnitApplied', dirs.any { it.isDirectory() })
                List<String> violations = ArchUnitArchitectureValidator.validate(project, convention, dirs)
                if (!violations.isEmpty()) throw new GradleException("Kotlin architecture violations:\n - ${violations.join('\n - ')}")
            }
            task.extensions.extraProperties.set('kotlinConventionAction', task.actions.first())
        }
        project.pluginManager.withPlugin('java') {
            archUnit.configure { it.dependsOn(project.tasks.named('classes')) }
        }
        def report = project.tasks.register('generateKotlinBuildConventionReport') { task ->
            task.group = 'verification'
            task.doLast { KotlinBuildConventionReport.write(project) }
            task.extensions.extraProperties.set('kotlinConventionAction', task.actions.first())
        }
        project.tasks.named('check') { task ->
            task.dependsOn(validate, archUnit)
            task.finalizedBy(report)
            task.doFirst { KotlinConventionIntegrity.validate(project, convention) }
        }
        report.configure { it.mustRunAfter(project.tasks.named('check'), validate, archUnit) }
        validate.configure { it.finalizedBy(report) }
        archUnit.configure { it.finalizedBy(report) }
        project.afterEvaluate { roots.addAll(KotlinSourceInspector.sourceDirs(project)) }
        project.gradle.taskGraph.whenReady { graph ->
            roots.addAll(KotlinSourceInspector.sourceDirs(project))
            if (graph.hasTask(project.tasks.named('check').get()) || graph.hasTask(validate.get()) || graph.hasTask(archUnit.get())) {
                KotlinConventionIntegrity.validate(project, convention)
            }
        }
    }
}
