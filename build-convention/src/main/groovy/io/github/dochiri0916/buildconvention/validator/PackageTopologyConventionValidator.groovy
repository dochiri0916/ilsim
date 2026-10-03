package io.github.dochiri0916.buildconvention.validator

import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.support.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.support.SourceInspector
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
            'configuration',
            'global',
            'infrastructure',
            'shared'
    ] as Set
    private static final Set<String> DOMAIN_CHILD_SEGMENTS = [
            'model',
            'vo',
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
    // UseCases live directly in port.in; Commands and Results either beside them or in these children.
    private static final Set<String> APPLICATION_PORT_IN_CHILD_SEGMENTS = [
            'command',
            'result'
    ] as Set
    private static final Set<String> WEB_ADAPTER_CHILD_SEGMENTS = [
            'request',
            'response',
            'exception'
    ] as Set
    private static final Set<String> ADAPTER_CHILD_SEGMENTS = [
            'in',
            'out'
    ] as Set
    private static final Set<String> ADAPTER_IN_CHILD_SEGMENTS = [
            'bootstrap',
            'web'
    ] as Set
    // Packages that may hold their own subpackages, and shared-kernel packages that may not.
    private static final Set<String> GLOBAL_TREE_PACKAGES = ['exception', 'persistence', 'configuration'] as Set
    private static final List<List<String>> GLOBAL_EXACT_PACKAGES = [
            ['domain'],
            ['domain', 'exception'],
            ['application', 'exception']
    ].asImmutable()

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

        applicationRootPackages.sort().each { String rootPackage ->
            String layerSegment = rootPackage.tokenize('.').find { String segment ->
                CONTEXT_LAYER_SEGMENTS.contains(segment)
            }
            if (layerSegment != null) {
                violations.add("application root package '${rootPackage}' must not contain layer segment '${layerSegment}'; "
                        + 'layer detection would be ambiguous')
            }
        }

        validInspections.each { inspection ->
            validateFile(
                    project,
                    inspection.file,
                    inspection.packageName,
                    inspection.primaryType().simpleName,
                    applicationRootPackages,
                    convention,
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
            HexagonalConventionExtension convention,
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

        if (!CONTEXT_LAYER_SEGMENTS.contains(segments[1]) && segments.size() >= 3
                && CONTEXT_LAYER_SEGMENTS.contains(segments[2])) {
            // A grouped context: {group}/{module}/{layer}..., such as catalog/product/domain.
            String moduleName = segments[1]
            if (RESERVED_CONTEXT_NAMES.contains(moduleName)) {
                violations.add("${path} package '${moduleName}' is not a bounded context module; use a real module name")
                return
            }
            segments = ["${segments[0]}.${moduleName}".toString()] + segments.drop(2)
        }

        String contextName = segments[0]
        String layerName = segments[1]
        if (!CONTEXT_LAYER_SEGMENTS.contains(layerName)) {
            violations.add("${path} package must follow {context}/domain, {context}/application, or {context}/adapter structure (a grouped context may add one module: {context}/{module}/domain)")
            return
        }

        if (segments.size() < 3) {
            // An aggregate root may sit directly in the domain package, beside its vo/model/exception packages.
            if (layerName != 'domain') {
                violations.add("${path} ${contextName}.${layerName} package must declare a valid child package")
            }
            return
        }

        validateLayerChildPackage(project, file, contextName, layerName, segments, convention, violations)
    }

    private static void validateGlobalPackage(
            Project project,
            File file,
            List<String> segments,
            List<String> violations
    ) {
        if (!isAllowedGlobalPackage(segments)) {
            violations.add("${project.relativePath(file)} global package must be limited to global.exception, global.persistence, global.configuration, global.domain(.exception), or global.application.exception")
        }
    }

    private static boolean isAllowedGlobalPackage(List<String> segments) {
        return segments.size() >= 2 && GLOBAL_TREE_PACKAGES.contains(segments[1])
                || GLOBAL_EXACT_PACKAGES.contains(segments.drop(1))
    }

    private static void validateLayerChildPackage(
            Project project,
            File file,
            String contextName,
            String layerName,
            List<String> segments,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        String childName = segments[2]
        if (layerName == 'domain' && !DOMAIN_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.domain package must use model, vo, event, or exception")
        }
        if (layerName == 'application' && !APPLICATION_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.application package must use port, exception, or service")
        }
        if (layerName == 'application' && childName == 'port') {
            if (segments.size() < 4 || !APPLICATION_PORT_CHILD_SEGMENTS.contains(segments[3])) {
                violations.add("${path} ${contextName}.application.port package must use in or out")
            }
        }
        if (layerName == 'application' && childName == 'port' && segments.size() >= 4 && segments[3] == 'in') {
            if (segments.size() > 5 || (segments.size() == 5 && !APPLICATION_PORT_IN_CHILD_SEGMENTS.contains(segments[4]))) {
                violations.add("${path} ${contextName}.application.port.in package may only add command or result")
            }
        }
        if (layerName == 'adapter' && !ADAPTER_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.adapter package must use in or out")
        }
        if (layerName == 'adapter' && childName == 'in') {
            if (segments.size() < 4 || !ADAPTER_IN_CHILD_SEGMENTS.contains(segments[3])) {
                violations.add("${path} ${contextName}.adapter.in package must use bootstrap or web")
            } else if (segments[3] == 'web') {
                validateWebAdapterChildPackage(path, contextName, segments, convention, violations)
            }
        }
    }

    private static void validateWebAdapterChildPackage(
            String path,
            String contextName,
            List<String> segments,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        List<String> children = segments.drop(4)
        if (convention.enforceMsaWebAdapterBoundary && !children.isEmpty()
                && ['external', 'internal'].contains(children.first())) {
            children = children.drop(1)
        }
        if (!children.isEmpty() && (children.size() > 1 || !WEB_ADAPTER_CHILD_SEGMENTS.contains(children.first()))) {
            violations.add("${path} ${contextName}.adapter.in.web package must use request, response, or exception")
        }
    }

    /** Root packages from the configured base package or the *Application bootstrap classes, read without parsing. */
    static Set<String> rootPackages(List<File> sourceFiles, HexagonalConventionExtension convention) {
        if (convention.basePackage != null && !convention.basePackage.isBlank()) {
            return [convention.basePackage.strip()] as Set
        }
        return sourceFiles.findAll { File file -> file.name.endsWith('Application.java') }
                .collect { File file -> SourceInspector.extractPackageName(file.getText('UTF-8')) }
                .findAll { String packageName -> !packageName.isBlank() }
                .toSet()
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
