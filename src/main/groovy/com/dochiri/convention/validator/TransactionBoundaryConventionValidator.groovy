package com.dochiri.convention.validator

import com.dochiri.convention.extension.HexagonalConventionExtension
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
            HexagonalConventionExtension convention
    ) {
        List<String> violations = []
        String path = project.relativePath(file)
        boolean applicationService = SourceInspector.isInLayer(packageName, convention.applicationPackageSegment)
                && packageName.contains('.service')

        if (hasAnnotation(source, 'Transactional') && !applicationService) {
            violations.add("${path} @Transactional is only allowed on concrete application services")
        }
        if (usesForbiddenTransactionControl(source)) {
            violations.add("${path} must not use REQUIRES_NEW, NESTED, NOT_SUPPORTED, or TransactionTemplate")
        }
        if (!applicationService) {
            return violations
        }

        if (hasClassLevelAnnotation(source, typeName, 'Transactional')) {
            violations.add("${path} @Transactional must be declared on public application service methods, not on the class")
        }
        findNonPublicMethodNamesWithAnnotation(source, 'Transactional').each { String methodName ->
            violations.add("${path} non-public method '${methodName}' must not declare @Transactional")
        }
        findPublicMethodNamesWithoutAnnotation(source, 'Transactional').each { String methodName ->
            violations.add("${path} public application service method '${methodName}' must declare @Transactional")
        }

        List<MethodBody> publicMethods = extractPublicMethodBodies(source)
        Set<String> useCaseNames = extractImplementedUseCaseNames(source, typeName)
        findQueryMethodsWithoutReadOnlyTransaction(publicMethods, typeName, useCaseNames).each { String methodName ->
            violations.add("${path} query application service method '${methodName}' must declare @Transactional(readOnly = true)")
        }
        findReadOnlyTransactionalMethodsCallingRepositoryMutation(source, publicMethods).each { String methodName ->
            violations.add("${path} read-only transaction method '${methodName}' must not call repository mutation methods")
        }
        findReadOnlyTransactionalCommandMethods(publicMethods, typeName, useCaseNames).each { String methodName ->
            violations.add("${path} state-changing application service method '${methodName}' must not use readOnly = true")
        }
        findTransactionalSelfInvocations(publicMethods).each { String methodName ->
            violations.add("${path} transactional method '${methodName}' must not be called through self-invocation")
        }
        findTransactionalMethodsCallingExternalSideEffects(source, publicMethods).each { String methodName ->
            violations.add("${path} transaction method '${methodName}' must not call external side effects inside the transaction")
        }
        findTransactionalMethodsModifyingMultipleRepositories(source, publicMethods).each { RepositoryMutation mutation ->
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
            String source,
            List<MethodBody> methods
    ) {
        Set<String> repositoryFieldNames = extractRepositoryFieldNames(source)
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
            String source,
            List<MethodBody> methods
    ) {
        Set<String> sideEffectFieldNames = extractExternalSideEffectFieldNames(source)
        methods
                .findAll { MethodBody method ->
                    hasTransactionalAnnotation(method.annotations)
                            && callsExternalSideEffect(method.body, sideEffectFieldNames)
                }
                .collect { MethodBody method -> method.methodName }
    }

    private static List<RepositoryMutation> findTransactionalMethodsModifyingMultipleRepositories(
            String source,
            List<MethodBody> methods
    ) {
        Set<String> repositoryFieldNames = extractRepositoryFieldNames(source)
        if (repositoryFieldNames.size() < 2) {
            return []
        }

        List<RepositoryMutation> mutations = []
        methods.each { MethodBody method ->
            if (hasTransactionalAnnotation(method.annotations) && !hasReadOnlyTransaction(method.annotations)) {
                List<String> modifiedRepositoryNames = repositoryFieldNames.findAll { String repositoryName ->
                    callsRepositoryMutation(method.body, repositoryName)
                }.sort()
                if (modifiedRepositoryNames.size() > 1) {
                    mutations.add(new RepositoryMutation(method.methodName, modifiedRepositoryNames))
                }
            }
        }
        return mutations
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

    private static Set<String> extractImplementedUseCaseNames(String source, String typeName) {
        String searchableSource = stripCommentsAndStrings(source)
        def matcher = searchableSource =~ /(?ms)\bclass\s+${Pattern.quote(typeName)}\s+implements\s+([^{]+)\{/
        if (!matcher.find()) {
            return []
        }
        splitTopLevelComma(matcher.group(1))
                .collect { String type -> simplifyTypeName(type) }
                .findAll { String type -> type.endsWith('UseCase') }
                .toSet()
    }

    private static List<MethodBody> extractPublicMethodBodies(String source) {
        List<MethodBody> methods = []
        def matcher = source =~ /(?ms)((?:^\s*@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s*)*)^\s*public\s+(?!class\b)(?!interface\b)(?!enum\b)(?!record\b)(?!static\b)(?:final\s+)?[A-Za-z_][A-Za-z0-9_$.<>, ?\[\]]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^;{}]*)\)\s*(?:throws\s+[^{]+)?\{/
        while (matcher.find()) {
            int bodyStart = matcher.end() - 1
            int bodyEnd = findMatchingBrace(source, bodyStart)
            if (bodyEnd < 0) {
                continue
            }
            methods.add(new MethodBody(
                    matcher.group(2),
                    matcher.group(1),
                    extractParameterTypes(matcher.group(3)),
                    source.substring(bodyStart + 1, bodyEnd)
            ))
        }
        return methods
    }

    private static List<String> extractParameterTypes(String parameters) {
        String normalizedParameters = parameters.trim()
        if (normalizedParameters.isEmpty()) {
            return []
        }
        splitTopLevelComma(normalizedParameters).collect { String parameter ->
            String normalized = parameter
                    .replaceAll(/(?s)@\w+(?:\([^)]*\))?\s*/, '')
                    .replaceAll(/\bfinal\s+/, '')
                    .trim()
            int lastSpace = normalized.lastIndexOf(' ')
            lastSpace < 0 ? normalized : normalized.substring(0, lastSpace).trim()
        }
    }

    private static List<String> findPublicMethodNamesWithoutAnnotation(String source, String annotation) {
        List<String> methodNames = []
        def matcher = source =~ /(?ms)((?:^\s*@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s*)*)^\s*public\s+(?!class\b)(?!interface\b)(?!enum\b)(?!record\b)(?!static\b)(?:final\s+)?[A-Za-z_][A-Za-z0-9_$.<>, ?\[\]]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{/
        while (matcher.find()) {
            String annotations = matcher.group(1)
            if (!(annotations =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*${annotation}\b/).find()) {
                methodNames.add(matcher.group(2))
            }
        }
        return methodNames
    }

    private static List<String> findNonPublicMethodNamesWithAnnotation(String source, String annotation) {
        List<String> methodNames = []
        def matcher = source =~ /(?ms)((?:^\s*@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s*)+)^\s*(?!public\b)(?:private\s+|protected\s+)?(?:final\s+)?[A-Za-z_][A-Za-z0-9_$.<>, ?\[\]]*\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{/
        while (matcher.find()) {
            String annotations = matcher.group(1)
            if ((annotations =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*${annotation}\b/).find()) {
                methodNames.add(matcher.group(2))
            }
        }
        return methodNames
    }

    private static Set<String> extractRepositoryFieldNames(String source) {
        extractInstanceFieldDeclarations(source)
                .findAll { FieldDeclaration field ->
                    field.type.endsWith('RepositoryPort')
                            || field.type.endsWith('Repository')
                            || field.name.endsWith('RepositoryPort')
                            || field.name.endsWith('Repository')
                }
                .collect { FieldDeclaration field -> field.name }
                .toSet()
    }

    private static Set<String> extractExternalSideEffectFieldNames(String source) {
        extractInstanceFieldDeclarations(source)
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

    private static boolean hasAnnotation(String source, String annotation) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*${annotation}\b/).find()
    }

    private static boolean hasClassLevelAnnotation(String source, String typeName, String annotation) {
        return (source =~ /(?ms)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*${annotation}\b(?:\([^)]*\))?\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+)?(?:final\s+|abstract\s+)?(?:class|record)\s+${typeName}\b/).find()
    }

    private static boolean hasTransactionalAnnotation(String annotations) {
        return (annotations =~ /(?m)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*Transactional\b/).find()
    }

    private static boolean hasReadOnlyTransaction(String annotations) {
        return (annotations =~ /(?s)@\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*Transactional\s*\([^)]*readOnly\s*=\s*true/).find()
    }

    private static boolean usesForbiddenTransactionControl(String source) {
        String searchableSource = stripCommentsAndStrings(source)
        return (searchableSource =~ /(?m)\bTransactionTemplate\b/).find()
                || (searchableSource =~ /(?m)\bPropagation\s*\.\s*(?:REQUIRES_NEW|NESTED|NOT_SUPPORTED)\b/).find()
                || (searchableSource =~ /(?m)\bpropagation\s*=\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)?(?:REQUIRES_NEW|NESTED|NOT_SUPPORTED)\b/).find()
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

    private static String normalizeType(String type) {
        return type.replaceAll(/\s+/, ' ').trim()
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
        final String annotations
        final List<String> parameterTypes
        final String body

        private MethodBody(String methodName, String annotations, List<String> parameterTypes, String body) {
            this.methodName = methodName
            this.annotations = annotations
            this.parameterTypes = parameterTypes
            this.body = body
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
