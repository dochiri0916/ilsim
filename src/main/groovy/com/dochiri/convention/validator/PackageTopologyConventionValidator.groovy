package com.dochiri.convention.validator

import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.nio.charset.StandardCharsets

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
        List<File> mainSourceFiles = SourceInspector.collectMainSourceFiles(project).findAll { File file ->
            file.name.endsWith('.java')
        }
        Set<String> applicationRootPackages = collectApplicationRootPackages(mainSourceFiles)
        List<String> violations = []

        mainSourceFiles.each { File file ->
            String source = file.getText(StandardCharsets.UTF_8.name())
            validateFile(project, file, source, applicationRootPackages, violations)
        }
        return violations
    }

    private static void validateFile(
            Project project,
            File file,
            String source,
            Set<String> applicationRootPackages,
            List<String> violations
    ) {
        String packageName = SourceInspector.extractPackageName(source)
        if (packageName == null || packageName.isBlank()) {
            return
        }

        String path = project.relativePath(file)
        String typeName = extractTypeName(source)
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
        if (segments.size() < 2 || !['error', 'web'].contains(segments[1])) {
            violations.add("${project.relativePath(file)} global package must be limited to global.error or global.web")
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

    private static Set<String> collectApplicationRootPackages(List<File> mainSourceFiles) {
        Set<String> rootPackages = []
        mainSourceFiles.each { File file ->
            String source = file.getText(StandardCharsets.UTF_8.name())
            String packageName = SourceInspector.extractPackageName(source)
            String typeName = extractTypeName(source)
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

    private static String extractTypeName(String source) {
        def matcher = source =~ /(?m)^\s*(?:public\s+)?(?:(?:final|abstract)\s+)?(?:class|record|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\b/
        return matcher.find() ? matcher.group(1) : null
    }
}
