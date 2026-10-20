package io.github.dochiri0916.buildconvention.validator.adapter.web

import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector

final class WebExceptionConventionValidator {
    static List<String> validate(String path, String packageName,
                                 JavaSourceAstInspector.TypeModel type, Set<String> imports) {
        List<String> violations = []
        type.nestedTypes.each { violations.addAll(validate(path, packageName, it, imports)) }
        if (packageName.endsWith('.global.exception') && type.simpleName == 'ProblemDetails') {
            def factories = type.methods.findAll { it.name == 'create' }
            if (factories.empty || factories.any { factory ->
                !['code', 'timestamp'].every { property -> factory.invocations.any { call ->
                    call.selector.endsWith('.setProperty') && !call.literalArguments.empty && call.literalArguments.first() == property
                } }
            }) {
                violations.add("${path} ProblemDetails.create must populate code and timestamp properties")
            }
        }
        boolean advice = type.annotations.any {
            resolve(it.qualifiedName, imports) in ['org.springframework.web.bind.annotation.RestControllerAdvice',
                                                 'org.springframework.web.bind.annotation.ControllerAdvice']
        }
        if (!advice) return violations
        def handlers = type.methods.findAll { method -> method.annotations.any {
            resolve(it.qualifiedName, imports) == 'org.springframework.web.bind.annotation.ExceptionHandler'
        } }
        boolean fallback = handlers.any { method -> method.parameterTypes.any {
            it in ['Exception', 'Throwable', 'java.lang.Exception', 'java.lang.Throwable']
        } }
        String precedence = fallback ? 'LOWEST_PRECEDENCE' : 'HIGHEST_PRECEDENCE'
        def order = type.annotations.find { resolve(it.qualifiedName, imports) == 'org.springframework.core.annotation.Order' }
        String value = order?.arguments?.get('value')
        if (value != 'org.springframework.core.Ordered.' + precedence
                && !(value == 'Ordered.' + precedence && imports.contains('org.springframework.core.Ordered'))
                && !(value == precedence && imports.contains('org.springframework.core.Ordered.' + precedence))) {
            violations.add("${path} exception advice '${type.simpleName}' must declare @Order(Ordered.${precedence})")
        }
        Set<String> factories = type.fields.findAll { field ->
            String resolved = resolve(field.type, imports)
            resolved.endsWith('.global.exception.ProblemDetails')
                    || (resolved == 'ProblemDetails' && packageName.endsWith('.global.exception'))
        }.collect { it.name }.toSet()
        handlers.each { handler ->
            if (!handler.returnType?.contains('ProblemDetail')) return
            def reachable = reachableMethods(type, handler, [] as Set)
            def calls = reachable.collectMany { it.invocations }
            if (!calls.any { call -> factories.any { receiver ->
                call.selector in [receiver + '.create', 'this.' + receiver + '.create']
            } }) {
                violations.add("${path} exception response '${handler.name}' must be created by ProblemDetails.create")
            }
            if (calls.any { it.selector.endsWith('.forStatus') || it.selector.endsWith('.forStatusAndDetail') }) {
                violations.add("${path} exception advice must not construct ProblemDetail directly")
            }
            boolean technical = handler.parameterTypes.any {
                it in ['Exception', 'Throwable', 'java.lang.Exception', 'java.lang.Throwable']
                        || it.endsWith('RepositoryOperationException')
            }
            Set<String> technicalParameters = handler.parameterTypes.withIndex().findAll { entry ->
                entry[0] in ['Exception', 'Throwable', 'java.lang.Exception', 'java.lang.Throwable']
                        || entry[0].endsWith('RepositoryOperationException')
            }.collect { entry -> handler.parameterNames[entry[1]] }.toSet()
            if (technical) {
                def responses = calls.findAll { call -> factories.any { receiver ->
                    call.selector in [receiver + '.create', 'this.' + receiver + '.create']
                } }
                if (responses.any { call ->
                    if (call.arguments.size() != 3) return true
                    String status = constantExpression(type, call.arguments[0])
                    String statusOwner = status.contains('.') ? status.substring(0, status.lastIndexOf('.')) : ''
                    Object code = constantValue(type, call, 1)
                    Object detail = constantValue(type, call, 2)
                    return resolve(statusOwner, imports) != 'org.springframework.http.HttpStatus'
                            || !status.endsWith('.INTERNAL_SERVER_ERROR') || code != 'INTERNAL_ERROR'
                            || !(detail instanceof String) || !(detail =~ /[가-힣]/).find()
                }) {
                    violations.add("${path} technical exception response must use 500, INTERNAL_ERROR and a constant Korean message without internal details")
                }
            }
            if (!calls.any { it.selector.endsWith(technical ? '.error' : '.info')
                    || (!technical && it.selector.endsWith('.warn')) }) {
                violations.add("${path} exception handler '${handler.name}' must log at ${technical ? 'error' : 'info or warn'} level")
            }
            if (technical && (!calls.any { it.selector.endsWith('.getRequestURI') }
                    || !calls.any { it.selector.endsWith('.getMethod') }
                    || !calls.any { call -> call.selector.endsWith('.error')
                        && call.arguments.any { argument -> technicalParameters.contains(argument) } })) {
                violations.add("${path} technical exception handler '${handler.name}' must log URI, HTTP method and the original exception")
            }
        }
        return violations
    }

    private static List<JavaSourceAstInspector.MethodModel> reachableMethods(
            JavaSourceAstInspector.TypeModel type, JavaSourceAstInspector.MethodModel method, Set visited) {
        if (!visited.add(method)) return []
        List<JavaSourceAstInspector.MethodModel> result = [method]
        method.invocations.each { call ->
            String name = call.selector.replaceFirst(/^this\./, '')
            type.methods.findAll { candidate -> candidate.name == name
                    && candidate.parameterTypes.size() == call.arguments.size() }.each { candidate ->
                result.addAll(reachableMethods(type, candidate, visited))
            }
        }
        return result
    }

    private static String resolve(String name, Set<String> imports) {
        if (name.contains('.')) return name
        if (name in ['RestControllerAdvice', 'ControllerAdvice', 'ExceptionHandler']
                && imports.contains('org.springframework.web.bind.annotation.*')) {
            return 'org.springframework.web.bind.annotation.' + name
        }
        return imports.find { it.endsWith('.' + name) } ?: name
    }

    private static String constantExpression(JavaSourceAstInspector.TypeModel type, String expression) {
        def field = type.fields.find { it.name == expression && it.staticField && it.finalField }
        return field?.initializer ?: expression
    }

    private static Object constantValue(JavaSourceAstInspector.TypeModel type,
                                        JavaSourceAstInspector.InvocationModel call, int index) {
        if (call.literalArguments[index] != null) return call.literalArguments[index]
        return type.fields.find { it.name == call.arguments[index] && it.staticField && it.finalField }?.literalInitializer
    }
}
