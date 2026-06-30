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

    static List<String> validate(Project project, HexagonalConventionExtension convention) {
        List<String> violations = []

        SourceInspector.collectMainSourceFiles(project).findAll { File file ->
            file.name.endsWith('.java')
        }.each { File file ->
            String source = file.getText(StandardCharsets.UTF_8.name())
            String packageName = SourceInspector.extractPackageName(source)
            validateCommonPackageUsage(project, file, source, packageName, violations)

            TypeDeclaration type = TypeDeclaration.from(source)
            if (type == null) {
                return
            }

            validateTechnicalAnnotationPlacement(project, file, source, packageName, convention, violations)
            validateWebErrorTypePlacement(project, file, source, packageName, convention, violations)
            validateTypePackageConvention(project, file, packageName, type, convention, violations)
            validateSpringComponentRegistration(project, file, source, packageName, type, convention, violations)

            if (SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
                validateDomain(project, file, source, type, violations)
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

        return violations
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

    private static void validateTechnicalAnnotationPlacement(
            Project project,
            File file,
            String source,
            String packageName,
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

        if (SourceInspector.isInLayer(packageName, convention.domainPackageSegment)) {
            if (type.name.endsWith('Event')) {
                if (!packageName.contains('.event')) {
                    violations.add("${path} domain event '${type.name}' must live in domain.event package")
                }
                if (type.kind != 'record') {
                    violations.add("${path} domain event '${type.name}' must be a record")
                }
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

        if (type.name.endsWith('Exception')) {
            if (!(source =~ /\bextends\s+RuntimeException\b/).find()) {
                violations.add("${path} application exception '${type.name}' must extend RuntimeException")
            }
            if (!hasSerialVersionUid(source)) {
                violations.add("${path} application exception '${type.name}' must declare serialVersionUID")
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
            TypeDeclaration type,
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
            validateDomainRecord(project, file, source, type, violations)
        }

        extractFieldDeclarations(source).each { FieldDeclaration field ->
            if (isRawCollectionType(field.type)) {
                violations.add("${path} domain field '${field.name}' must use a first-class collection record instead of '${field.type}'")
            }
            if (isRawScalarType(field.type)) {
                violations.add("${path} domain field '${field.name}' must use a Value Object instead of raw scalar '${field.type}'")
            }
            if (field.name.endsWith('PublicId')) {
                violations.add("${path} domain reference field '${field.name}' must use '{Target}Id' naming, not '*PublicId'")
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
            TypeDeclaration type,
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

        boolean singleValueObject = isSingleComponentValueObject(type, components)
        components.each { RecordComponent component ->
            if (isRawScalarType(component.type) && !singleValueObject) {
                violations.add("${path} domain record component '${component.name}' must use a Value Object instead of raw scalar '${component.type}'")
            }
            if (component.name.endsWith('PublicId')) {
                violations.add("${path} domain reference component '${component.name}' must use '{Target}Id' naming, not '*PublicId'")
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
            if (field.name.endsWith('PublicId')) {
                violations.add("${path} JPA reference field '${field.name}' must use '{target}Id' naming, not '*PublicId'")
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
        return (source =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:Autowired|Component|Service|Repository|Entity|Table|Getter|Setter|Data|Builder|NoArgsConstructor)\b/).find()
    }

    private static boolean throwsJdkBasicException(String source) {
        return (source =~ /(?m)\bthrow\s+new\s+(?:IllegalArgumentException|IllegalStateException|NullPointerException)\b/).find()
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

    private static boolean hasAnyAnnotation(String source, List<String> annotations) {
        return annotations.any { String annotation -> hasAnnotation(source, annotation) }
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
                .replaceAll(/(?s)"(?:\\.|[^"\\])*"/, '""')
                .replaceAll(/(?s)'(?:\\.|[^'\\])*'/, "''")
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

    private static boolean looksLikeIdReference(String name) {
        return name ==~ /.*Id(s)?$/
    }

    private static boolean isIdentifierVoType(String type) {
        return normalizeType(type).endsWith('Id')
    }

    private static boolean isSingleComponentValueObject(TypeDeclaration type, List<RecordComponent> components) {
        return components.size() == 1 && !type.name.endsWith('Event')
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

    private static class RecordComponent {
        final String type
        final String name

        private RecordComponent(String type, String name) {
            this.type = type
            this.name = name
        }
    }
}
