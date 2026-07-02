package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class ClaudeConventionValidator {
    private static final Set<String> JPA_RELATION_ANNOTATIONS = [
            'ManyToOne',
            'OneToMany',
            'OneToOne',
            'ManyToMany'
    ] as Set
    private static final Set<String> WEB_ERROR_TYPES = [
            'ProblemDetail',
            'ErrorResponse',
            'ErrorResponseException',
            'ResponseEntityExceptionHandler',
            'HttpStatus',
            'HttpStatusCode'
    ] as Set
    private static final Set<String> TECHNICAL_EXCEPTION_PACKAGES = [
            'jakarta.persistence',
            'javax.persistence',
            'org.hibernate',
            'org.springframework.dao',
            'org.springframework.web.client',
            'org.springframework.web.reactive.function.client',
            'feign',
            'okhttp3',
            'retrofit2',
            'software.amazon.awssdk',
            'com.amazonaws',
            'org.apache.http'
    ] as Set
    private static final Set<String> TECHNICAL_EXCEPTION_TYPES = [
            'AmazonClientException',
            'AmazonServiceException',
            'ConnectException',
            'DataAccessException',
            'FeignException',
            'FileNotFoundException',
            'HibernateException',
            'HttpClientErrorException',
            'HttpServerErrorException',
            'IOException',
            'JDBCException',
            'PersistenceException',
            'RestClientException',
            'SdkException',
            'SocketException',
            'SocketTimeoutException',
            'SQLException',
            'SQLIntegrityConstraintViolationException',
            'SQLTimeoutException',
            'TimeoutException',
            'WebClientException',
            'WebClientResponseException'
    ] as Set
    private static final Set<String> TEST_METHOD_ANNOTATIONS = [
            'Test',
            'ParameterizedTest',
            'RepeatedTest',
            'TestFactory',
            'TestTemplate'
    ] as Set
    private static final Set<String> CONTEXT_LAYER_SEGMENTS = [
            'domain',
            'application',
            'adapter'
    ] as Set
    private static final Set<String> RESERVED_CONTEXT_NAMES = [
            'common',
            'config',
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
    private static final Set<String> ADAPTER_CHILD_SEGMENTS = [
            'in',
            'out'
    ] as Set
    private static final Set<String> RAW_SCALAR_TYPES = [
            'String',
            'java.lang.String',
            'boolean',
            'Boolean',
            'java.lang.Boolean',
            'byte',
            'Byte',
            'java.lang.Byte',
            'short',
            'Short',
            'java.lang.Short',
            'int',
            'Integer',
            'java.lang.Integer',
            'long',
            'Long',
            'java.lang.Long',
            'float',
            'Float',
            'java.lang.Float',
            'double',
            'Double',
            'java.lang.Double',
            'char',
            'Character',
            'java.lang.Character'
    ] as Set
    private static final Set<String> GENERIC_API_DTO_NAMES = [
            'ApiRequest',
            'ApiResponse',
            'BaseRequest',
            'BaseResponse',
            'CommonRequest',
            'CommonResponse',
            'DefaultRequest',
            'DefaultResponse'
    ] as Set

    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        List<String> violations = []
        List<File> mainSourceFiles = SourceInspector.collectMainSourceFiles(project).findAll { File file ->
            file.name.endsWith('.java')
        }
        Set<String> applicationRootPackages = collectApplicationRootPackages(mainSourceFiles)
        validateNoMessageBundleResources(project, violations)

        mainSourceFiles.each { File file ->
            String source = file.getText(StandardCharsets.UTF_8.name())
            String packageName = SourceInspector.extractPackageName(source)
            validateCommonPackageUsage(project, file, source, packageName, violations)
            validateNoElseUsage(project, file, source, violations)
            validateNoQualityToolSuppressUsage(project, file, source, violations)
            validateNoI18nOrValueInjection(project, file, source, violations)
            validateExceptionMessageLanguage(project, file, source, violations)

            TypeDeclaration type = TypeDeclaration.from(source)
            if (type == null) {
                return
            }

            validateSingleResponsibility(project, file, source, packageName, type, convention, violations)
            validateTechnicalAnnotationPlacement(project, file, source, packageName, type, convention, violations)
            validateWebErrorTypePlacement(project, file, source, packageName, convention, violations)
            validatePackageTopology(project, file, packageName, type, applicationRootPackages, violations)
            validateTypePackageConvention(project, file, packageName, type, convention, violations)
            validateSpringComponentRegistration(project, file, source, packageName, type, convention, violations)
            validateExceptionArchitecture(project, file, source, packageName, type, convention, violations)

            if (SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
                validateDomain(project, file, source, packageName, type, convention, violations)
            }

            if (SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)) {
                validateApplication(project, file, source, packageName, type, convention, violations)
            }

            if (SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)) {
                validatePresentation(project, file, source, type, convention, violations)
            }

            if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)) {
                validateInfrastructure(project, file, source, packageName, type, violations)
            }

            if (SourceInspector.isEntityClass(source)) {
                validateJpaEntity(project, file, source, packageName, type, convention, violations)
            }

            if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)
                    && type.name.endsWith('Mapper')) {
                validateMapper(project, file, source, type, violations)
            }

            if (type.name.endsWith('Controller') || hasAnnotation(source, 'RestController')) {
                validateController(project, file, source, convention, violations)
            }
        }
        validateTestConventions(project, violations)

        return violations
    }

    private static Set<String> collectApplicationRootPackages(List<File> mainSourceFiles) {
        mainSourceFiles.collect { File file ->
            String source = file.getText(StandardCharsets.UTF_8.name())
            TypeDeclaration type = TypeDeclaration.from(source)
            if (type == null || !type.name.endsWith('Application')) {
                return null
            }
            SourceInspector.extractPackageName(source)
        }.findAll { String packageName ->
            packageName != null && !packageName.isBlank()
        }.toSet()
    }

    private static void validateCommonPackageUsage(
            Project project,
            File file,
            String source,
            String packageName,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (SourceInspector.isInLayer(packageName, 'common') || hasPathSegment(path, 'common')) {
            violations.add("${path} common package is not allowed; use a shared module or context-local package")
        }
        SourceInspector.extractImports(source).each { String imported ->
            if (SourceInspector.isInLayer(imported, 'common')) {
                violations.add("${path} must not import common package '${imported}'")
            }
        }
    }

    private static boolean hasPathSegment(String path, String segment) {
        return path.replace(File.separatorChar, '/' as char).split('/').contains(segment)
    }

    private static void validateNoMessageBundleResources(Project project, List<String> violations) {
        File resourcesDir = project.file('src/main/resources')
        if (!resourcesDir.exists()) {
            return
        }

        project.fileTree(resourcesDir) {
            include '**/messages*.properties'
        }.files.each { File file ->
            violations.add("${project.relativePath(file)} must not use MessageSource message bundle resources; use code-based ApiErrorMessageProvider catalog")
        }
    }

    private static void validateNoElseUsage(Project project, File file, String source, List<String> violations) {
        String codeOnly = stripCommentsAndStrings(source)
        if ((codeOnly =~ /\belse\b/).find()) {
            String path = project.relativePath(file)
            violations.add("${path} must not use else; use guard clauses and early return")
        }
    }

    private static void validateNoQualityToolSuppressUsage(
            Project project,
            File file,
            String source,
            List<String> violations
    ) {
        if (hasQualityToolSuppressWarnings(source)) {
            violations.add("${project.relativePath(file)} must not suppress PMD/Checkstyle/SpotBugs warnings; fix the violation or convention rule")
        }
    }

    private static void validateNoI18nOrValueInjection(
            Project project,
            File file,
            String source,
            List<String> violations
    ) {
        String searchableSource = stripCommentsAndStrings(source)
        String path = project.relativePath(file)
        if (importsOrUsesType(source, searchableSource, 'org.springframework.context.MessageSource', 'MessageSource')) {
            violations.add("${path} must not use MessageSource; use code-based ApiErrorMessageProvider catalog")
        }
        if ((searchableSource =~ /(?m)@\s*(?:org\.springframework\.beans\.factory\.annotation\.)?Value\b/).find()) {
            violations.add("${path} must not use @Value; bind configuration with record @ConfigurationProperties")
        }
    }

    private static void validateSingleResponsibility(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (type.name ==~ /.*(Manager|Helper|Utils?)$/) {
            violations.add("${path} type '${type.name}' has an ambiguous responsibility name; use a specific domain, use case, port, adapter, mapper, or factory name")
        }

        if (SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)
                && packageName.contains('.service')
                && type.kind == 'class') {
            int useCaseCount = countImplementedInterfacesEndingWith(source, type.name, 'UseCase')
            if (useCaseCount > 1) {
                violations.add("${path} application service '${type.name}' must implement exactly one UseCase for SRP")
            }
        }

        if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)
                && type.kind == 'class'
                && !SourceInspector.isEntityClass(source)
                && !type.name.endsWith('Mapper')) {
            int portCount = countImplementedInterfacesEndingWith(source, type.name, 'Port')
            if (portCount > 1) {
                violations.add("${path} adapter '${type.name}' must implement only one outbound Port for SRP")
            }
        }
    }

    private static void validateTechnicalAnnotationPlacement(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        boolean outboundAdapter = SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)
        boolean inboundWebAdapter = SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
        boolean globalError = isGlobalErrorPackage(packageName)
        boolean applicationService = SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)
                && packageName.contains('.service')

        ['Entity', 'Table', 'Repository'].each { String annotation ->
            if (hasAnnotation(source, annotation) && !outboundAdapter) {
                violations.add("${path} @${annotation} is only allowed in adapter.out")
            }
        }

        ['RestController', 'Controller', 'RestControllerAdvice', 'ControllerAdvice'].each { String annotation ->
            if (hasAnnotation(source, annotation) && !inboundWebAdapter && !globalError) {
                violations.add("${path} @${annotation} is only allowed in adapter.in.web or global.error")
            }
        }

        if (hasAnnotation(source, 'Transactional') && !applicationService) {
            violations.add("${path} @Transactional is only allowed on concrete application services")
        }
        if (applicationService && hasClassLevelAnnotation(source, type.name, 'Transactional')) {
            violations.add("${path} @Transactional must be declared on public application service methods, not on the class")
        }
        if (applicationService) {
            findPublicMethodNamesWithoutAnnotation(source, 'Transactional').each { String methodName ->
                violations.add("${path} public application service method '${methodName}' must declare @Transactional")
            }
        }
    }

    private static void validateWebErrorTypePlacement(
            Project project,
            File file,
            String source,
            String packageName,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        boolean allowedWebErrorPackage = SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
                || isGlobalErrorPackage(packageName)
        if (allowedWebErrorPackage || !usesAnyType(source, WEB_ERROR_TYPES)) {
            return
        }

        String path = project.relativePath(file)
        violations.add("${path} Spring Web error types are only allowed in adapter.in.web or global.error")
    }

    private static void validatePackageTopology(
            Project project,
            File file,
            String packageName,
            TypeDeclaration type,
            Set<String> applicationRootPackages,
            List<String> violations
    ) {
        if (packageName == null || packageName.isBlank()) {
            return
        }

        String path = project.relativePath(file)
        if (type.name.endsWith('Application') && applicationRootPackages.contains(packageName)) {
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
            if (segments.size() < 2 || !['error', 'web'].contains(segments[1])) {
                violations.add("${path} global package must be limited to global.error or global.web")
            }
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

        validateLayerChildPackage(project, file, contextName, layerName, segments[2], violations)
    }

    private static void validateLayerChildPackage(
            Project project,
            File file,
            String contextName,
            String layerName,
            String childName,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (layerName == 'domain' && !DOMAIN_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.domain package must use model, event, or exception")
        }
        if (layerName == 'application' && !APPLICATION_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.application package must use port, exception, or service")
        }
        if (layerName == 'adapter' && !ADAPTER_CHILD_SEGMENTS.contains(childName)) {
            violations.add("${path} ${contextName}.adapter package must use in or out")
        }
    }

    private static void validateSpringComponentRegistration(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)

        if (type.name.endsWith('ContextConfig')) {
            violations.add("${path} context-specific ContextConfig is not allowed; register application services and adapters as Spring components")
        }
        if (isWebMvcConfiguration(source) && instantiatesWebMvcExtension(source)) {
            violations.add("${path} web configuration must inject Interceptor/ArgumentResolver components instead of creating them with new")
        }

        if (SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)
                && packageName.contains('.service')
                && type.kind == 'class'
                && type.name.endsWith('Service')
                && !hasAnnotation(source, 'Service')) {
            violations.add("${path} application service '${type.name}' must declare @Service")
        }

        if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)
                && type.kind == 'class'
                && !isAbstractClass(source, type.name)
                && !SourceInspector.isEntityClass(source)
                && !type.name.endsWith('Mapper')
                && !hasAnyAnnotation(source, ['Component', 'Repository'])) {
            violations.add("${path} outbound adapter '${type.name}' must declare @Component or @Repository instead of being wired by ContextConfig")
        }

        if (isSpringComponentType(source, packageName, type, convention)) {
            if (usesAutowiredInjection(source)) {
                violations.add("${path} Spring component '${type.name}' must use final fields with @RequiredArgsConstructor instead of @Autowired injection")
            }
            if (hasPrivateFinalInstanceField(source) && !hasAnnotation(source, 'RequiredArgsConstructor')) {
                violations.add("${path} Spring component '${type.name}' with final dependencies must declare @RequiredArgsConstructor")
            }
            if (hasNonFinalPrivateInstanceField(source) && hasDependencyField(source)) {
                violations.add("${path} Spring component '${type.name}' dependencies must be private final fields")
            }
        }
    }

    private static void validateExceptionArchitecture(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        boolean domain = SourceInspector.isInLayer(packageName, convention.domainPackageSegment)
        boolean application = SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)
        boolean outboundPort = application && packageName.contains('.port.out')
        boolean globalError = isGlobalErrorPackage(packageName)
        boolean webAdapter = SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)

        if ((domain || application || outboundPort) && exposesTechnicalException(source)) {
            violations.add("${path} domain/application/outbound port must not expose DB/HTTP/SDK/Spring technical exception types")
        }

        if ((globalError || webAdapter) && exposesExceptionMessageAsProblemDetail(source)) {
            violations.add("${path} must not expose exception.getMessage() as ProblemDetail detail")
        }

        if ((globalError || webAdapter) && hardCodesProblemDetailTitleOrDetail(source)) {
            violations.add("${path} must resolve user-facing ProblemDetail title/detail through code-based message catalog")
        }

        if (globalError && type.name == 'GlobalExceptionHandler'
                && importsDomainOrApplicationException(source, convention)) {
            violations.add("${path} GlobalExceptionHandler must delegate domain/application exception mapping to ApiExceptionMapper")
        }

        if (implementsApiExceptionMapper(source) && !globalError && !webAdapter) {
            violations.add("${path} ApiExceptionMapper implementations must live in adapter.in.web or global.error")
        }
    }

    private static void validateTestConventions(Project project, List<String> violations) {
        File testJavaDir = project.file('src/test/java')
        if (!testJavaDir.exists()) {
            return
        }

        project.fileTree(testJavaDir) {
            include '**/*.java'
        }.files.each { File file ->
            String source = file.getText(StandardCharsets.UTF_8.name())
            validateJavaTestFile(project, file, source, violations)
        }
    }

    private static void validateJavaTestFile(
            Project project,
            File file,
            String source,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (hasDisabledTestAnnotation(source)) {
            violations.add("${path} tests must not use @Disabled; fix or delete skipped tests")
        }

        def matcher = source =~ /(?ms)((?:^\s*@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s*)+)\s*(?:public|protected|private)?\s*(?:final\s+)?(?:void|[A-Za-z_][A-Za-z0-9_$.<>]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^)]*\)\s*(?:throws\s+[^{]+)?\{/
        while (matcher.find()) {
            String annotations = matcher.group(1)
            String methodName = matcher.group(2)
            if (!hasTestMethodAnnotation(annotations)) {
                continue
            }

            validateDisplayName(project, file, annotations, methodName, violations)

            int bodyStart = matcher.end() - 1
            int bodyEnd = findMatchingBrace(source, bodyStart)
            if (bodyEnd < 0) {
                continue
            }
            String body = source.substring(bodyStart + 1, bodyEnd)
            validateGivenWhenThen(project, file, body, methodName, violations)
            validateTestAssertionQuality(project, file, body, methodName, violations)
        }
    }

    private static void validateDisplayName(
            Project project,
            File file,
            String annotations,
            String methodName,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        def matcher = annotations =~ /@(?:[A-Za-z_][A-Za-z0-9_]*\.)?DisplayName\s*\(\s*"([^"]*)"\s*\)/
        if (!matcher.find()) {
            violations.add("${path} test method '${methodName}' must declare @DisplayName in Korean")
            return
        }

        String displayName = matcher.group(1)
        if (!containsKorean(displayName)) {
            violations.add("${path} test method '${methodName}' @DisplayName must be written in Korean")
        }
    }

    private static void validateGivenWhenThen(
            Project project,
            File file,
            String body,
            String methodName,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        PhaseComment given = findPhaseComment(body, 'given')
        PhaseComment when = findPhaseComment(body, 'when')
        PhaseComment then = findPhaseComment(body, 'then')
        PhaseComment whenThen = findWhenThenComment(body)

        if (given == null) {
            violations.add("${path} test method '${methodName}' must include '// given'")
        }
        if (when == null && whenThen == null) {
            violations.add("${path} test method '${methodName}' must include '// when' or '// when & then'")
        }
        if (then == null && whenThen == null) {
            violations.add("${path} test method '${methodName}' must include '// then' or '// when & then'")
        }

        if (given != null && when != null && then != null) {
            validateSeparatedGivenWhenThen(project, file, body, methodName, given, when, then, violations)
            return
        }

        if (given != null && whenThen != null) {
            validateCombinedWhenThen(project, file, body, methodName, given, whenThen, violations)
        }
    }

    private static void validateSeparatedGivenWhenThen(
            Project project,
            File file,
            String body,
            String methodName,
            PhaseComment given,
            PhaseComment when,
            PhaseComment then,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (!(given.start < when.start && when.start < then.start)) {
            violations.add("${path} test method '${methodName}' must order comments as // given, // when, // then")
            return
        }

        validateNonEmptyTestSection(project, file, methodName, 'given', body.substring(given.end, when.start), violations)
        validateNonEmptyTestSection(project, file, methodName, 'when', body.substring(when.end, then.start), violations)
        validateNonEmptyTestSection(project, file, methodName, 'then', body.substring(then.end), violations)

        if (!hasObservableAssertion(body.substring(then.end))) {
            violations.add("${path} test method '${methodName}' // then section must assert observable result")
        }
    }

    private static void validateCombinedWhenThen(
            Project project,
            File file,
            String body,
            String methodName,
            PhaseComment given,
            PhaseComment whenThen,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (!(given.start < whenThen.start)) {
            violations.add("${path} test method '${methodName}' must order comments as // given, // when & then")
            return
        }

        validateNonEmptyTestSection(project, file, methodName, 'given', body.substring(given.end, whenThen.start), violations)
        String whenThenBody = body.substring(whenThen.end)
        validateNonEmptyTestSection(project, file, methodName, 'when & then', whenThenBody, violations)

        if (!hasObservableAssertion(whenThenBody)) {
            violations.add("${path} test method '${methodName}' // when & then section must assert observable result")
        }
    }

    private static void validateNonEmptyTestSection(
            Project project,
            File file,
            String methodName,
            String sectionName,
            String sectionBody,
            List<String> violations
    ) {
        if (hasExecutableCode(sectionBody)) {
            return
        }
        String path = project.relativePath(file)
        violations.add("${path} test method '${methodName}' // ${sectionName} section must contain code; do not leave placeholder comments")
    }

    private static void validateTestAssertionQuality(
            Project project,
            File file,
            String body,
            String methodName,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (usesJUnitAssumption(body)) {
            violations.add("${path} test method '${methodName}' must not use JUnit assumptions to skip execution")
            return
        }

        boolean observableAssertion = hasObservableAssertion(body)
        if (observableAssertion) {
            return
        }

        if (hasNoExceptionOnlyAssertion(body)) {
            violations.add("${path} test method '${methodName}' must not rely only on no-exception assertions")
            return
        }

        if (hasMockVerification(body)) {
            violations.add("${path} test method '${methodName}' must not verify mocks without result/state/exception assertions")
            return
        }

        violations.add("${path} test method '${methodName}' must assert observable result, state, stored value, event, or exception")
    }

    private static void validateTypePackageConvention(
            Project project,
            File file,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)

        if (type.name.endsWith('Controller')) {
            boolean webAdapter = SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
                    || packageName.contains('.adapter.in.web')
            if (!webAdapter) {
                violations.add("${path} controller '${type.name}' must live in adapter.in.web package")
            }
        }

        boolean globalError = isGlobalErrorPackage(packageName)
        if ((type.name.endsWith('Request') || type.name.endsWith('Response'))
                && !SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
                && !packageName.contains('.adapter.in.web')
                && !globalError) {
            violations.add("${path} API DTO '${type.name}' must live in adapter.in.web package")
        }
        if (type.name.endsWith('Request') && SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
                && !packageName.contains('.request')) {
            violations.add("${path} request DTO '${type.name}' must live in adapter.in.web.request package")
        }
        if (type.name.endsWith('Response') && SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
                && !packageName.contains('.response')) {
            violations.add("${path} response DTO '${type.name}' must live in adapter.in.web.response package")
        }
        if ((type.name.endsWith('Request') || type.name.endsWith('Response'))
                && SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
                && !globalError) {
            if (type.kind != 'record') {
                violations.add("${path} API DTO '${type.name}' must be a record")
            }
            if (hasGenericApiDtoName(packageName, type.name)) {
                violations.add("${path} API DTO '${type.name}' must be responsibility-specific, not context-wide or generic")
            }
        }

        if (SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
            if (isDomainEventPackage(packageName, convention)) {
                if (type.name.endsWith('Event')) {
                    violations.add("${path} domain event '${type.name}' must use a past-tense name without Event suffix")
                }
                if (type.kind != 'record') {
                    violations.add("${path} domain event '${type.name}' must be a record")
                }
            } else if (type.name.endsWith('Event')) {
                violations.add("${path} domain event '${type.name}' must live in domain.event package")
            } else if (type.name.endsWith('Exception') || type.name.endsWith('ErrorCode')) {
                if (!packageName.contains('.exception')) {
                    violations.add("${path} domain exception support '${type.name}' must live in domain.exception package")
                }
                if (type.name.endsWith('ErrorCode') && type.kind != 'enum') {
                    violations.add("${path} domain error code '${type.name}' must be an enum")
                }
            } else if (packageName.contains('.exception')) {
                violations.add("${path} domain exception support '${type.name}' must end with Exception or ErrorCode")
            } else if (!packageName.contains('.model')) {
                violations.add("${path} domain model '${type.name}' must live in domain.model package")
            }
        }

        if (SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)) {
            if (type.name.endsWith('Exception') || type.name.endsWith('ErrorCode')) {
                if (!packageName.contains('.exception')) {
                    violations.add("${path} application exception support '${type.name}' must live in application.exception package")
                }
                if (type.name.endsWith('ErrorCode') && type.kind != 'enum') {
                    violations.add("${path} application error code '${type.name}' must be an enum")
                }
            } else if (packageName.contains('.exception')) {
                violations.add("${path} application exception support '${type.name}' must end with Exception or ErrorCode")
            }
        }
    }

    private static void validateApplication(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)

        if (dependsOnSpringSecurity(source)) {
            violations.add("${path} application must depend on a password port, not Spring Security types")
        }

        if (type.name.endsWith('Exception')) {
            if (!(source =~ /\bextends\s+RuntimeException\b/).find()) {
                violations.add("${path} application exception '${type.name}' must extend RuntimeException")
            }
            if (!hasSerialVersionUid(source)) {
                violations.add("${path} application exception '${type.name}' must declare serialVersionUID")
            }
            validateExceptionFactoryPolicy(path, 'application', type.name, source, violations)
        } else {
            if (throwsDirectExceptionConstruction(source)) {
                violations.add("${path} application must raise exceptions through static factory methods, not direct constructors")
            }
            if (constructsExceptionWithStringLiteral(source)) {
                violations.add("${path} application must use ErrorCode-based exceptions, not string message constructors")
            }
        }

        if (type.name.endsWith('UseCase') && !packageName.contains('.port.in')) {
            violations.add("${path} inbound port '${type.name}' must live in application..port.in package")
        }
        if ((type.name.endsWith('Command') || type.name.endsWith('Query') || type.name.endsWith('Result'))
                && !packageName.contains('.port.in')) {
            violations.add("${path} application DTO '${type.name}' must live in application..port.in package")
        }
        if ((type.name.endsWith('Port') || type.name.endsWith('RepositoryPort'))
                && !packageName.contains('.port.out')) {
            violations.add("${path} outbound port '${type.name}' must live in application..port.out package")
        }
        if (type.name.endsWith('Service') && !packageName.contains('.service')) {
            violations.add("${path} application service '${type.name}' must live in application..service package")
        }

        boolean globalErrorImported = false
        SourceInspector.extractImports(source).each { String imported ->
            if (SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)
                    || SourceInspector.isInLayer(imported, convention.presentationPackageSegment)
                    || isGlobalErrorPackage(imported)) {
                globalErrorImported = globalErrorImported || isGlobalErrorPackage(imported)
                violations.add("${path} application must not depend on adapter/global error layer '${imported}'")
            }
        }
        if (!globalErrorImported && referencesGlobalErrorPackage(source)) {
            violations.add("${path} application must not depend on global.error")
        }

        if (packageName.contains('.port.in')) {
            if (type.name.endsWith('UseCase') && type.kind != 'interface') {
                violations.add("${path} inbound port '${type.name}' must be an interface")
            }
            if ((type.name.endsWith('Command') || type.name.endsWith('Query') || type.name.endsWith('Result'))
                    && type.kind != 'record') {
                violations.add("${path} application DTO '${type.name}' must be a record")
            }
        }

        if (packageName.contains('.port.out')) {
            if (packageName.contains('.dto')) {
                if ((type.name.endsWith('ReadModel') || type.name.endsWith('Result')) && type.kind != 'record') {
                    violations.add("${path} outbound DTO '${type.name}' must be a record")
                }
            } else if (type.kind == 'interface'
                    && !(type.name.endsWith('Port') || type.name.endsWith('RepositoryPort'))) {
                violations.add("${path} outbound port '${type.name}' must end with Port or RepositoryPort")
            }
        }

        if (packageName.contains('.service')) {
            if (type.kind != 'class' || !type.name.endsWith('Service')) {
                violations.add("${path} application service '${type.name}' must be a class ending with Service")
            }
            if (!type.finalType) {
                violations.add("${path} application service '${type.name}' must be final")
            }
            if (!(source =~ /(?m)\bclass\s+${type.name}\s+implements\s+[^\\{;]*UseCase\b/).find()) {
                violations.add("${path} application service '${type.name}' must implement an inbound UseCase")
            }
        }
    }

    private static void validatePresentation(
            Project project,
            File file,
            String source,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        SourceInspector.extractImports(source).each { String imported ->
            if (SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)) {
                violations.add("${path} inbound web adapter must not import outbound adapter type '${imported}'")
            }
        }
        if ((type.name.endsWith('Request') || type.name.endsWith('Response'))
                && referencesInnerLayerPackage(source, convention)) {
            violations.add("${path} API DTO '${type.name}' must not expose domain/application types directly")
        }
    }

    private static void validateInfrastructure(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            List<String> violations
    ) {
        String path = project.relativePath(file)

        if (type.name.endsWith('JpaRepository')) {
            if (type.kind != 'interface') {
                violations.add("${path} JPA repository '${type.name}' must be an interface")
            }
            if (!packageName.contains('persistence') && !packageName.contains('repository')) {
                violations.add("${path} JPA repository '${type.name}' should live in a persistence/repository package")
            }
        }

        if (type.name.endsWith('Entity') && !packageName.contains('persistence')) {
            violations.add("${path} outbound adapter entity '${type.name}' must live in a persistence package")
        }

        if (type.name.endsWith('PersistenceAdapter')) {
            if (!hasAnnotation(source, 'Repository')) {
                violations.add("${path} persistence adapter '${type.name}' must declare @Repository")
            }
            if (!(source =~ /(?m)\bclass\s+${type.name}\s+implements\s+[A-Za-z0-9_,\s<>]*Port\b/).find()
                    && !(source =~ /(?m)\bclass\s+${type.name}\s+implements\s+[A-Za-z0-9_,\s<>]*RepositoryPort\b/).find()) {
                violations.add("${path} persistence adapter '${type.name}' must implement an outbound Port")
            }
        }
    }

    private static void validateDomain(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)

        if (type.kind == 'enum') {
            return
        }

        if (type.name.endsWith('Exception')) {
            if (!(source =~ /\bextends\s+RuntimeException\b/).find()) {
                violations.add("${path} domain exception '${type.name}' must extend RuntimeException")
            }
            if (!hasSerialVersionUid(source)) {
                violations.add("${path} domain exception '${type.name}' must declare serialVersionUID")
            }
            validateExceptionFactoryPolicy(path, 'domain', type.name, source, violations)
            return
        }

        if (type.kind == 'class') {
            if (type.name.endsWith('Service')) {
                if (!type.finalType) {
                    violations.add("${path} domain service '${type.name}' must be final")
                }
            } else if (!type.finalType) {
                violations.add("${path} domain class '${type.name}' must be a record or final aggregate root")
            }
        }

        if (hasForbiddenDomainAnnotation(source)) {
            violations.add("${path} domain must not use Spring/JPA/QueryDSL/Lombok annotations")
        }

        if (throwsJdkBasicException(source)) {
            violations.add("${path} domain must use domain-specific exceptions instead of JDK basic exceptions")
        }
        if (usesRequireNonNull(source)) {
            violations.add("${path} domain invariants must not use requireNonNull; throw a domain-specific exception factory instead")
        }
        if (throwsDirectExceptionConstruction(source)) {
            violations.add("${path} domain must raise exceptions through static factory methods, not direct constructors")
        }
        if (constructsExceptionWithStringLiteral(source)) {
            violations.add("${path} domain must use ErrorCode-based exceptions, not string message constructors")
        }

        boolean globalErrorImported = false
        SourceInspector.extractImports(source).each { String imported ->
            if (isGlobalErrorPackage(imported)) {
                globalErrorImported = true
                violations.add("${path} domain must not depend on global.error '${imported}'")
            }
        }
        if (!globalErrorImported && referencesGlobalErrorPackage(source)) {
            violations.add("${path} domain must not depend on global.error")
        }

        if ((source =~ /(?m)\b(?:Long|long)\s+[A-Za-z_][A-Za-z0-9_]*id\b/).find()) {
            violations.add("${path} domain must not declare DB technical id fields")
        }

        if (type.kind == 'record') {
            validateDomainRecord(project, file, source, packageName, type, convention, violations)
        }

        extractFieldDeclarations(source).each { FieldDeclaration field ->
            if (isRawCollectionType(field.type)) {
                violations.add("${path} domain field '${field.name}' must use a first-class collection record instead of '${field.type}'")
            }
            if (isRawScalarType(field.type)) {
                violations.add("${path} domain field '${field.name}' must use a Value Object instead of raw scalar '${field.type}'")
            }
            if (isPublicIdName(field.name)) {
                violations.add("${path} domain reference field '${field.name}' must use '{Target}Id' naming, not 'publicId' or '*PublicId'")
            }
            if (looksLikeIdReference(field.name) && field.name != 'id' && !isIdentifierVoType(field.type)) {
                violations.add("${path} domain reference field '${field.name}' must use an identifier Value Object type")
            }
        }
    }

    private static void validateDomainRecord(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        List<RecordComponent> components = extractRecordComponents(source, type.name)

        components.findAll { component -> isRawCollectionType(component.type) }.each { RecordComponent component ->
            if (components.size() > 1) {
                violations.add("${path} domain record component '${component.name}' must be wrapped in a first-class collection record")
            } else if (type.name in ['StringList', 'IdSet', 'IdList', 'StringSet'] || type.name.endsWith('Ids')) {
                violations.add("${path} first-class collection record '${type.name}' must use a domain-specific name")
            }
        }

        boolean singleValueObject = isSingleComponentValueObject(type, components, packageName, convention)
        components.each { RecordComponent component ->
            if (isRawScalarType(component.type) && !singleValueObject) {
                violations.add("${path} domain record component '${component.name}' must use a Value Object instead of raw scalar '${component.type}'")
            }
            if (isPublicIdName(component.name)) {
                violations.add("${path} domain reference component '${component.name}' must use '{Target}Id' naming, not 'publicId' or '*PublicId'")
            }
            if (looksLikeIdReference(component.name) && component.name != 'id' && !isIdentifierVoType(component.type)) {
                violations.add("${path} domain reference component '${component.name}' must use an identifier Value Object type")
            }
        }

        if (!components.isEmpty() && !hasCompactConstructor(source, type.name)) {
            violations.add("${path} domain record '${type.name}' must declare a compact constructor for invariants")
        }
        if (components.any { component -> component.type == 'String' }
                && (!hasNullGuard(source) || !hasBlankCheck(source))) {
            violations.add("${path} domain record '${type.name}' must null-check and blank-check String components")
        }
        if (type.name.endsWith('Id')
                && components.size() == 1
                && normalizeType(components.first().type) == 'String'
                && !hasGenerateFactory(source, type.name)) {
            violations.add("${path} identifier VO '${type.name}' must expose a generate factory")
        }
        RecordComponent entityId = components.find { component ->
            component.name == 'id' && isIdentifierVoType(component.type)
        }
        if (entityId != null && !hasEqualsAndHashCode(source)) {
            violations.add("${path} domain entity record with identifier VO must override equals and hashCode using id")
        }
        if (entityId != null && !hasStaticFactoryReturning(source, type.name)) {
            violations.add("${path} domain entity record with identifier VO must expose a static factory returning '${type.name}'")
        }
        if (components.size() == 1
                && isRawCollectionType(components.first().type)
                && !(source =~ /\b(?:List|Set|Map)\.copyOf\s*\(/).find()) {
            violations.add("${path} first-class collection record '${type.name}' must defensively copy its collection")
        }
        if (components.size() == 1
                && isRawCollectionType(components.first().type)
                && !checksNullElements(source)) {
            violations.add("${path} first-class collection record '${type.name}' must reject null elements")
        }
    }

    private static void validateJpaEntity(
            Project project,
            File file,
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)

        if (!SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)) {
            violations.add("${path} JPA entity '${type.name}' must be in adapter.out package")
        }
        if (!packageName.contains('persistence')) {
            violations.add("${path} JPA entity '${type.name}' must live in a persistence package")
        }
        if (type.finalType) {
            violations.add("${path} JPA entity '${type.name}' must not be final")
        }
        if (!hasAnnotation(source, 'Getter')) {
            violations.add("${path} JPA entity '${type.name}' must declare @Getter")
        }
        if (!hasProtectedNoArgsConstructor(source)) {
            violations.add("${path} JPA entity '${type.name}' must declare @NoArgsConstructor(access = AccessLevel.PROTECTED)")
        }
        if ((source =~ /(?m)@\s*(Setter|Data|Builder)\b/).find()) {
            violations.add("${path} JPA entity '${type.name}' must not use @Setter, @Data, or @Builder")
        }
        if (hasPublicField(source)) {
            violations.add("${path} JPA entity '${type.name}' must not declare public fields")
        }
        if (hasPublicConstructor(source, type.name)) {
            violations.add("${path} JPA entity '${type.name}' must not expose public constructors")
        }
        if (hasJpaRelationAnnotation(source)) {
            violations.add("${path} JPA entity '${type.name}' must not use object relation mappings")
        }

        validateJpaId(project, file, source, type, violations)
        validateDomainIdentifierColumn(project, file, source, type, violations)
        validateJpaReferenceFields(project, file, source, type, violations)

        if (hasNonPrivateArgumentConstructor(source, type.name)) {
            violations.add("${path} JPA entity '${type.name}' argument constructors must be private")
        }
        if (!hasStaticFactoryReturning(source, type.name)) {
            violations.add("${path} JPA entity '${type.name}' must expose a static factory returning '${type.name}'")
        }
    }

    private static void validateJpaId(
            Project project,
            File file,
            String source,
            TypeDeclaration type,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        def idMatcher = source =~ /(?s)(@\s*Getter\s*\(\s*AccessLevel\.NONE\s*\)\s*)?@\s*(?:jakarta\.persistence\.|javax\.persistence\.)?Id\b.*?private\s+Long\s+([A-Za-z_][A-Za-z0-9_]*)\s*;/
        if (!idMatcher.find()) {
            violations.add("${path} JPA entity '${type.name}' must declare private Long id with @Id")
            return
        }

        String getterNone = idMatcher.group(1)
        String idName = idMatcher.group(2)
        if (idName != 'id') {
            violations.add("${path} JPA technical key must be named 'id', not '${idName}'")
        }
        if (getterNone == null) {
            violations.add("${path} JPA technical key 'id' must declare @Getter(AccessLevel.NONE)")
        }
    }

    private static void validateDomainIdentifierColumn(
            Project project,
            File file,
            String source,
            TypeDeclaration type,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        String identifierField = domainIdentifierFieldName(type.name)
        String quotedIdentifierField = Pattern.quote(identifierField)
        def identifierMatcher = source =~ /(?s)@\s*(?:jakarta\.persistence\.|javax\.persistence\.)?Column\s*\((.*?)\)\s*private\s+String\s+${quotedIdentifierField}\s*;/
        if (!identifierMatcher.find()) {
            violations.add("${path} JPA entity '${type.name}' must declare private String ${identifierField} with @Column")
            return
        }

        String columnArgs = identifierMatcher.group(1)
        if (!(columnArgs =~ /unique\s*=\s*true/).find()) {
            violations.add("${path} JPA domain identifier column '${identifierField}' must be unique")
        }
        if (!(columnArgs =~ /length\s*=\s*32/).find()) {
            violations.add("${path} JPA domain identifier column '${identifierField}' must have length = 32")
        }
    }

    private static void validateJpaReferenceFields(
            Project project,
            File file,
            String source,
            TypeDeclaration type,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        String primaryIdentifierField = domainIdentifierFieldName(type.name)
        extractFieldDeclarations(source).each { FieldDeclaration field ->
            if (field.type.endsWith('Entity')) {
                violations.add("${path} JPA entity '${type.name}' must not hold entity reference field '${field.name}'")
            }
            if (isPublicIdName(field.name)) {
                violations.add("${path} JPA reference field '${field.name}' must use '{target}Id' naming, not 'publicId' or '*PublicId'")
            }
            if (looksLikeIdReference(field.name)
                    && !(field.name in ['id', primaryIdentifierField])
                    && field.type != 'String') {
                violations.add("${path} JPA reference field '${field.name}' must store identifier VO value as String")
            }
        }
    }

    private static void validateMapper(
            Project project,
            File file,
            String source,
            TypeDeclaration type,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (!type.finalType) {
            violations.add("${path} mapper '${type.name}' must be final")
        }
        if (!hasPrivateNoArgConstructor(source, type.name)) {
            violations.add("${path} mapper '${type.name}' must declare a private constructor")
        }
        if ((source =~ /(?m)@\s*(Component|Service|Repository)\b/).find()) {
            violations.add("${path} mapper '${type.name}' must not be registered as a Spring bean")
        }
    }

    private static void validateController(
            Project project,
            File file,
            String source,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        SourceInspector.extractImports(source).each { String imported ->
            boolean applicationImport = SourceInspector.isInLayer(imported, convention.applicationPackageSegment)
            boolean allowedInboundPort = imported.contains('.port.in.')
            if (SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)
                    || (applicationImport && !allowedInboundPort)) {
                violations.add("${path} controller must depend on inbound UseCase ports only, not '${imported}'")
            }
        }
        if (controllerReturnsLayerType(source, convention.domainPackageSegment)) {
            violations.add("${path} controller must not expose domain types as response return values")
        }
        if (controllerCreatesProblemDetail(source)) {
            violations.add("${path} controller must not create or return ProblemDetail directly")
        }
    }

    private static List<FieldDeclaration> extractFieldDeclarations(String source) {
        List<FieldDeclaration> fields = []
        def matcher = source =~ /(?m)^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:private|protected|public)\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*([A-Za-z_][A-Za-z0-9_$.]*(?:\s*<[^;=()]+>)?(?:\s*\[\])?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;)/
        while (matcher.find()) {
            fields.add(new FieldDeclaration(
                    normalizeType(matcher.group(1)),
                    matcher.group(2)
            ))
        }
        return fields
    }

    private static List<FieldDeclaration> extractInstanceFieldDeclarations(String source) {
        List<FieldDeclaration> fields = []
        def matcher = source =~ /(?m)^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:private|protected|public)\s+((?:static\s+|final\s+|transient\s+|volatile\s+)*)((?:[A-Za-z_][A-Za-z0-9_$.]*)(?:\s*<[^;=()]+>)?(?:\s*\[\])?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;)/
        while (matcher.find()) {
            String modifiers = matcher.group(1)
            if (modifiers.contains('static')) {
                continue
            }
            fields.add(new FieldDeclaration(
                    normalizeType(matcher.group(2)),
                    matcher.group(3)
            ))
        }
        return fields
    }

    private static List<RecordComponent> extractRecordComponents(String source, String recordName) {
        def matcher = source =~ /(?s)\brecord\s+${recordName}\s*\((.*?)\)/
        if (!matcher.find()) {
            return []
        }

        String components = matcher.group(1).trim()
        if (components.isEmpty()) {
            return []
        }

        return splitTopLevelComma(components).collect { String component ->
            String normalized = component
                    .replaceAll(/(?s)@\w+(?:\([^)]*\))?\s*/, '')
                    .replaceAll(/\bfinal\s+/, '')
                    .trim()
            int lastSpace = normalized.lastIndexOf(' ')
            if (lastSpace < 0) {
                return null
            }
            new RecordComponent(
                    normalizeType(normalized.substring(0, lastSpace)),
                    normalized.substring(lastSpace + 1).trim()
            )
        }.findAll { it != null }
    }

    private static List<String> splitTopLevelComma(String value) {
        List<String> parts = []
        int depth = 0
        int start = 0
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index)
            if (current == '<' as char) {
                depth++
            } else if (current == '>' as char) {
                depth--
            } else if (current == ',' as char && depth == 0) {
                parts.add(value.substring(start, index))
                start = index + 1
            }
        }
        parts.add(value.substring(start))
        return parts
    }

    private static boolean hasForbiddenDomainAnnotation(String source) {
        return (source =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:Autowired|Component|Service|Repository|Entity|Table|Getter|Setter|Data|Builder|NoArgsConstructor|RequiredArgsConstructor|AllArgsConstructor)\b/).find()
    }

    private static boolean throwsJdkBasicException(String source) {
        return (source =~ /(?m)\bthrow\s+new\s+(?:IllegalArgumentException|IllegalStateException|NullPointerException)\b/).find()
    }

    private static void validateExceptionFactoryPolicy(
            String path,
            String layer,
            String typeName,
            String source,
            List<String> violations
    ) {
        if (!isAbstractClass(source, typeName)) {
            if (hasNonPrivateConstructor(source, typeName)) {
                violations.add("${path} ${layer} exception '${typeName}' must keep constructors private and expose static factory methods")
            }
            if (!hasAnyStaticFactoryReturning(source, typeName)) {
                violations.add("${path} ${layer} exception '${typeName}' must expose at least one static factory returning '${typeName}'")
            }
        }
        if (constructsSameExceptionWithStringLiteral(source, typeName)) {
            violations.add("${path} ${layer} exception '${typeName}' must use ErrorCode-based constructors, not string messages")
        }
        validateExceptionDetailFieldNames(path, layer, source, violations)
    }

    private static void validateExceptionDetailFieldNames(
            String path,
            String layer,
            String source,
            List<String> violations
    ) {
        Set<String> methodNames = extractZeroArgumentPublicMethodNames(source)
        extractInstanceFieldDeclarations(source)
                .findAll { FieldDeclaration field -> field.name != 'serialVersionUID' && methodNames.contains(field.name) }
                .each { FieldDeclaration field ->
                    violations.add("${path} ${layer} exception detail field '${field.name}' must use a context-specific internal name instead of matching accessor '${field.name}()'")
                }
    }

    private static Set<String> extractZeroArgumentPublicMethodNames(String source) {
        Set<String> methodNames = []
        def matcher = source =~ /(?m)^\s*public\s+(?!static\b)(?:final\s+)?[A-Za-z_][A-Za-z0-9_$.]*(?:\s*<[^;=()]+>)?(?:\s*\[\])?\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)/
        while (matcher.find()) {
            methodNames.add(matcher.group(1))
        }
        return methodNames
    }

    private static void validateExceptionMessageLanguage(
            Project project,
            File file,
            String source,
            List<String> violations
    ) {
        String searchableSource = stripComments(source)
        def matcher = searchableSource =~ /(?m)\b(?:new\s+[A-Za-z_][A-Za-z0-9_$.]*Exception|super)\s*\(\s*"((?:\\.|[^"\\])*)"/
        while (matcher.find()) {
            if (!containsKorean(matcher.group(1))) {
                violations.add("${project.relativePath(file)} exception message string literals must be written in Korean")
            }
        }
    }

    private static boolean usesRequireNonNull(String source) {
        return (source =~ /(?m)\b(?:Objects\s*\.\s*)?requireNonNull\s*\(/).find()
    }

    private static boolean hasQualityToolSuppressWarnings(String source) {
        return (source =~ /(?s)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)?SuppressWarnings\s*\([^)]*(?:PMD|pmd|Checkstyle|checkstyle|SpotBugs|spotbugs|FindBugs|findbugs)[^)]*\)/).find()
                || (source =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_.]*\.)?SuppressFBWarnings\b/).find()
    }

    private static boolean throwsDirectExceptionConstruction(String source) {
        def matcher = source =~ /(?m)\bthrow\s+new\s+([A-Za-z_][A-Za-z0-9_]*Exception)\s*\(/
        while (matcher.find()) {
            if (!(matcher.group(1) in ['IllegalArgumentException', 'IllegalStateException', 'NullPointerException'])) {
                return true
            }
        }
        return false
    }

    private static boolean constructsExceptionWithStringLiteral(String source) {
        return (source =~ /(?m)\bnew\s+[A-Za-z_][A-Za-z0-9_]*Exception\s*\(\s*"/).find()
    }

    private static boolean constructsSameExceptionWithStringLiteral(String source, String typeName) {
        return (source =~ /(?m)\bnew\s+${typeName}\s*\(\s*"/).find()
    }

    private static boolean dependsOnSpringSecurity(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return SourceInspector.extractImports(source).any { String imported ->
            imported.startsWith('org.springframework.security.')
        } || (searchableSource =~ /\borg\.springframework\.security\./).find()
    }

    private static boolean hasNonPrivateConstructor(String source, String className) {
        return (source =~ /(?m)^\s*(?:public|protected)?\s*${className}\s*\(/).find()
    }

    private static boolean hasAnyStaticFactoryReturning(String source, String typeName) {
        return (source =~ /(?m)^\s*(?:public\s+)?static\s+${typeName}\s+[A-Za-z_][A-Za-z0-9_]*\s*\(/).find()
    }

    private static boolean hasNullGuard(String source) {
        return (source =~ /\brequireNonNull\s*\(/).find()
                || (source =~ /(?m)\b[A-Za-z_][A-Za-z0-9_]*\s*==\s*null\b/).find()
                || (source =~ /(?m)\bnull\s*==\s*[A-Za-z_][A-Za-z0-9_]*\b/).find()
    }

    private static boolean hasBlankCheck(String source) {
        return (source =~ /\.isBlank\s*\(/).find()
    }

    private static boolean hasJpaRelationAnnotation(String source) {
        return JPA_RELATION_ANNOTATIONS.any { String annotation ->
            (source =~ /(?m)@\s*(?:jakarta\.persistence\.|javax\.persistence\.)?${annotation}\b/).find()
        }
    }

    private static boolean hasAnnotation(String source, String annotation) {
        return (source =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*${annotation}\b/).find()
    }

    private static boolean hasClassLevelAnnotation(String source, String typeName, String annotation) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?ms)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*${annotation}\b(?:\([^)]*\))?(?:\s*@\s*[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?)*\s*(?:public\s+)?(?:(?:final|abstract)\s+)?(?:class|record|interface|enum)\s+${typeName}\b/).find()
    }

    private static List<String> findPublicMethodNamesWithoutAnnotation(String source, String annotation) {
        List<String> methodNames = []
        def matcher = source =~ /(?ms)((?:^\s*@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s*)*)^\s*public\s+(?!class\b)(?!interface\b)(?!enum\b)(?!record\b)(?!static\b)(?:final\s+)?[A-Za-z_][A-Za-z0-9_$.<>, ?\[\]]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{/
        while (matcher.find()) {
            String annotations = matcher.group(1)
            String methodName = matcher.group(2)
            if (!(annotations =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*${annotation}\b/).find()) {
                methodNames.add(methodName)
            }
        }
        return methodNames
    }

    private static int countImplementedInterfacesEndingWith(String source, String typeName, String suffix) {
        String searchableSource = stripCommentsAndStrings(source)
        def matcher = searchableSource =~ /(?ms)\bclass\s+${typeName}\s+implements\s+([^{]+)\{/
        if (!matcher.find()) {
            return 0
        }
        splitTopLevelComma(matcher.group(1)).count { String type ->
            simplifyTypeName(type).endsWith(suffix)
        }
    }

    private static String simplifyTypeName(String type) {
        String withoutGenerics = type.replaceAll(/<.*>/, '').trim()
        String simpleName = withoutGenerics.tokenize('.').last()
        return simpleName.replaceAll(/\s+/, '')
    }

    private static boolean isSpringComponentType(
            String source,
            String packageName,
            TypeDeclaration type,
            HexagonalConventionExtension convention
    ) {
        if (type.kind != 'class') {
            return false
        }
        boolean applicationService = SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)
                && packageName.contains('.service')
                && type.name.endsWith('Service')
        boolean adapter = SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)
                || SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
        return applicationService || adapter || hasAnyAnnotation(source, [
                'Component',
                'Service',
                'Repository',
                'RestController',
                'Controller',
                'RestControllerAdvice',
                'ControllerAdvice'
        ])
    }

    private static boolean usesAutowiredInjection(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*Autowired\b/).find()
    }

    private static boolean hasPrivateFinalInstanceField(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)^\s*private\s+final\s+(?!static\b)[A-Za-z_][A-Za-z0-9_$.]*(?:\s*<[^;=()]+>)?(?:\s*\[\])?\s+[A-Za-z_][A-Za-z0-9_]*\s*(?:;|=)/).find()
    }

    private static boolean hasNonFinalPrivateInstanceField(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)^\s*private\s+(?!static\b)(?!final\b)[A-Za-z_][A-Za-z0-9_$.]*(?:\s*<[^;=()]+>)?(?:\s*\[\])?\s+[A-Za-z_][A-Za-z0-9_]*\s*(?:;|=)/).find()
    }

    private static boolean hasDependencyField(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)^\s*private\s+(?:final\s+)?[A-Za-z_][A-Za-z0-9_$.]*(?:Port|Repository|RepositoryPort|UseCase|Client|Service|Mapper|Factory|Publisher|Producer|Consumer|Template|EntityManager|Clock)\b/).find()
    }

    private static String findApplicationRootPackage(String packageName, Set<String> applicationRootPackages) {
        applicationRootPackages
                .findAll { String rootPackage ->
                    packageName == rootPackage || packageName.startsWith("${rootPackage}.")
                }
                .sort { String left, String right -> right.length() <=> left.length() }
                .with { List<String> matches -> matches.isEmpty() ? null : matches.first() }
    }

    private static boolean hasAnyAnnotation(String source, List<String> annotations) {
        return annotations.any { String annotation -> hasAnnotation(source, annotation) }
    }

    private static boolean isWebMvcConfiguration(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /\bWebMvcConfigurer\b/).find()
                || (searchableSource =~ /\badd(?:Interceptors|ArgumentResolvers)\s*\(/).find()
    }

    private static boolean instantiatesWebMvcExtension(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /\bnew\s+[A-Za-z_][A-Za-z0-9_]*(?:Interceptor|ArgumentResolver)\s*\(/).find()
    }

    private static boolean isAbstractClass(String source, String typeName) {
        return (source =~ /(?m)^\s*(?:public\s+)?abstract\s+class\s+${typeName}\b/).find()
    }

    private static boolean hasProtectedNoArgsConstructor(String source) {
        return (source =~ /(?s)@\s*NoArgsConstructor\s*\([^)]*access\s*=\s*AccessLevel\.PROTECTED[^)]*\)/).find()
    }

    private static boolean hasPrivateNoArgConstructor(String source, String className) {
        return (source =~ /(?m)^\s*private\s+${className}\s*\(\s*\)/).find()
    }

    private static boolean hasNonPrivateArgumentConstructor(String source, String className) {
        def matcher = source =~ /(?m)^\s*(public|protected)?\s*${className}\s*\(([^)]*)\)/
        while (matcher.find()) {
            if (!matcher.group(2).trim().isEmpty()) {
                return true
            }
        }
        return false
    }

    private static boolean hasCompactConstructor(String source, String recordName) {
        return (source =~ /(?m)^\s*public\s+${recordName}\s*\{/).find()
    }

    private static boolean hasStaticFactoryReturning(String source, String typeName) {
        return (source =~ /(?m)^\s*(?:public\s+)?static\s+${typeName}\s+(create|from|of|reconstitute|pending|generate)\s*\(/).find()
    }

    private static boolean hasGenerateFactory(String source, String typeName) {
        return (source =~ /(?m)^\s*(?:public\s+)?static\s+${typeName}\s+generate\s*\(/).find()
    }

    private static boolean hasSerialVersionUid(String source) {
        return (source =~ /(?m)\bserialVersionUID\b/).find()
    }

    private static boolean hasPublicField(String source) {
        return (source =~ /(?m)^\s*public\s+(?!static\b)[A-Za-z_][A-Za-z0-9_$.]*(?:\s*<[^;=()]+>)?\s+[A-Za-z_][A-Za-z0-9_]*\s*(?:=|;)/).find()
    }

    private static boolean hasPublicConstructor(String source, String className) {
        return (source =~ /(?m)^\s*public\s+${className}\s*\(/).find()
    }

    private static boolean checksNullElements(String source) {
        return (source =~ /Objects::isNull/).find()
                || (source =~ /\.contains\s*\(\s*null\s*\)/).find()
                || (source =~ /==\s*null/).find()
    }

    private static boolean exposesTechnicalException(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        boolean imported = SourceInspector.extractImports(source).any { String imported ->
            TECHNICAL_EXCEPTION_PACKAGES.any { String packageName ->
                imported == packageName || imported.startsWith("${packageName}.")
            } || TECHNICAL_EXCEPTION_TYPES.contains(imported.substring(imported.lastIndexOf('.') + 1))
        }
        if (imported) {
            return true
        }
        return TECHNICAL_EXCEPTION_TYPES.any { String typeName ->
            (searchableSource =~ /(?m)\b(?:throws|catch\s*\(|new\s+)${Pattern.quote(typeName)}\b/).find()
                    || (searchableSource =~ /(?m)\b${Pattern.quote(typeName)}\s+[A-Za-z_][A-Za-z0-9_]*\s*(?:;|,|\))/).find()
        }
    }

    private static boolean exposesExceptionMessageAsProblemDetail(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)\.setDetail\s*\([^)]*\.getMessage\s*\(/).find()
                || (searchableSource =~ /(?m)forStatusAndDetail\s*\([^)]*\.getMessage\s*\(/).find()
    }

    private static boolean hardCodesProblemDetailTitleOrDetail(String source) {
        return (source =~ /(?m)\.set(?:Title|Detail)\s*\(\s*"[^"]+"\s*\)/).find()
                || (source =~ /(?m)forStatusAndDetail\s*\([^,]+,\s*"[^"]+"\s*\)/).find()
    }

    private static boolean importsDomainOrApplicationException(
            String source,
            HexagonalConventionExtension convention
    ) {
        return SourceInspector.extractImports(source).any { String imported ->
            (SourceInspector.isInLayer(imported, convention.domainPackageSegment)
                    && imported.contains('.exception.'))
                    || (SourceInspector.isInLayer(imported, convention.applicationPackageSegment)
                    && imported.contains('.exception.'))
        }
    }

    private static boolean implementsApiExceptionMapper(String source) {
        return (source =~ /(?m)\bimplements\s+[A-Za-z0-9_,\s<>]*ApiExceptionMapper\b/).find()
    }

    private static boolean hasTestMethodAnnotation(String annotations) {
        return TEST_METHOD_ANNOTATIONS.any { String annotation ->
            (annotations =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*${annotation}\b/).find()
        }
    }

    private static boolean hasDisabledTestAnnotation(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*Disabled\b/).find()
    }

    private static boolean containsKorean(String value) {
        return (value =~ /[\uAC00-\uD7A3]/).find()
    }

    private static PhaseComment findPhaseComment(String body, String phase) {
        String combinedWhenThenGuard = phase == 'when' ? '(?!\\s*&\\s*then)' : ''
        def matcher = body =~ /(?m)^\s*\/\/\s*${phase}\b${combinedWhenThenGuard}.*$/
        return matcher.find() ? new PhaseComment(matcher.start(), matcher.end()) : null
    }

    private static PhaseComment findWhenThenComment(String body) {
        def matcher = body =~ /(?m)^\s*\/\/\s*when\s*&\s*then\b.*$/
        return matcher.find() ? new PhaseComment(matcher.start(), matcher.end()) : null
    }

    private static boolean hasExecutableCode(String value) {
        String codeOnly = stripCommentsAndStrings(value)
                .replaceAll(/[\s;]/, '')
        return !codeOnly.isBlank()
    }

    private static boolean hasObservableAssertion(String body) {
        String searchableSource = stripCommentsAndStrings(body)
        return (searchableSource =~ /(?m)\b(?:Assertions\s*\.\s*)?assert(?!DoesNotThrow\b)[A-Z][A-Za-z0-9_]*\s*\(/).find()
                || (searchableSource =~ /(?m)\bassertThat(?:ThrownBy|ExceptionOfType)?\s*\(/).find()
                || hasMeaningfulAssertThatCode(searchableSource)
                || (searchableSource =~ /(?m)\bthen\s*\([^)]*\)\s*\.\s*(?:is|has|contains|startsWith|endsWith|matches|isEqualTo|isTrue|isFalse|isNull|isNotNull|isEmpty|isNotEmpty)\b/).find()
                || (searchableSource =~ /(?m)^\s*assert\s+[^;]+;/).find()
                || (searchableSource =~ /(?m)\bfail\s*\(/).find()
    }

    private static boolean hasMeaningfulAssertThatCode(String searchableSource) {
        return (searchableSource =~ /(?m)\bassertThatCode\s*\(/).find()
                && !(searchableSource =~ /(?m)\bassertThatCode\s*\([^;]*\.doesNotThrowAnyException\s*\(/).find()
    }

    private static boolean hasNoExceptionOnlyAssertion(String body) {
        String searchableSource = stripCommentsAndStrings(body)
        return (searchableSource =~ /(?m)\b(?:Assertions\s*\.\s*)?assertDoesNotThrow\s*\(/).find()
                || (searchableSource =~ /(?m)\bassertThatCode\s*\([^;]*\.doesNotThrowAnyException\s*\(/).find()
    }

    private static boolean hasMockVerification(String body) {
        String searchableSource = stripCommentsAndStrings(body)
        return (searchableSource =~ /(?m)\bverify\s*\(/).find()
                || (searchableSource =~ /(?m)\bthen\s*\([^)]*\)\s*\.\s*should\s*\(/).find()
    }

    private static boolean usesJUnitAssumption(String body) {
        String searchableSource = stripCommentsAndStrings(body)
        return (searchableSource =~ /(?m)\b(?:Assumptions\s*\.\s*)?assume(?:True|False|That|NoException)\s*\(/).find()
    }

    private static int findMatchingBrace(String source, int openBraceIndex) {
        int depth = 0
        boolean inString = false
        boolean inChar = false
        boolean inLineComment = false
        boolean inBlockComment = false
        boolean escaped = false

        for (int index = openBraceIndex; index < source.length(); index++) {
            char current = source.charAt(index)
            char next = index + 1 < source.length() ? source.charAt(index + 1) : (char) 0

            if (inLineComment) {
                if (current == '\n' as char || current == '\r' as char) {
                    inLineComment = false
                }
                continue
            }
            if (inBlockComment) {
                if (current == '*' as char && next == '/' as char) {
                    inBlockComment = false
                    index++
                }
                continue
            }
            if (inString) {
                if (!escaped && current == '"' as char) {
                    inString = false
                }
                escaped = !escaped && current == '\\' as char
                if (current != '\\' as char) {
                    escaped = false
                }
                continue
            }
            if (inChar) {
                if (!escaped && current == '\'' as char) {
                    inChar = false
                }
                escaped = !escaped && current == '\\' as char
                if (current != '\\' as char) {
                    escaped = false
                }
                continue
            }

            if (current == '/' as char && next == '/' as char) {
                inLineComment = true
                index++
                continue
            }
            if (current == '/' as char && next == '*' as char) {
                inBlockComment = true
                index++
                continue
            }
            if (current == '"' as char) {
                inString = true
                escaped = false
                continue
            }
            if (current == '\'' as char) {
                inChar = true
                escaped = false
                continue
            }
            if (current == '{' as char) {
                depth++
            } else if (current == '}' as char) {
                depth--
                if (depth == 0) {
                    return index
                }
            }
        }
        return -1
    }

    private static boolean referencesInnerLayerPackage(String source, HexagonalConventionExtension convention) {
        return referencesLayerPackage(source, convention.domainPackageSegment)
                || referencesLayerPackage(source, convention.applicationPackageSegment)
    }

    private static boolean controllerReturnsLayerType(String source, String packageSegment) {
        Set<String> importedSimpleNames = importedSimpleNamesInLayer(source, packageSegment)
        return extractPublicMethodReturnTypes(source).any { String returnType ->
            returnTypeReferencesLayer(returnType, importedSimpleNames, packageSegment)
        }
    }

    private static boolean controllerCreatesProblemDetail(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /\bProblemDetail\s*\./).find()
                || (searchableSource =~ /\bnew\s+ProblemDetail\b/).find()
                || extractPublicMethodReturnTypes(source).any { String returnType ->
                    tokenizedTypeNames(returnType).contains('ProblemDetail')
                }
    }

    private static List<String> extractPublicMethodReturnTypes(String source) {
        List<String> returnTypes = []
        String searchableSource = stripCommentsAndStrings(source)
        def matcher = searchableSource =~ /(?m)^\s*(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s*)*public\s+(?:final\s+|static\s+|synchronized\s+)*([A-Za-z_][A-Za-z0-9_$.]*(?:\s*<[^;{()]+>)?(?:\s*\[\])?)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(/
        while (matcher.find()) {
            String returnType = matcher.group(1).trim()
            if (returnType != 'void') {
                returnTypes.add(returnType)
            }
        }
        return returnTypes
    }

    private static Set<String> importedSimpleNamesInLayer(String source, String packageSegment) {
        return SourceInspector.extractImports(source).findAll { String imported ->
            SourceInspector.isInLayer(imported, packageSegment) && !imported.endsWith('.*')
        }.collect { String imported ->
            imported.substring(imported.lastIndexOf('.') + 1)
        }.toSet()
    }

    private static boolean returnTypeReferencesLayer(
            String returnType,
            Set<String> importedSimpleNames,
            String packageSegment
    ) {
        if (SourceInspector.isInLayer(returnType, packageSegment)) {
            return true
        }
        return tokenizedTypeNames(returnType).any { String token ->
            importedSimpleNames.contains(token)
        }
    }

    private static Set<String> tokenizedTypeNames(String type) {
        return type.split(/[^A-Za-z0-9_]+/)
                .findAll { String token -> !token.isBlank() }
                .toSet()
    }

    private static boolean hasGenericApiDtoName(String packageName, String typeName) {
        if (GENERIC_API_DTO_NAMES.contains(typeName)) {
            return true
        }

        String contextName = boundedContextName(packageName)
        if (contextName == null) {
            return false
        }

        return typeName == "${capitalizeAscii(contextName)}Request"
                || typeName == "${capitalizeAscii(contextName)}Response"
    }

    private static String boundedContextName(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return null
        }

        List<String> segments = packageName.split('\\.').toList()
        int adapterIndex = segments.indexOf('adapter')
        if (adapterIndex > 0) {
            return segments[adapterIndex - 1]
        }

        int domainIndex = segments.indexOf('domain')
        if (domainIndex > 0) {
            return segments[domainIndex - 1]
        }

        int applicationIndex = segments.indexOf('application')
        if (applicationIndex > 0) {
            return segments[applicationIndex - 1]
        }

        return null
    }

    private static String capitalizeAscii(String value) {
        if (value == null || value.isBlank()) {
            return value
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1)
    }

    private static boolean importsOrUsesType(
            String source,
            String searchableSource,
            String qualifiedName,
            String simpleName
    ) {
        boolean imported = SourceInspector.extractImports(source).any { String imported ->
            imported == qualifiedName
                    || (imported.endsWith('.*') && qualifiedName.startsWith(imported.substring(0, imported.length() - 1)))
        }
        return imported || (searchableSource =~ /(?m)\b${Pattern.quote(simpleName)}\b/).find()
    }

    private static boolean usesAnyType(String source, Set<String> typeNames) {
        String searchableSource = stripCommentsAndStrings(source)
        return typeNames.any { String typeName ->
            (searchableSource =~ /(?m)\b${Pattern.quote(typeName)}\b/).find()
        }
    }

    private static boolean referencesGlobalErrorPackage(String source) {
        return referencesLayerPackage(source, 'global.error')
    }

    private static boolean referencesLayerPackage(String source, String packageSegment) {
        String searchableSource = stripCommentsAndStrings(source)
        String quotedPackageSegment = Pattern.quote(packageSegment)
        return (searchableSource =~ /(?m)(?:^|[^A-Za-z0-9_])(?:[A-Za-z_][A-Za-z0-9_]*\.)*${quotedPackageSegment}(?:\.|[^A-Za-z0-9_]|$)/).find()
    }

    private static String stripCommentsAndStrings(String source) {
        return source
                .replaceAll(/(?s)\/\*.*?\*\//, ' ')
                .replaceAll(/(?m)\/\/.*$/, ' ')
                .replaceAll('(?s)"""[\\s\\S]*?"""', '""')
                .replaceAll(/(?s)"(?:\\.|[^"\\])*"/, '""')
                .replaceAll(/(?s)'(?:\\.|[^'\\])*'/, "''")
    }

    private static String stripComments(String source) {
        return source
                .replaceAll(/(?s)\/\*.*?\*\//, ' ')
                .replaceAll(/(?m)\/\/.*$/, ' ')
    }

    private static boolean isGlobalErrorPackage(String packageName) {
        return packageName == 'global.error'
                || packageName.endsWith('.global.error')
                || packageName.contains('.global.error.')
    }

    private static boolean hasEqualsAndHashCode(String source) {
        return (source =~ /(?m)^\s*public\s+boolean\s+equals\s*\(/).find()
                && (source =~ /(?m)^\s*public\s+int\s+hashCode\s*\(/).find()
    }

    private static boolean isRawCollectionType(String type) {
        String normalized = normalizeType(type)
        return normalized.startsWith('List<')
                || normalized.startsWith('Set<')
                || normalized.startsWith('Map<')
                || normalized.endsWith('[]')
    }

    private static boolean isRawScalarType(String type) {
        return RAW_SCALAR_TYPES.contains(normalizeType(type))
    }

    private static boolean isDomainEventPackage(String packageName, HexagonalConventionExtension convention) {
        String marker = ".${convention.domainPackageSegment}.event"
        return packageName != null && (packageName.endsWith(marker) || packageName.contains("${marker}."))
    }

    private static boolean looksLikeIdReference(String name) {
        return name ==~ /.*Id(s)?$/
    }

    private static boolean isPublicIdName(String name) {
        return name == 'publicId' || name.endsWith('PublicId')
    }

    private static boolean isIdentifierVoType(String type) {
        return normalizeType(type).endsWith('Id')
    }

    private static boolean isSingleComponentValueObject(
            TypeDeclaration type,
            List<RecordComponent> components,
            String packageName,
            HexagonalConventionExtension convention
    ) {
        return components.size() == 1
                && !isDomainEventPackage(packageName, convention)
                && !type.name.endsWith('Event')
    }

    private static String domainIdentifierFieldName(String typeName) {
        String domainName = typeName.replaceFirst(/Entity$/, '')
        if (domainName.isEmpty()) {
            return 'id'
        }
        return domainName.substring(0, 1).toLowerCase(Locale.ROOT) + domainName.substring(1) + 'Id'
    }

    private static String normalizeType(String type) {
        return type.replaceAll(/\s+/, ' ').trim()
    }

    private static class TypeDeclaration {
        final String kind
        final String name
        final boolean finalType

        private TypeDeclaration(String kind, String name, boolean finalType) {
            this.kind = kind
            this.name = name
            this.finalType = finalType
        }

        static TypeDeclaration from(String source) {
            def matcher = source =~ /(?m)^\s*(?:public\s+)?(?:(final|abstract)\s+)?(class|record|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\b/
            if (!matcher.find()) {
                return null
            }
            return new TypeDeclaration(
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(1) == 'final'
            )
        }
    }

    private static class FieldDeclaration {
        final String type
        final String name

        private FieldDeclaration(String type, String name) {
            this.type = type
            this.name = name
        }
    }

    private static class PhaseComment {
        final int start
        final int end

        private PhaseComment(int start, int end) {
            this.start = start
            this.end = end
        }
    }

    private static class RecordComponent {
        final String type
        final String name

        private RecordComponent(String type, String name) {
            this.type = type
            this.name = name
        }
    }
}
