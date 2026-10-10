package io.github.dochiri0916.buildconvention.integrity

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.report.KotlinBuildConventionReport
import org.gradle.api.GradleException
import org.gradle.api.Project

/** These checks run before the task graph, so -x and disabled task actions cannot silently pass check. */
final class KotlinConventionIntegrity {
    static final List<String> REQUIRED = ['validateKotlinDomainConventions', 'validateKotlinArchUnitArchitecture',
                                          'generateKotlinBuildConventionReport'].asImmutable()

    private KotlinConventionIntegrity() {
    }

    static void validate(Project project, HexagonalConventionExtension convention) {
        List<String> violations = []
        (REQUIRED + ['check']).each { String name ->
            def task = project.tasks.findByName(name)
            if (task == null || !task.enabled) violations.add("disabled/missing task ${name}")
            if (REQUIRED.contains(name) && task != null && (task.actions.size() != 1 ||
                    !task.actions.contains(task.extensions.extraProperties.get('kotlinConventionAction')))) {
                violations.add("modified actions ${name}")
            }
            if (task != null) {
                try {
                    if (!task.onlyIf.isSatisfiedBy(task)) violations.add("onlyIf-skipped task ${name}")
                } catch (RuntimeException ignored) {
                    violations.add("unevaluable onlyIf ${name}")
                }
            }
            String path = project.path == ':' ? ":${name}" : "${project.path}:${name}"
            if (project.gradle.startParameter.excludedTaskNames.any { it in [name, path, path.substring(1)] }) {
                violations.add("-x excluded task ${name}")
            }
        }
        def check = project.tasks.findByName('check')
        Set<String> dependencies = check?.taskDependencies?.getDependencies(check)?.collect { it.name }?.toSet() ?: [] as Set
        if (!dependencies.containsAll(REQUIRED.take(2))) violations.add('removed check dependencies')
        def report = project.tasks.findByName('generateKotlinBuildConventionReport')
        (REQUIRED.take(2) + ['check']).each { String name ->
            def task = project.tasks.findByName(name)
            if (task != null && !task.finalizedBy.getDependencies(task).contains(report)) {
                violations.add("removed report finalizer ${name}")
            }
        }
        if (!convention.enforceDomainEntitySeparation || !convention.enforceDomainStaticFactoryMethod ||
                !convention.enforceCrossContextIdentifierIsolation) violations.add('relaxed domain convention flags')
        if (!violations.isEmpty()) {
            project.extensions.extraProperties.set('kotlinIntegrityFailure', violations)
            KotlinBuildConventionReport.write(project)
            throw new GradleException('Build convention verification must not be disabled. ' + violations.join('; '))
        }
        project.extensions.extraProperties.set('kotlinIntegrityPassed', true)
    }
}
