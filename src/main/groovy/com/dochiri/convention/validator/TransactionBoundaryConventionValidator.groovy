package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
import com.dochiri.convention.support.JavaSourceAstInspector
import com.dochiri.convention.support.SourceInspector
import org.gradle.api.Project

import java.util.regex.Pattern

class TransactionBoundaryConventionValidator {
    private static final Set<String> EXTERNAL_SIDE_EFFECT_TYPES = [
            'ApplicationEventPublisher',
            'FeignClient',
            'HttpClient',
            'JavaMailSender',
            'KafkaTemplate',
            'MessageChannel',
            'RabbitTemplate',
            'RestTemplate',
            'S3Client',
            'SnsClient',
            'SqsClient',
            'WebClient'
    ] as Set
    private static final Set<String> EXTERNAL_SIDE_EFFECT_PORT_SUFFIXES = [
            'ClientPort',
            'EmailPort',
            'ExternalApiPort',
            'GatewayPort',
            'MailPort',
            'MessagePort',
            'NotificationPort',
            'ProducerPort',
            'PublisherPort',
            'SenderPort',
            'WebhookPort'
    ] as Set
    private static final Set<String> EXTERNAL_SIDE_EFFECT_NAMES = [
            'applicationEventPublisher',
            'eventPublisher',
            'feignClient',
            'httpClient',
            'javaMailSender',
            'kafkaTemplate',
            'mailSender',
            'messageChannel',
            'rabbitTemplate',
            'restTemplate',
            's3Client',
            'snsClient',
            'sqsClient',
            'webClient'
    ] as Set

    static List<String> validateFile(
            Project project,
            File file,
            String source,
            String packageName,
            String typeName,
            HexagonalConventionExtension convention,
            AggregateBoundaryConventionValidator.Analysis aggregateAnalysis,
            JavaSourceAstInspector.TypeModel type
    ) {
        List<String> violations = []
        String path = project.relativePath(file)
        boolean applicationService = SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)
                && packageName.contains('.service')

        if (hasTransactionalAnnotation(type) && !applicationService) {
            violations.add("${path} @Transactional is only allowed on concrete application services")
        }
        if (usesForbiddenTransactionControl(source, type)) {
            violations.add("${path} must not use REQUIRES_NEW, NESTED, NOT_SUPPORTED, or TransactionTemplate")
        }
        if (!applicationService) {
            return violations
        }

        if (type.annotation('Transactional') != null) {
            violations.add("${path} @Transactional must be declared on public application service methods, not on the class")
        }
        findNonPublicMethodNamesWithAnnotation(type, 'Transactional').each { String methodName ->
            violations.add("${path} non-public method '${methodName}' must not declare @Transactional")
        }
        List<MethodBody> publicMethods = extractPublicMethodBodies(type)
        findPublicMethodNamesWithoutAnnotation(publicMethods, 'Transactional').each { String methodName ->
            violations.add("${path} public application service method '${methodName}' must declare @Transactional")
        }

        Set<String> useCaseNames = extractImplementedUseCaseNames(type)
        findQueryMethodsWithoutReadOnlyTransaction(publicMethods, typeName, useCaseNames).each { String methodName ->
            violations.add("${path} query application service method '${methodName}' must declare @Transactional(readOnly = true)")
        }
        findReadOnlyTransactionalMethodsCallingRepositoryMutation(type, publicMethods).each { String methodName ->
            violations.add("${path} read-only transaction method '${methodName}' must not call repository mutation methods")
        }
        findReadOnlyTransactionalCommandMethods(publicMethods, typeName, useCaseNames).each { String methodName ->
            violations.add("${path} state-changing application service method '${methodName}' must not use readOnly = true")
        }
        findTransactionalSelfInvocations(publicMethods).each { String methodName ->
            violations.add("${path} transactional method '${methodName}' must not be called through self-invocation")
        }
        findTransactionalMethodsCallingExternalSideEffects(type, publicMethods).each { String methodName ->
            violations.add("${path} transaction method '${methodName}' must not call external side effects inside the transaction")
        }
        findTransactionalMethodsModifyingMultipleRepositories(
                file,
                type,
                publicMethods,
                aggregateAnalysis
        ).each { RepositoryMutation mutation ->
            violations.add("${path} application service method '${mutation.methodName}' must not modify multiple aggregate repositories in one transaction: ${mutation.repositoryNames.join(', ')}")
        }
        return violations
    }

    private static List<String> findQueryMethodsWithoutReadOnlyTransaction(
            List<MethodBody> methods,
            String typeName,
            Set<String> useCaseNames
    ) {
        methods
                .findAll { MethodBody method ->
                    isQueryOperation(method, typeName, useCaseNames)
                            && hasTransactionalAnnotation(method.annotations)
                            && !hasReadOnlyTransaction(method.annotations)
                }
                .collect { MethodBody method -> method.methodName }
    }

    private static List<String> findReadOnlyTransactionalMethodsCallingRepositoryMutation(
            JavaSourceAstInspector.TypeModel type,
            List<MethodBody> methods
    ) {
        Set<String> repositoryFieldNames = extractRepositoryFieldNames(type)
        if (repositoryFieldNames.isEmpty()) {
            return []
        }

        methods
                .findAll { MethodBody method ->
                    hasReadOnlyTransaction(method.annotations)
                            && callsAnyRepositoryMutation(method.body, repositoryFieldNames)
                }
                .collect { MethodBody method -> method.methodName }
    }

    private static List<String> findReadOnlyTransactionalCommandMethods(
            List<MethodBody> methods,
            String typeName,
            Set<String> useCaseNames
    ) {
        methods
                .findAll { MethodBody method ->
                    hasReadOnlyTransaction(method.annotations)
                            && isStateChangingOperation(method, typeName, useCaseNames)
                }
                .collect { MethodBody method -> method.methodName }
    }

    private static List<String> findTransactionalSelfInvocations(List<MethodBody> methods) {
        Set<String> transactionalMethodNames = methods
                .findAll { MethodBody method -> hasTransactionalAnnotation(method.annotations) }
                .collect { MethodBody method -> method.methodName }
                .toSet()
        if (transactionalMethodNames.isEmpty()) {
            return []
        }

        Set<String> invokedMethodNames = []
        methods.each { MethodBody method ->
            String searchableBody = stripCommentsAndStrings(method.body)
            transactionalMethodNames.each { String transactionalMethodName ->
                if (callsSelfMethod(searchableBody, transactionalMethodName)) {
                    invokedMethodNames.add(transactionalMethodName)
                }
            }
        }
        return invokedMethodNames.sort()
    }

    private static List<String> findTransactionalMethodsCallingExternalSideEffects(
            JavaSourceAstInspector.TypeModel type,
            List<MethodBody> methods
    ) {
        Set<String> sideEffectFieldNames = extractExternalSideEffectFieldNames(type)
        methods
                .findAll { MethodBody method ->
                    hasTransactionalAnnotation(method.annotations)
                            && callsExternalSideEffect(method.body, sideEffectFieldNames)
                }
                .collect { MethodBody method -> method.methodName }
    }

    private static List<RepositoryMutation> findTransactionalMethodsModifyingMultipleRepositories(
            File file,
            JavaSourceAstInspector.TypeModel type,
            List<MethodBody> methods,
            AggregateBoundaryConventionValidator.Analysis aggregateAnalysis
    ) {
        List<FieldDeclaration> repositoryFields = extractRepositoryFields(type)
        if (repositoryFields.size() < 2) {
            return []
        }

        List<RepositoryMutation> mutations = []
        methods.each { MethodBody method ->
            if (hasTransactionalAnnotation(method.annotations) && !hasReadOnlyTransaction(method.annotations)) {
                List<FieldDeclaration> modifiedRepositories = repositoryFields.findAll { repositoryField ->
                    callsRepositoryMutation(method.body, repositoryField.name)
                }.sort { left, right -> left.name <=> right.name }
                if (modifiedRepositories.size() > 1
                        && modifiesDifferentAggregateRoots(file, modifiedRepositories, aggregateAnalysis)) {
                    mutations.add(new RepositoryMutation(
                            method.methodName,
                            modifiedRepositories.collect { repositoryField -> repositoryField.name }
                    ))
                }
            }
        }
        return mutations
    }

    private static boolean modifiesDifferentAggregateRoots(
            File file,
            List<FieldDeclaration> repositoryFields,
            AggregateBoundaryConventionValidator.Analysis aggregateAnalysis
    ) {
        if (aggregateAnalysis == null) {
            return true
        }
        List<String> aggregateRoots = repositoryFields.collect { repositoryField ->
            aggregateAnalysis.aggregateRootForRepository(file, repositoryField.type)
        }
        if (aggregateRoots.any { aggregateRoot -> aggregateRoot == null }) {
            return true
        }
        return aggregateRoots.toSet().size() > 1
    }

    private static boolean isQueryOperation(MethodBody method, String typeName, Set<String> useCaseNames) {
        return isQueryName(method.methodName)
                || method.parameterTypes.any { String parameterType -> simplifyTypeName(parameterType).endsWith('Query') }
                || isQueryName(typeName.replaceFirst(/Service$/, ''))
                || useCaseNames.any { String useCaseName -> isQueryName(useCaseName.replaceFirst(/UseCase$/, '')) }
    }

    private static boolean isStateChangingOperation(MethodBody method, String typeName, Set<String> useCaseNames) {
        return isStateChangingName(method.methodName)
                || method.parameterTypes.any { String parameterType -> simplifyTypeName(parameterType).endsWith('Command') }
                || isStateChangingName(typeName.replaceFirst(/Service$/, ''))
                || useCaseNames.any { String useCaseName -> isStateChangingName(useCaseName.replaceFirst(/UseCase$/, '')) }
    }

    private static boolean isQueryName(String name) {
        return name ==~ /^(?:get|list|find|search|count|exists|load|read).*/
    }

    private static boolean isStateChangingName(String name) {
        return name ==~ /^(?:add|approve|cancel|clear|complete|create|delete|handle|pay|place|register|reject|remove|save|ship|start|update|write).*/
    }

    private static Set<String> extractImplementedUseCaseNames(JavaSourceAstInspector.TypeModel type) {
        return type.implementedTypes
                .collect { String implementedType -> simplifyTypeName(implementedType) }
                .findAll { String implementedType -> implementedType.endsWith('UseCase') }
                .toSet()
    }

    private static List<MethodBody> extractPublicMethodBodies(JavaSourceAstInspector.TypeModel type) {
        return type.methods
                .findAll { method ->
                    !method.constructor
                            && method.hasBody
                            && method.modifiers.contains('PUBLIC')
                            && !method.modifiers.contains('STATIC')
                }
                .collect { method ->
                    new MethodBody(
                            method.name,
                            method.annotations,
                            method.parameterTypes,
                            method.body
                    )
        }
    }

    private static List<String> findPublicMethodNamesWithoutAnnotation(
            List<MethodBody> methods,
            String annotation
    ) {
        return methods.findAll { method ->
            method.annotation(annotation) == null
        }.collect { method -> method.methodName }
    }

    private static List<String> findNonPublicMethodNamesWithAnnotation(
            JavaSourceAstInspector.TypeModel type,
            String annotation
    ) {
        return type.methods.findAll { method ->
            !method.constructor
                    && method.hasBody
                    && !method.modifiers.contains('PUBLIC')
                    && method.annotation(annotation) != null
        }.collect { method -> method.name }
    }

    private static Set<String> extractRepositoryFieldNames(JavaSourceAstInspector.TypeModel type) {
        extractRepositoryFields(type)
                .collect { FieldDeclaration field -> field.name }
                .toSet()
    }

    private static List<FieldDeclaration> extractRepositoryFields(JavaSourceAstInspector.TypeModel type) {
        return extractInstanceFieldDeclarations(type).findAll { FieldDeclaration field ->
            field.type.endsWith('RepositoryPort')
                    || field.type.endsWith('Repository')
                    || field.name.endsWith('RepositoryPort')
                    || field.name.endsWith('Repository')
        }
    }

    private static Set<String> extractExternalSideEffectFieldNames(JavaSourceAstInspector.TypeModel type) {
        extractInstanceFieldDeclarations(type)
                .findAll { FieldDeclaration field ->
                    EXTERNAL_SIDE_EFFECT_TYPES.contains(simplifyTypeName(field.type))
                            || EXTERNAL_SIDE_EFFECT_NAMES.contains(field.name)
                            || isExternalSideEffectPort(field)
                }
                .collect { FieldDeclaration field -> field.name }
                .toSet()
    }

    private static boolean isExternalSideEffectPort(FieldDeclaration field) {
        String simpleType = simplifyTypeName(field.type)
        return EXTERNAL_SIDE_EFFECT_PORT_SUFFIXES.any { String suffix -> simpleType.endsWith(suffix) }
                || (simpleType.endsWith('Port') && field.name ==~ /.*(?:Publisher|Producer|Sender|Client|Gateway|Notifier|Notification|Mail|Email|Message|Webhook|ExternalApi).*/)
    }

    private static List<FieldDeclaration> extractInstanceFieldDeclarations(
            JavaSourceAstInspector.TypeModel type
    ) {
        return type.fields.findAll { field -> !field.staticField }.collect { field ->
            new FieldDeclaration(field.type, field.name)
        }
    }

    private static boolean callsAnyRepositoryMutation(String body, Set<String> repositoryFieldNames) {
        repositoryFieldNames.any { String repositoryName ->
            callsRepositoryMutation(body, repositoryName)
        }
    }

    private static boolean callsRepositoryMutation(String body, String repositoryName) {
        String searchableBody = stripCommentsAndStrings(body)
        String quotedRepositoryName = Pattern.quote(repositoryName)
        return (searchableBody =~ /(?m)\b${quotedRepositoryName}\s*\.\s*(?:save|delete|update|insert|persist|remove)[A-Za-z0-9_]*\s*\(/).find()
    }

    private static boolean callsSelfMethod(String searchableBody, String methodName) {
        String quotedMethodName = Pattern.quote(methodName)
        return (searchableBody =~ /(?m)\bthis\s*\.\s*${quotedMethodName}\s*\(/).find()
                || (searchableBody =~ /(?m)(?:^|[^A-Za-z0-9_.])${quotedMethodName}\s*\(/).find()
    }

    private static boolean callsExternalSideEffect(String body, Set<String> sideEffectFieldNames) {
        String searchableBody = stripCommentsAndStrings(body)
        if (sideEffectFieldNames.any { String fieldName ->
            (searchableBody =~ /(?m)\b${Pattern.quote(fieldName)}\s*\./).find()
        }) {
            return true
        }

        return (searchableBody =~ /(?m)\b(?:Files\s*\.\s*(?:write|delete|copy|move)|new\s+(?:FileOutputStream|FileWriter))\b/).find()
                || (searchableBody =~ /(?m)\b(?:WebClient|RestTemplate|KafkaTemplate|RabbitTemplate|JavaMailSender|ApplicationEventPublisher)\b/).find()
    }

    private static boolean hasTransactionalAnnotation(JavaSourceAstInspector.TypeModel type) {
        return type.annotation('Transactional') != null || type.methods.any { method ->
            method.annotation('Transactional') != null
        }
    }

    private static boolean hasTransactionalAnnotation(
            List<JavaSourceAstInspector.AnnotationModel> annotations
    ) {
        return annotations.any { annotation -> annotation.simpleName == 'Transactional' }
    }

    private static boolean hasReadOnlyTransaction(
            List<JavaSourceAstInspector.AnnotationModel> annotations
    ) {
        JavaSourceAstInspector.AnnotationModel transaction = annotations.find { annotation ->
            annotation.simpleName == 'Transactional'
        }
        String readOnly = transaction?.arguments?.get('readOnly')
        return readOnly != null && readOnly.replaceAll(/\s+/, '') == 'true'
    }

    private static boolean usesForbiddenTransactionControl(
            String source,
            JavaSourceAstInspector.TypeModel type
    ) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)\bTransactionTemplate\b/).find()
                || transactionalAnnotations(type).any { annotation ->
                    String propagation = annotation.arguments.get('propagation')
                    propagation != null && propagation.replaceAll(/\s+/, '') ==~
                            /(?:[A-Za-z_][A-Za-z0-9_]*\.)?(?:REQUIRES_NEW|NESTED|NOT_SUPPORTED)/
                }
    }

    private static List<JavaSourceAstInspector.AnnotationModel> transactionalAnnotations(
            JavaSourceAstInspector.TypeModel type
    ) {
        List<JavaSourceAstInspector.AnnotationModel> annotations = []
        annotations.addAll(type.annotations.findAll { annotation ->
            annotation.simpleName == 'Transactional'
        })
        type.methods.each { method ->
            annotations.addAll(method.annotations.findAll { annotation ->
                annotation.simpleName == 'Transactional'
            })
        }
        return annotations
    }

    private static String stripCommentsAndStrings(String source) {
        return source
                .replaceAll(/(?s)\/\*.*?\*\//, ' ')
                .replaceAll(/(?m)\/\/.*$/, ' ')
                .replaceAll('(?s)"""[\\s\\S]*?"""', '""')
                .replaceAll(/(?s)"(?:\\.|[^"\\])*"/, '""')
                .replaceAll(/(?s)'(?:\\.|[^'\\])*'/, "''")
    }

    private static String simplifyTypeName(String type) {
        String withoutGenerics = type.replaceAll(/<.*>/, '').trim()
        String simpleName = withoutGenerics.tokenize('.').last()
        return simpleName.replaceAll(/\s+/, '')
    }

    private static class FieldDeclaration {
        final String type
        final String name

        private FieldDeclaration(String type, String name) {
            this.type = type
            this.name = name
        }
    }

    private static class MethodBody {
        final String methodName
        final List<JavaSourceAstInspector.AnnotationModel> annotations
        final List<String> parameterTypes
        final String body

        private MethodBody(
                String methodName,
                List<JavaSourceAstInspector.AnnotationModel> annotations,
                List<String> parameterTypes,
                String body
        ) {
            this.methodName = methodName
            this.annotations = annotations
            this.parameterTypes = parameterTypes
            this.body = body
        }

        JavaSourceAstInspector.AnnotationModel annotation(String simpleName) {
            return annotations.find { annotation -> annotation.simpleName == simpleName }
        }
    }

    private static class RepositoryMutation {
        final String methodName
        final List<String> repositoryNames

        private RepositoryMutation(String methodName, List<String> repositoryNames) {
            this.methodName = methodName
            this.repositoryNames = repositoryNames
        }
    }
}
