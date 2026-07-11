package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.JavaSourceAstInspector
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class JavaSourceArchitectureValidator {
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
    private static final Set<String> ALLOWED_APPLICATION_SPRING_TYPES = [
            'org.springframework.stereotype.Service',
            'org.springframework.transaction.annotation.Transactional'
    ] as Set
    private static final Set<String> ALLOWED_APPLICATION_EXTERNAL_TYPES = [
            'lombok.RequiredArgsConstructor',
            'org.springframework.stereotype.Service',
            'org.springframework.transaction.annotation.Transactional'
    ] as Set
    private static final Set<String> APPLICATION_TECHNICAL_PACKAGE_PREFIXES = [
            'com.amazonaws.',
            'com.querydsl.',
            'feign.',
            'jakarta.persistence.',
            'java.sql.',
            'javax.persistence.',
            'okhttp3.',
            'org.apache.http.',
            'org.hibernate.',
            'org.springframework.',
            'retrofit2.',
            'software.amazon.awssdk.'
    ] as Set

    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        List<String> violations = []
        List<File> mainSourceFiles = SourceInspector.collectMainSourceFiles(project).findAll { File file ->
            file.name.endsWith('.java')
        }
        AggregateBoundaryConventionValidator.Analysis aggregateAnalysis =
                AggregateBoundaryConventionValidator.analyze(project, convention)
        violations.addAll(aggregateAnalysis.violations)
        validateNoMessageBundleResources(project, violations)
        violations.addAll(PackageTopologyConventionValidator.validate(project, convention))

        mainSourceFiles.each { File file ->
            JavaSourceAstInspector.Inspection inspection = aggregateAnalysis.inspectionFor(file)
            if (inspection == null || !inspection.valid) {
                return
            }
            String source = file.getText(StandardCharsets.UTF_8.name())
            source = appendMissingAstImports(source, inspection)
            String packageName = inspection.packageName
            validateCommonPackageUsage(project, file, source, packageName, violations)
            validateNoWildcardImports(project, file, source, violations)
            validateNoQualityToolSuppressUsage(project, file, source, violations)
            validateNoI18nOrValueInjection(project, file, source, violations)

            JavaSourceAstInspector.TypeModel astType = inspection.primaryType()
            TypeDeclaration type = TypeDeclaration.from(astType)

            validateSingleResponsibility(project, file, source, packageName, type, convention, violations)
            validateTechnicalAnnotationPlacement(project, file, source, packageName, type, convention, violations)
            violations.addAll(TransactionBoundaryConventionValidator.validateFile(
                    project,
                    file,
                    source,
                    packageName,
                    type.name,
                    convention,
                    aggregateAnalysis,
                    astType
            ))
            validateWebErrorTypePlacement(project, file, source, packageName, convention, violations)
            validateTypePackageConvention(project, file, packageName, type, convention, violations)
            validateSpringComponentRegistration(project, file, source, packageName, type, convention, violations)
            validateExceptionArchitecture(project, file, source, packageName, type, convention, violations)
            validateErrorProviderCodeKeys(project, file, source, type, violations)
            validateWebAuthenticationArchitecture(project, file, source, violations)

            if (SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
                validateDomain(project, file, source, packageName, type, convention, violations)
            }

            if (SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)) {
                validateApplication(project, file, source, packageName, type, convention, violations)
            }

            if (SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)) {
                validatePresentation(project, file, source, packageName, type, convention, violations)
            }

            if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)) {
                validateInfrastructure(project, file, source, packageName, type, violations)
            }

            if (type.hasAnnotation('Entity')) {
                validateJpaEntity(project, file, source, packageName, type, convention, violations)
            }

            if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)
                    && type.name.endsWith('Mapper')) {
                validateMapper(project, file, source, type, violations)
            }

            if (type.name.endsWith('Controller') || type.hasAnnotation('RestController')) {
                validateController(project, file, source, type, convention, violations)
            }
        }
        validateTestConventions(project, violations)

        return violations
    }

    private static String appendMissingAstImports(
            String source,
            JavaSourceAstInspector.Inspection inspection
    ) {
        Set<String> parsedImports = SourceInspector.extractImports(source).toSet()
        Set<String> missingImports = inspection.allImports.findAll { imported ->
            !parsedImports.contains(imported)
        }.toSet()
        if (missingImports.isEmpty()) {
            return source
        }
        String canonicalImports = missingImports.sort().collect { imported ->
            "import ${imported};"
        }.join(System.lineSeparator())
        return source + System.lineSeparator() + canonicalImports + System.lineSeparator()
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

    private static void validateNoWildcardImports(Project project, File file, String source, List<String> violations) {
        SourceInspector.extractImports(source).each { String imported ->
            if (imported.endsWith('.*')) {
                violations.add("${project.relativePath(file)} must not use wildcard import '${imported}'")
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
            int useCaseCount = type.countImplementedTypesEndingWith('UseCase')
            if (useCaseCount > 1) {
                violations.add("${path} application service '${type.name}' must implement exactly one UseCase for SRP")
            }
        }

        if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)
                && type.kind == 'class'
                && !type.hasAnnotation('Entity')
                && !type.name.endsWith('Mapper')) {
            int portCount = type.countImplementedTypesEndingWith('Port')
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

        ['Entity', 'Table', 'Repository'].each { String annotation ->
            if (type.hasAnnotation(annotation) && !outboundAdapter) {
                violations.add("${path} @${annotation} is only allowed in adapter.out")
            }
        }

        ['RestController', 'Controller', 'RestControllerAdvice', 'ControllerAdvice'].each { String annotation ->
            if (type.hasAnnotation(annotation) && !inboundWebAdapter && !globalError) {
                violations.add("${path} @${annotation} is only allowed in adapter.in.web or global.error")
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
                && !type.hasAnnotation('Service')) {
            violations.add("${path} application service '${type.name}' must declare @Service")
        }

        if (SourceInspector.isInLayer(packageName, convention.infrastructurePackageSegment)
                && type.kind == 'class'
                && !type.isAbstract()
                && !type.hasAnnotation('Entity')
                && !type.name.endsWith('Mapper')
                && !['Component', 'Repository'].any { annotation -> type.hasAnnotation(annotation) }) {
            violations.add("${path} outbound adapter '${type.name}' must declare @Component or @Repository instead of being wired by ContextConfig")
        }

        if (isSpringComponentType(packageName, type, convention)) {
            if (usesAutowiredInjection(source)) {
                violations.add("${path} Spring component '${type.name}' must use final fields with @RequiredArgsConstructor instead of @Autowired injection")
            }
            if (hasPrivateFinalInstanceField(source) && !type.hasAnnotation('RequiredArgsConstructor')) {
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

        if (type.implementsTypeEndingWith('ApiExceptionMapper') && !globalError && !webAdapter) {
            violations.add("${path} ApiExceptionMapper implementations must live in adapter.in.web or global.error")
        }
    }

    private static void validateErrorProviderCodeKeys(
            Project project,
            File file,
            String source,
            TypeDeclaration type,
            List<String> violations
    ) {
        boolean messageProvider = type.implementsTypeEndingWith('ApiErrorMessageProvider')
        if (!type.implementsTypeEndingWith('ErrorCodeMappingProvider') && !messageProvider) {
            return
        }
          if (usesEnumNameAsProviderMapKey(source)) {
              violations.add("${project.relativePath(file)} error mapping/message provider must use ApiErrorCode.from(errorCode), not Enum.name(), for API code keys")
          }
          if (usesStringLiteralAsProviderMapKey(source)) {
              violations.add("${project.relativePath(file)} error mapping/message provider must use ApiErrorCode.from(errorCode), not hard-coded string literals, for API code keys")
          }
          if (messageProvider && hasNonKoreanUserFacingApiErrorMessage(source)) {
              violations.add("${project.relativePath(file)} user-facing ApiErrorMessage title/detail must be written in Korean")
          }
      }

    private static void validateWebAuthenticationArchitecture(
            Project project,
            File file,
            String source,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        if (usesApiExcludePathPatterns(source)) {
            violations.add("${path} must mark public APIs with @PublicApi instead of hard-coded /api excludePathPatterns")
        }
        if (isAuthenticatedMemberArgumentResolver(source) && returnsNull(source)) {
            violations.add("${path} @AuthenticatedMember resolver must throw an authentication exception instead of returning null")
        }
    }

    private static void validateTestConventions(Project project, List<String> violations) {
        File testJavaDir = project.file('src/test/java')
        if (!testJavaDir.exists()) {
            return
        }

        List<File> testFiles = project.fileTree(testJavaDir) {
            include '**/*.java'
        }.files.toList()
        JavaSourceAstInspector.inspectAll(testFiles).values().each { inspection ->
            File file = inspection.file
            if (!inspection.valid) {
                violations.add(
                        "${project.relativePath(file)} could not be parsed as Java source: "
                                + inspection.errors.join('; ')
                )
                return
            }

            String source = file.getText(StandardCharsets.UTF_8.name())
            source = appendMissingAstImports(source, inspection)
            validateNoWildcardImports(project, file, source, violations)
            if (inspection.types.any { type -> hasDisabledAnnotation(type) }) {
                violations.add("${project.relativePath(file)} tests must not use @Disabled; fix or delete skipped tests")
            }
            inspection.types.each { type ->
                validateJavaTestType(project, file, type, violations)
            }
        }
    }

    private static void validateJavaTestType(
            Project project,
            File file,
            JavaSourceAstInspector.TypeModel type,
            List<String> violations
    ) {
        type.methods.findAll { method -> isTestMethod(method) }.each { method ->
            validateDisplayName(project, file, method, violations)
            validateGivenWhenThen(project, file, method.body, method.name, violations)
            validateTestAssertionQuality(project, file, method.body, method.name, violations)
        }
        type.nestedTypes.each { nestedType ->
            validateJavaTestType(project, file, nestedType, violations)
        }
    }

    private static boolean hasDisabledAnnotation(JavaSourceAstInspector.TypeModel type) {
        return type.annotation('Disabled') != null
                || type.methods.any { method -> method.annotation('Disabled') != null }
                || type.nestedTypes.any { nestedType -> hasDisabledAnnotation(nestedType) }
    }

    private static boolean isTestMethod(JavaSourceAstInspector.MethodModel method) {
        return method.annotations.any { annotation ->
            TEST_METHOD_ANNOTATIONS.contains(annotation.simpleName)
        }
    }

    private static void validateDisplayName(
            Project project,
            File file,
            JavaSourceAstInspector.MethodModel method,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        JavaSourceAstInspector.AnnotationModel displayNameAnnotation = method.annotation('DisplayName')
        String displayName = displayNameAnnotation?.arguments?.get('value')
        if (displayName == null) {
            violations.add("${path} test method '${method.name}' must declare @DisplayName in Korean")
            return
        }

        if (!containsKorean(displayName)) {
            violations.add("${path} test method '${method.name}' @DisplayName must be written in Korean")
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
                && !isRequestPackage(packageName, convention)) {
            violations.add("${path} request DTO '${type.name}' must live in adapter.in.web.request package")
        }
        if (type.name.endsWith('Response') && SourceInspector.isInLayer(packageName, convention.presentationPackageSegment)
                && !isResponsePackage(packageName, convention)) {
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

        validateApplicationDependencies(path, source, packageName, type, violations)
        if (packageName.contains('.service')) {
            validateApplicationServiceCollaborators(path, source, type, violations)
        }
        if (dependsOnSpringSecurity(source)) {
            violations.add("${path} application must depend on a password port, not Spring Security types")
        }
        if (packageName.contains('.service') && callsRepositoryWithRawCommandValue(source)) {
            violations.add("${path} application service must create a VO and pass normalized vo.value() to repository exists/find calls")
        }
        if (type.name.endsWith('Exception')) {
            if (!type.extendsType('RuntimeException')) {
                violations.add("${path} application exception '${type.name}' must extend RuntimeException")
            }
            if (!hasSerialVersionUid(type)) {
                violations.add("${path} application exception '${type.name}' must declare serialVersionUID")
            }
            validateExceptionFactoryPolicy(path, 'application', type, source, violations)
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
            if (!type.implementsTypeEndingWith('UseCase')) {
                violations.add("${path} application service '${type.name}' must implement an inbound UseCase")
            }
        }
    }

    private static void validateApplicationDependencies(
            String path,
            String source,
            String packageName,
            TypeDeclaration type,
            List<String> violations
    ) {
        String currentContext = boundedContextName(packageName)
        Set<String> dependencies = new LinkedHashSet<>(SourceInspector.extractImports(source))
        dependencies.addAll(type.astType.qualifiedTypeReferences)
        dependencies.each { dependency ->
            validateApplicationDependencyReference(path, dependency, currentContext, violations)
        }
    }

    private static void validateApplicationDependencyReference(
            String path,
            String dependency,
            String currentContext,
            List<String> violations
    ) {
        if (isForbiddenApplicationTechnicalImport(dependency)) {
            violations.add("${path} application must not depend on technical framework type '${dependency}'")
            return
        }

        String referencedContext = boundedContextName(dependency)
        boolean crossContextDependency = currentContext != null
                && referencedContext != null
                && currentContext != referencedContext
                && (dependency.contains('.domain.model.') || dependency.contains('.application.'))
        if (crossContextDependency) {
            String typeName = dependency.substring(dependency.lastIndexOf('.') + 1)
            if (dependency.contains('.domain.model.') && !isIdentifierVoType(typeName)) {
                violations.add("${path} application must not depend on another context domain model '${dependency}'; use an integration Port or identifier Value Object")
            }
            if (dependency.contains('.application.')) {
                violations.add("${path} application must not depend directly on another context application type '${dependency}'; use an integration Port")
            }
            return
        }

        if (dependency.contains('.adapter.') || isGlobalErrorPackage(dependency)) {
            violations.add("${path} application must not depend on adapter/global error layer '${dependency}'")
            return
        }

        if (!isAllowedApplicationDependency(dependency, currentContext)) {
            violations.add(
                    "${path} application dependency '${dependency}' is not allowed; "
                            + 'depend on JDK types, same-context Domain/Ports/Application exceptions, '
                            + 'or the explicitly allowed component annotations'
            )
        }
    }

    private static boolean isAllowedApplicationDependency(String imported, String currentContext) {
        if (imported.startsWith('java.') || ALLOWED_APPLICATION_EXTERNAL_TYPES.contains(imported)) {
            return true
        }

        String importedContext = boundedContextName(imported)
        if (currentContext != null && importedContext == currentContext) {
            return imported.contains('.domain.')
                    || imported.contains('.application.port.')
                    || imported.contains('.application.exception.')
        }

        String simpleTypeName = imported.substring(imported.lastIndexOf('.') + 1)
        return imported.contains('.domain.model.') && isIdentifierVoType(simpleTypeName)
    }

    private static void validateApplicationServiceCollaborators(
            String path,
            String source,
            TypeDeclaration type,
            List<String> violations
    ) {
        Map<String, String> importsBySimpleName = SourceInspector.extractImports(source)
                .findAll { imported -> !imported.endsWith('.*') }
                .collectEntries { imported ->
                    [(imported.substring(imported.lastIndexOf('.') + 1)): imported]
                }
        extractInstanceFieldDeclarations(type).findAll { field ->
            String simpleType = simplifyTypeName(field.type)
            String importedType = importsBySimpleName.get(simpleType)
            boolean outboundPort = simpleType.endsWith('Port')
            boolean domainService = simpleType.endsWith('Service')
                    && ((importedType != null && importedType.contains('.domain.model.'))
                    || field.type.contains('.domain.model.'))
            !outboundPort && !domainService
        }.each { field ->
            violations.add(
                    "${path} application service collaborator '${field.name}' must be an outbound Port or Domain service"
            )
        }
    }

    private static boolean isForbiddenApplicationTechnicalImport(String imported) {
        if (ALLOWED_APPLICATION_SPRING_TYPES.contains(imported)) {
            return false
        }
        return APPLICATION_TECHNICAL_PACKAGE_PREFIXES.any { String prefix -> imported.startsWith(prefix) }
    }

    private static void validatePresentation(
            Project project,
            File file,
            String source,
            String packageName,
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
                && referencesInnerLayerPackage(source, type, convention)) {
            violations.add("${path} API DTO '${type.name}' must not expose domain/application types directly")
        }
        if (convention.enforceMsaWebAdapterBoundary && !isMsaWebAdapterPackage(packageName, convention)) {
            violations.add("${path} MSA web adapter package must go through adapter.in.web.external or adapter.in.web.internal")
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
            if (!type.hasAnnotation('Repository')) {
                violations.add("${path} persistence adapter '${type.name}' must declare @Repository")
            }
            if (!type.implementsTypeEndingWith('Port')) {
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
            if (!type.extendsType('RuntimeException')) {
                violations.add("${path} domain exception '${type.name}' must extend RuntimeException")
            }
            if (!hasSerialVersionUid(type)) {
                violations.add("${path} domain exception '${type.name}' must declare serialVersionUID")
            }
            validateExceptionFactoryPolicy(path, 'domain', type, source, violations)
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

        typeDependencies(source, type)
                .findAll { String dependency -> isGlobalErrorPackage(dependency) }
                .each { String dependency ->
                    violations.add("${path} domain must not depend on global.error '${dependency}'")
                }

        if (type.fields.any { field ->
            field.name.toLowerCase(Locale.ROOT).endsWith('id')
                    && simplifyTypeName(field.type) in ['Long', 'long']
        }) {
            violations.add("${path} domain must not declare DB technical id fields")
        }

          if (type.kind == 'record') {
              validateDomainRecord(project, file, source, packageName, type, convention, violations)
          }

          validateCrossContextAggregateReferences(project, file, source, packageName, type, convention, violations)

          extractFieldDeclarations(type).each { FieldDeclaration field ->
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
        List<RecordComponent> components = extractRecordComponents(type)

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

        if (!components.isEmpty() && !hasCompactConstructor(type)) {
            violations.add("${path} domain record '${type.name}' must declare a compact constructor for invariants")
        }
        if (components.any { component -> component.type == 'String' }
                && (!hasNullGuard(source) || !hasBlankCheck(source))) {
            violations.add("${path} domain record '${type.name}' must null-check and blank-check String components")
        }
        if (type.name.endsWith('Id')
                && components.size() == 1
                && normalizeType(components.first().type) == 'String'
                && !hasGenerateFactory(type)) {
            violations.add("${path} identifier VO '${type.name}' must expose a generate factory")
        }
        RecordComponent entityId = components.find { component ->
            component.name == 'id' && isIdentifierVoType(component.type)
        }
        if (entityId != null && !hasEqualsAndHashCode(type)) {
            violations.add("${path} domain entity record with identifier VO must override equals and hashCode using id")
        }
          if (entityId != null && !hasDomainStaticFactoryReturning(type)) {
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

    private static void validateCrossContextAggregateReferences(
              Project project,
              File file,
              String source,
              String packageName,
              TypeDeclaration type,
              HexagonalConventionExtension convention,
              List<String> violations
      ) {
          String currentContext = boundedContextName(packageName)
          if (currentContext == null) {
              return
          }

          Set<String> memberTypeNames = domainMemberTypeNames(type)
          Set<String> dependencies = new LinkedHashSet<>(SourceInspector.extractImports(source))
          dependencies.addAll(type.astType.qualifiedTypeReferences)
          dependencies.each { String dependency ->
              if (!dependency.contains(".${convention.domainPackageSegment}.model.")) {
                  return
              }

              String referencedContext = boundedContextName(dependency)
              if (referencedContext == null || referencedContext == currentContext) {
                  return
              }

              String referencedTypeName = dependency.substring(dependency.lastIndexOf('.') + 1)
              if (isIdentifierVoType(referencedTypeName)) {
                  return
              }
              if (!memberTypeNames.contains(referencedTypeName)) {
                  return
              }

              violations.add("${project.relativePath(file)} domain must not depend directly on another context model '${referencedTypeName}'; translate it into a context-owned type, or use an identifier VO for an identifiable reference")
          }
      }

      private static Set<String> domainMemberTypeNames(TypeDeclaration type) {
          Set<String> memberTypeNames = []
          extractFieldDeclarations(type).each { FieldDeclaration field ->
              memberTypeNames.addAll(tokenizedTypeNames(field.type))
          }
          if (type.kind == 'record') {
              extractRecordComponents(type).each { RecordComponent component ->
                  memberTypeNames.addAll(tokenizedTypeNames(component.type))
              }
          }
          return memberTypeNames
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
        if (!type.hasAnnotation('Getter')) {
            violations.add("${path} JPA entity '${type.name}' must declare @Getter")
        }
        if (!hasProtectedNoArgsConstructor(type)) {
            violations.add("${path} JPA entity '${type.name}' must declare @NoArgsConstructor(access = AccessLevel.PROTECTED)")
        }
        if (['Setter', 'Data', 'Builder'].any { annotation -> type.hasAnnotation(annotation) }) {
            violations.add("${path} JPA entity '${type.name}' must not use @Setter, @Data, or @Builder")
        }
        if (hasPublicField(type)) {
            violations.add("${path} JPA entity '${type.name}' must not declare public fields")
        }
        if (hasPublicConstructor(type)) {
            violations.add("${path} JPA entity '${type.name}' must not expose public constructors")
        }
        if (hasJpaRelationAnnotation(type)) {
            violations.add("${path} JPA entity '${type.name}' must not use object relation mappings")
        }

        validateJpaId(project, file, type, violations)
        validateDomainIdentifierColumn(project, file, type, violations)
        validateJpaReferenceFields(project, file, source, type, violations)

        if (hasNonPrivateArgumentConstructor(type)) {
            violations.add("${path} JPA entity '${type.name}' argument constructors must be private")
        }
        if (!hasStaticFactoryReturning(type)) {
            violations.add("${path} JPA entity '${type.name}' must expose a static factory returning '${type.name}'")
        }
    }

    private static void validateJpaId(
            Project project,
            File file,
            TypeDeclaration type,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        JavaSourceAstInspector.FieldModel idField = type.astType.fields.find { field ->
            field.annotation('Id') != null
        }
        if (idField == null
                || !idField.modifiers.contains('PRIVATE')
                || simplifyTypeName(idField.type) != 'Long') {
            violations.add("${path} JPA entity '${type.name}' must declare private Long id with @Id")
            return
        }

        if (idField.name != 'id') {
            violations.add("${path} JPA technical key must be named 'id', not '${idField.name}'")
        }
        if (!hasLombokAccessLevel(idField.annotation('Getter'), 'NONE')) {
            violations.add("${path} JPA technical key 'id' must declare @Getter(AccessLevel.NONE)")
        }
    }

    private static void validateDomainIdentifierColumn(
            Project project,
            File file,
            TypeDeclaration type,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        String identifierField = domainIdentifierFieldName(type.name)
        JavaSourceAstInspector.FieldModel domainIdField = type.astType.fields.find { field ->
            field.name == identifierField
        }
        JavaSourceAstInspector.AnnotationModel column = domainIdField?.annotation('Column')
        if (domainIdField == null
                || column == null
                || !domainIdField.modifiers.contains('PRIVATE')
                || simplifyTypeName(domainIdField.type) != 'String') {
            violations.add("${path} JPA entity '${type.name}' must declare private String ${identifierField} with @Column")
            return
        }

        if (normalizedAnnotationArgument(column, 'unique') != 'true') {
            violations.add("${path} JPA domain identifier column '${identifierField}' must be unique")
        }
        if (normalizedAnnotationArgument(column, 'length') != '32') {
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
        extractFieldDeclarations(type).each { FieldDeclaration field ->
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
        if (!hasPrivateNoArgConstructor(type)) {
            violations.add("${path} mapper '${type.name}' must declare a private constructor")
        }
        if (['Component', 'Service', 'Repository'].any { annotation -> type.hasAnnotation(annotation) }) {
            violations.add("${path} mapper '${type.name}' must not be registered as a Spring bean")
        }
        if (!extractInstanceFieldDeclarations(type).isEmpty()) {
            violations.add("${path} mapper '${type.name}' must not keep instance state")
        }
        extractNonPrivateStaticMethodNames(type).findAll { String methodName ->
            !revealsMapperConversionDirection(methodName)
        }.each { String methodName ->
            violations.add("${path} mapper '${type.name}' method '${methodName}' must reveal conversion direction with toDomain, toEntity, or updateEntity")
        }
        String searchableSource = stripCommentsAndStrings(source)
        if ((searchableSource =~ /(?m)\b(?:UUID\s*\.\s*randomUUID|(?:Instant|LocalDate|LocalDateTime|OffsetDateTime|ZonedDateTime)\s*\.\s*now|System\s*\.\s*currentTimeMillis)\s*\(/).find()) {
            violations.add("${path} mapper '${type.name}' must not generate time or UUID values")
        }
        if ((searchableSource =~ /(?m)\b(?:setAccessible|getDeclaredField|getDeclaredFields|getDeclaredConstructor|getDeclaredConstructors)\s*\(/).find()) {
            violations.add("${path} mapper '${type.name}' must not use reflection to access private state")
        }
    }

    private static boolean revealsMapperConversionDirection(String methodName) {
        return methodName ==~ /(?:toDomain.*|toEntity.*|to[A-Z][A-Za-z0-9]*(?:Domain|Entity|Entities)[A-Za-z0-9]*|updateEntity.*)/
    }

    private static Set<String> extractNonPrivateStaticMethodNames(TypeDeclaration type) {
        return type.astType.methods.findAll { method ->
            !method.constructor
                    && method.modifiers.contains('STATIC')
                    && !method.modifiers.contains('PRIVATE')
        }.collect { method -> method.name }.toSet()
    }

    private static void validateController(
            Project project,
            File file,
            String source,
            TypeDeclaration type,
            HexagonalConventionExtension convention,
            List<String> violations
    ) {
        String path = project.relativePath(file)
        extractInstanceFieldDeclarations(type).findAll { field ->
            !simplifyTypeName(field.type).endsWith('UseCase')
        }.each { field ->
            violations.add(
                    "${path} controller collaborator '${field.name}' must be an inbound UseCase"
            )
        }
        SourceInspector.extractImports(source).each { String imported ->
            boolean applicationImport = SourceInspector.isInLayer(imported, convention.applicationPackageSegment)
            boolean allowedInboundPort = imported.contains('.port.in.')
            if (SourceInspector.isInLayer(imported, convention.infrastructurePackageSegment)
                    || (applicationImport && !allowedInboundPort)) {
                violations.add("${path} controller must depend on inbound UseCase ports only, not '${imported}'")
            }
        }
        if (controllerReturnsLayerType(source, type, convention.domainPackageSegment)) {
            violations.add("${path} controller must not expose domain types as response return values")
        }
        if (controllerCreatesProblemDetail(source, type)) {
            violations.add("${path} controller must not create or return ProblemDetail directly")
        }
    }

    private static List<FieldDeclaration> extractFieldDeclarations(TypeDeclaration type) {
        return type.fields
    }

    private static List<FieldDeclaration> extractInstanceFieldDeclarations(TypeDeclaration type) {
        return type.fields.findAll { field -> !field.staticField }
    }

    private static List<RecordComponent> extractRecordComponents(TypeDeclaration type) {
        return type.recordComponents
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
            TypeDeclaration type,
            String source,
            List<String> violations
    ) {
        if (!type.isAbstract()) {
            if (hasNonPrivateConstructor(type)) {
                violations.add("${path} ${layer} exception '${type.name}' must keep constructors private and expose static factory methods")
            }
            if (!hasAnyStaticFactoryReturning(type)) {
                violations.add("${path} ${layer} exception '${type.name}' must expose at least one static factory returning '${type.name}'")
            }
        }
        if (constructsSameExceptionWithStringLiteral(source, type.name)) {
            violations.add("${path} ${layer} exception '${type.name}' must use ErrorCode-based constructors, not string messages")
        }
        validateExceptionDetailFieldNames(path, layer, type, violations)
    }

    private static void validateExceptionDetailFieldNames(
            String path,
            String layer,
            TypeDeclaration type,
            List<String> violations
    ) {
        Set<String> methodNames = type.astType.methods.findAll { method ->
            !method.constructor
                    && method.parameterTypes.isEmpty()
                    && method.modifiers.contains('PUBLIC')
                    && !method.modifiers.contains('STATIC')
        }.collect { method -> method.name }.toSet()
        extractInstanceFieldDeclarations(type)
                .findAll { FieldDeclaration field -> methodNames.contains(field.name) }
                .each { FieldDeclaration field ->
                    violations.add("${path} ${layer} exception detail field '${field.name}' must use a context-specific internal name instead of matching accessor '${field.name}()'")
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

    private static boolean hasNonPrivateConstructor(TypeDeclaration type) {
        return type.astType.methods.any { method ->
            method.constructor && !method.modifiers.contains('PRIVATE')
        }
    }

    private static boolean hasAnyStaticFactoryReturning(TypeDeclaration type) {
        return type.astType.methods.any { method ->
            !method.constructor
                    && method.modifiers.contains('STATIC')
                    && !method.modifiers.contains('PRIVATE')
                    && !method.modifiers.contains('PROTECTED')
                    && simplifyTypeName(method.returnType) == type.name
        }
    }

    private static boolean hasNullGuard(String source) {
        return (source =~ /\brequireNonNull\s*\(/).find()
                || (source =~ /(?m)\b[A-Za-z_][A-Za-z0-9_]*\s*==\s*null\b/).find()
                || (source =~ /(?m)\bnull\s*==\s*[A-Za-z_][A-Za-z0-9_]*\b/).find()
    }

    private static boolean hasBlankCheck(String source) {
        return (source =~ /\.isBlank\s*\(/).find()
    }

    private static boolean hasJpaRelationAnnotation(TypeDeclaration type) {
        return JPA_RELATION_ANNOTATIONS.any { String annotation ->
            type.astType.annotation(annotation) != null
                    || type.astType.fields.any { field -> field.annotation(annotation) != null }
                    || type.astType.methods.any { method -> method.annotation(annotation) != null }
        }
    }

    private static String simplifyTypeName(String type) {
        String withoutGenerics = type.replaceAll(/<.*>/, '').trim()
        String simpleName = withoutGenerics.tokenize('.').last()
        return simpleName.replaceAll(/\s+/, '')
    }

    private static boolean isSpringComponentType(
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
        return applicationService || adapter || [
                'Component',
                'Service',
                'Repository',
                'RestController',
                'Controller',
                'RestControllerAdvice',
                'ControllerAdvice'
        ].any { annotation -> type.hasAnnotation(annotation) }
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

    private static boolean isWebMvcConfiguration(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /\bWebMvcConfigurer\b/).find()
                || (searchableSource =~ /\badd(?:Interceptors|ArgumentResolvers)\s*\(/).find()
    }

    private static boolean instantiatesWebMvcExtension(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /\bnew\s+[A-Za-z_][A-Za-z0-9_]*(?:Interceptor|ArgumentResolver)\s*\(/).find()
    }

    private static boolean hasProtectedNoArgsConstructor(TypeDeclaration type) {
        return hasLombokAccessLevel(type.astType.annotation('NoArgsConstructor'), 'PROTECTED')
    }

    private static boolean hasLombokAccessLevel(
            JavaSourceAstInspector.AnnotationModel annotation,
            String accessLevel
    ) {
        String configuredAccess = annotation?.arguments?.get('access')
                ?: annotation?.arguments?.get('value')
        if (configuredAccess == null) {
            return false
        }
        String normalizedAccess = configuredAccess.replaceAll(/\s+/, '')
        return normalizedAccess == accessLevel || normalizedAccess.endsWith(".${accessLevel}")
    }

    private static String normalizedAnnotationArgument(
            JavaSourceAstInspector.AnnotationModel annotation,
            String argumentName
    ) {
        String value = annotation.arguments.get(argumentName)
        return value == null ? null : value.replaceAll(/\s+/, '')
    }

    private static boolean hasPrivateNoArgConstructor(TypeDeclaration type) {
        return type.astType.methods.any { method ->
            method.constructor
                    && method.parameterTypes.isEmpty()
                    && method.modifiers.contains('PRIVATE')
        }
    }

    private static boolean hasNonPrivateArgumentConstructor(TypeDeclaration type) {
        return type.astType.methods.any { method ->
            method.constructor
                    && !method.parameterTypes.isEmpty()
                    && !method.modifiers.contains('PRIVATE')
        }
    }

    private static boolean hasCompactConstructor(TypeDeclaration type) {
        return type.astType.methods.findAll { method -> method.constructor }.any { constructor ->
            int constructorNameStart = constructor.declarationHeader.lastIndexOf(type.name)
            if (constructorNameStart < 0) {
                return false
            }
            String signatureSuffix = constructor.declarationHeader.substring(
                    constructorNameStart + type.name.length()
            )
            return !signatureSuffix.contains('(')
        }
    }

    private static boolean hasDomainStaticFactoryReturning(TypeDeclaration type) {
        Set<String> factoryNames = ['create', 'from', 'of', 'reconstitute', 'pending', 'generate'] as Set
        return type.astType.methods.any { method ->
            !method.constructor
                    && factoryNames.contains(method.name)
                    && method.modifiers.contains('STATIC')
                    && !method.modifiers.contains('PRIVATE')
                    && !method.modifiers.contains('PROTECTED')
                    && simplifyTypeName(method.returnType) == type.name
        }
    }

    private static boolean hasStaticFactoryReturning(TypeDeclaration type) {
        return type.astType.methods.any { method ->
            !method.constructor
                    && method.modifiers.contains('STATIC')
                    && !method.modifiers.contains('PRIVATE')
                    && !method.modifiers.contains('PROTECTED')
                    && simplifyTypeName(method.returnType) == type.name
        }
    }

    private static boolean hasGenerateFactory(TypeDeclaration type) {
        return type.astType.methods.any { method ->
            !method.constructor
                    && method.name == 'generate'
                    && method.modifiers.contains('STATIC')
                    && !method.modifiers.contains('PRIVATE')
                    && !method.modifiers.contains('PROTECTED')
                    && simplifyTypeName(method.returnType) == type.name
        }
    }

    private static boolean hasSerialVersionUid(TypeDeclaration type) {
        return type.astType.fields.any { field -> field.name == 'serialVersionUID' }
    }

    private static boolean hasPublicField(TypeDeclaration type) {
        return type.astType.fields.any { field ->
            !field.staticField && field.modifiers.contains('PUBLIC')
        }
    }

    private static boolean hasPublicConstructor(TypeDeclaration type) {
        return type.astType.methods.any { method ->
            method.constructor && method.modifiers.contains('PUBLIC')
        }
    }

    private static boolean checksNullElements(String source) {
        return (source =~ /Objects::isNull/).find()
                || (source =~ /\.contains\s*\(\s*null\s*\)/).find()
                || (source =~ /(?s)\.anyMatch\s*\([^)]*->[^)]*==\s*null[^)]*\)/).find()
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

    private static boolean hasNonKoreanUserFacingApiErrorMessage(String source) {
        String sourceWithoutComments = stripComments(source)
        def matcher = sourceWithoutComments =~ /(?s)\b(?:message|ApiErrorMessage)\s*\(([^;{}]*?)\)/
        while (matcher.find()) {
            List<String> literals = []
            def literalMatcher = matcher.group(1) =~ /"((?:\\.|[^"\\])*)"/
            while (literalMatcher.find()) {
                literals.add(literalMatcher.group(1))
            }
            if (literals.size() >= 2) {
                List<String> userFacingMessages = literals.takeRight(2)
                if (userFacingMessages.any { String message -> !containsKorean(message) }) {
                    return true
                }
            }
        }
        return false
    }

      private static boolean usesEnumNameAsProviderMapKey(String source) {
          String sourceWithoutComments = stripComments(source)
          return (sourceWithoutComments =~ /(?s)\bMap\.(?:entry|of|ofEntries)\s*\([^;{}]*\.name\s*\(/).find()
      }

      private static boolean usesStringLiteralAsProviderMapKey(String source) {
          String sourceWithoutComments = stripComments(source)
          return (sourceWithoutComments =~ /(?s)\bMap\.(?:entry|of|ofEntries)\s*\(\s*"[^"]+"\s*,/).find()
      }

    private static boolean usesApiExcludePathPatterns(String source) {
        return (source =~ /(?s)\.excludePathPatterns\s*\([^;]*"\/api\//).find()
    }

    private static boolean isAuthenticatedMemberArgumentResolver(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /\bAuthenticatedMember\b/).find()
                && (searchableSource =~ /\bHandlerMethodArgumentResolver\b/).find()
    }

    private static boolean returnsNull(String source) {
        return (stripCommentsAndStrings(source) =~ /(?m)\breturn\s+null\s*;/).find()
    }

      private static boolean callsRepositoryWithRawCommandValue(String source) {
          return (stripCommentsAndStrings(source) =~ /(?m)\.\s*(?:exists|find)[A-Za-z0-9_]*\s*\(\s*(?:command|cmd|query)\s*\.\s*[A-Za-z_][A-Za-z0-9_]*\s*\(\s*\)\s*\)/).find()
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

    private static boolean referencesInnerLayerPackage(
            String source,
            TypeDeclaration type,
            HexagonalConventionExtension convention
    ) {
        return typeDependencies(source, type).any { String dependency ->
            SourceInspector.isInLayer(dependency, convention.domainPackageSegment)
                    || SourceInspector.isInLayer(dependency, convention.applicationPackageSegment)
        }
    }

    private static Set<String> typeDependencies(String source, TypeDeclaration type) {
        Set<String> dependencies = new LinkedHashSet<>(SourceInspector.extractImports(source))
        dependencies.addAll(type.astType.qualifiedTypeReferences)
        return dependencies
    }

    private static boolean controllerReturnsLayerType(
            String source,
            TypeDeclaration type,
            String packageSegment
    ) {
        Set<String> importedSimpleNames = importedSimpleNamesInLayer(source, packageSegment)
        return extractPublicMethodReturnTypes(type).any { String returnType ->
            returnTypeReferencesLayer(returnType, importedSimpleNames, packageSegment)
        }
    }

    private static boolean controllerCreatesProblemDetail(String source, TypeDeclaration type) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /\bProblemDetail\s*\./).find()
                || (searchableSource =~ /\bnew\s+ProblemDetail\b/).find()
                || extractPublicMethodReturnTypes(type).any { String returnType ->
                    tokenizedTypeNames(returnType).contains('ProblemDetail')
                }
    }

    private static List<String> extractPublicMethodReturnTypes(TypeDeclaration type) {
        return type.astType.methods.findAll { method ->
            !method.constructor
                    && method.returnType != 'void'
                    && method.modifiers.contains('PUBLIC')
        }.collect { method -> method.returnType }
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

    private static boolean isRequestPackage(String packageName, HexagonalConventionExtension convention) {
        if (convention.enforceMsaWebAdapterBoundary) {
            return packageName.contains('.adapter.in.web.external.request')
                    || packageName.contains('.adapter.in.web.internal.request')
        }
        return packageName.contains('.request')
    }

    private static boolean isResponsePackage(String packageName, HexagonalConventionExtension convention) {
        if (convention.enforceMsaWebAdapterBoundary) {
            return packageName.contains('.adapter.in.web.external.response')
                    || packageName.contains('.adapter.in.web.internal.response')
        }
        return packageName.contains('.response')
    }

    private static boolean isMsaWebAdapterPackage(String packageName, HexagonalConventionExtension convention) {
        String webPackage = ".${convention.presentationPackageSegment}"
        String externalPackage = "${webPackage}.external"
        String internalPackage = "${webPackage}.internal"
        return packageName.contains(externalPackage) || packageName.contains(internalPackage)
    }

    private static boolean hasEqualsAndHashCode(TypeDeclaration type) {
        boolean hasEquals = type.astType.methods.any { method ->
            method.name == 'equals'
                    && method.modifiers.contains('PUBLIC')
                    && method.parameterTypes.size() == 1
                    && simplifyTypeName(method.returnType) == 'boolean'
        }
        boolean hasHashCode = type.astType.methods.any { method ->
            method.name == 'hashCode'
                    && method.modifiers.contains('PUBLIC')
                    && method.parameterTypes.isEmpty()
                    && simplifyTypeName(method.returnType) == 'int'
        }
        return hasEquals && hasHashCode
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
        final Set<String> annotations
        final List<FieldDeclaration> fields
        final List<RecordComponent> recordComponents
        final JavaSourceAstInspector.TypeModel astType

        private TypeDeclaration(
                String kind,
                String name,
                boolean finalType,
                Set<String> annotations,
                List<FieldDeclaration> fields,
                List<RecordComponent> recordComponents,
                JavaSourceAstInspector.TypeModel astType
        ) {
            this.kind = kind
            this.name = name
            this.finalType = finalType
            this.annotations = annotations
            this.fields = fields
            this.recordComponents = recordComponents
            this.astType = astType
        }

        static TypeDeclaration from(JavaSourceAstInspector.TypeModel model) {
            List<FieldDeclaration> fields = model.fields.collect { field ->
                new FieldDeclaration(field.type, field.name, field.staticField)
            }
            List<RecordComponent> recordComponents = model.recordComponents.collect { component ->
                new RecordComponent(component.type, component.name)
            }
            return new TypeDeclaration(
                    model.kind.toLowerCase(Locale.ROOT),
                    model.simpleName,
                    model.kind == 'RECORD' || model.modifiers.contains('FINAL'),
                    Collections.unmodifiableSet(model.annotations.collect { annotation ->
                        annotation.simpleName
                    }.toSet()),
                    Collections.unmodifiableList(fields),
                    Collections.unmodifiableList(recordComponents),
                    model
            )
        }

        boolean hasAnnotation(String simpleName) {
            return annotations.contains(simpleName)
        }

        boolean isAbstract() {
            return astType.modifiers.contains('ABSTRACT')
        }

        boolean extendsType(String simpleName) {
            return astType.superType != null && simplifyTypeName(astType.superType) == simpleName
        }

        boolean implementsTypeEndingWith(String suffix) {
            return countImplementedTypesEndingWith(suffix) > 0
        }

        int countImplementedTypesEndingWith(String suffix) {
            return astType.implementedTypes.count { implementedType ->
                simplifyTypeName(implementedType).endsWith(suffix)
            }
        }
    }

    private static class FieldDeclaration {
        final String type
        final String name
        final boolean staticField

        private FieldDeclaration(String type, String name, boolean staticField) {
            this.type = type
            this.name = name
            this.staticField = staticField
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
