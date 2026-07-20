package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.JavaSourceAstInspector
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

class PackageTopologyConventionValidator {
    private static final Set<String> CONTEXT_LAYER_SEGMENTS = [
            'domain',
            'application',
            'adapter'
    ] as Set
    private static final Set<String> RESERVED_CONTEXT_NAMES = [
            'common',
            'config',
            'global',
            'infrastructure',
            'shared'
    ] as Set
    private static final Set<String> DOMAIN_CHILD_SEGMENTS = [
            'model',
            'event',
            'exception'
    ] as Set
    private static final Set<String> APPLICATION_CHILD_SEGMENTS = [
            'port',
            'exception',
            'service'
    ] as Set
    private static final Set<String> APPLICATION_PORT_CHILD_SEGMENTS = [
            'in',
            'out'
    ] as Set
    private static final Set<String> ADAPTER_CHILD_SEGMENTS = [
            'in',
            'out'
    ] as Set
    private static final Set<String> ADAPTER_IN_CHILD_SEGMENTS = [
            'bootstrap',
            'event',
            'messaging',
            'scheduler',
            'web'
    ] as Set

    static List<String> validate(Project project) {
        return validate(project, new HexagonalConventionExtension())
    }

    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        List<File> mainSourceFiles = SourceInspector.collectMainSourceFiles(project).findAll { File file ->
            file.name.endsWith('.java')
        }
        List<String> violations = []
        Map<String, JavaSourceAstInspector.Inspection> parsedInspections =
                JavaSourceAstInspector.inspectAll(mainSourceFiles)
        List<JavaSourceAstInspector.Inspection> inspections = parsedInspections.values().toList()
        inspections.findAll { inspection -> !inspection.valid }.each { inspection ->
            violations.add(
                    "${project.relativePath(inspection.file)} could not be parsed as Java source: "
                            + inspection.errors.join('; ')
            )
        }
        List<JavaSourceAstInspector.Inspection> validInspections = inspections.findAll { inspection ->
            inspection.valid
        }
        Set<String> applicationRootPackages = resolveApplicationRootPackages(validInspections, convention)

        if (!mainSourceFiles.isEmpty() && applicationRootPackages.isEmpty()) {
            violations.add('application base package could not be determined; declare a *Application bootstrap class or configure hexagonalConvention.basePackage')
            return violations
        }

        validInspections.each { inspection ->
            validateFile(
                    project,
                    inspection.file,
                    inspection.packageName,
                    inspection.primaryType().simpleName,
                    applicationRootPackages,
                    violations
            )
        }
        return violations
    }

    private static Set<String> resolveApplicationRootPackages(
            List<JavaSourceAstInspector.Inspection> inspections,
            HexagonalConventionExtension convention
    ) {
        if (convention.basePackage != null && !convention.basePackage.isBlank()) {
            return [convention.basePackage.strip()] as Set
        }
        return collectApplicationRootPackages(inspections)
    }

    private static void validateFile(
            Project project,
            File file,
            String packageName,
            String typeName,
            Set<String> applicationRootPackages,
            List<String> violations
    ) {
        if (packageName == null || packageName.isBlank()) {
            return
        }

        String path = project.relativePath(file)
        if (typeName != null && typeName.endsWith('Application') && applicationRootPackages.contains(packageName)) {
            return
        }

        String rootPackage = findApplicationRootPackage(packageName, applicationRootPackages)
        if (rootPackage == null) {
            if (!applicationRootPackages.isEmpty()) {
                violations.add("${path} package '${packageName}' must be under application root package '${applicationRootPackages.sort().join(', ')}'")
            }
            return
        }

        if (packageName == rootPackage) {
            violations.add("${path} root package may contain only the application bootstrap class")
            return
        }

        String relativePackage = packageName.substring(rootPackage.length() + 1)
        List<String> segments = relativePackage.split('\\.').toList()
        if (segments.isEmpty()) {
            return
        }

        if (segments.first() == 'global') {
            validateGlobalPackage(project, file, segments, violations)
            return
        }

        if (CONTEXT_LAYER_SEGMENTS.contains(segments.first())) {
            violations.add("${path} package must be context-first: use {context}/${segments.first()}..., not ${segments.first()}/{context}...")
            return
        }

        if (RESERVED_CONTEXT_NAMES.contains(segments.first())) {
            violations.add("${path} package '${segments.first()}' is not a bounded context; use a real context name before domain, application, or adapter")
            return
        }

        if (segments.size() < 2) {
            violations.add("${path} bounded context package '${segments.first()}' must contain domain, application, or adapter")
            return
        }

        String contextName = segments[0]
        String layerName = segments[1]
        if (!CONTEXT_LAYER_SEGMENTS.contains(layerName)) {
            violations.add("${path} package must follow {context}/domain, {context}/application, or {context}/adapter structure")
            return
        }

        if (segments.size() < 3) {
            violations.add("${path} ${contextName}.${layerName} package must declare a valid child package")
            return
        }

        validateLayerChildPackage(project, file, contextName, layerName, segments, violations)
    }

    private static void validateGlobalPackage(
            Project project,
            File file,
            List<String> segments,
            List<String> violations
    ) {
        if (segments.size() < 2 || !['exception', 'web'].contains(segments[1])) {
            violations.add("${project.relativePath(file)} global package must be limited to global.exception or global.web")
        }
    }

    private static void validateLayerChildPackage(
            Project project,
            File file,
            String contextName,
            String layerName,
            List<String> segments,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        String childName = segments[2]
        if (layerName == 'domain' && !DOMAIN_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.domain package must use model, event, or exception")
        }
        if (layerName == 'application' && !APPLICATION_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.application package must use port, exception, or service")
        }
        if (layerName == 'application' && childName == 'port') {
            if (segments.size() < 4 || !APPLICATION_PORT_CHILD_SEGMENTS.contains(segments[3])) {
                violations.add("${path} ${contextName}.application.port package must use in or out")
            }
        }
        if (layerName == 'adapter' && !ADAPTER_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.adapter package must use in or out")
        }
        if (layerName == 'adapter' && childName == 'in') {
            if (segments.size() < 4 || !ADAPTER_IN_CHILD_SEGMENTS.contains(segments[3])) {
                violations.add("${path} ${contextName}.adapter.in package must use bootstrap, event, messaging, scheduler, or web")
            }
        }
    }

    private static Set<String> collectApplicationRootPackages(
            List<JavaSourceAstInspector.Inspection> inspections
    ) {
        Set<String> rootPackages = []
        inspections.each { inspection ->
            String packageName = inspection.packageName
            String typeName = inspection.primaryType().simpleName
            if (typeName != null && typeName.endsWith('Application') && !packageName.isBlank()) {
                rootPackages.add(packageName)
            }
        }
        return rootPackages
    }

    private static String findApplicationRootPackage(String packageName, Set<String> applicationRootPackages) {
        applicationRootPackages
                .findAll { String rootPackage -> packageName == rootPackage || packageName.startsWith("${rootPackage}.") }
                .sort { String left, String right -> right.length() <=> left.length() }
                .find()
    }

}
