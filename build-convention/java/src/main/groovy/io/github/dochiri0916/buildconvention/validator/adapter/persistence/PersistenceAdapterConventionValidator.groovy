package io.github.dochiri0916.buildconvention.validator.adapter.persistence

import io.github.dochiri0916.buildconvention.source.java.JavaLexicalScanner
import io.github.dochiri0916.buildconvention.source.java.JavaMethodGraph
import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import java.util.regex.Pattern

/** Inspects storage operations and their local translation helpers as one adapter boundary. */
final class PersistenceAdapterConventionValidator {
    static List<String> validate(String path, String packageName,
                                 JavaSourceAstInspector.TypeModel type, Set<String> imports) {
        List<String> violations = []
        if (!packageName.contains('.adapter.out.persistence') || !type.simpleName.endsWith('Adapter')) return violations
        type.methods.findAll { it.modifiers.contains('PUBLIC') && !it.constructor }.each { method ->
            def methods = JavaMethodGraph.reachable(type, method)
            Set<String> repositories = repositoryNames(type, methods)
            def calls = methods.collectMany { it.invocations }
            def operations = calls.findAll { isRepositoryCall(it, repositories) }
            if (operations.empty) return
            if (!calls.any { translatorCall(it, 'execute', imports) || translatorCall(it, 'run', imports) }) {
                violations.add("${path} persistence adapter operation '${method.name}' must wrap query execution and conversion in RepositoryExceptionTranslator.execute")
            }
            methods.each { scope ->
                scope.invocations.eachWithIndex { call, index ->
                    String operation = call.selector.tokenize('.').last()
                    if (isRepositoryCall(call, repositories) && deferredWrite(operation)) {
                        String receiver = call.selector.substring(0, call.selector.lastIndexOf('.'))
                        if (!scope.invocations.drop(index + 1).any { later -> later.selector == receiver + '.flush' }
                                && !methods.any { other -> !other.is(scope) && other.invocations.any { it.selector == receiver + '.flush' } }) {
                            violations.add("${path} persistence adapter write '${operation}' must flush before leaving its exception translation boundary")
                        }
                    }
                }
                scope.catches.each { caught ->
                    def helpers = JavaMethodGraph.callees(type, caught.invocations)
                            .collectMany { JavaMethodGraph.reachable(type, it) }.unique()
                    def catchCalls = caught.invocations + helpers.collectMany { it.invocations }
                    if (catchCalls.any { isRepositoryCall(it, repositories) && isWrite(it.selector.tokenize('.').last()) }) {
                        violations.add("${path} persistence adapter must not retry a write in the failed transaction's catch block")
                    }
                    if (!caught.parameterType.contains('DataIntegrityViolationException')) return
                    def scopes = [[body: caught.codeBody, causes: [caught.parameterName]]]
                    scopes.addAll(helpers.collect { helper ->
                        [body: helper.codeBody, causes: helper.parameterTypes.withIndex().findAll { entry ->
                            entry[0].endsWith('Exception') || entry[0].endsWith('Throwable')
                        }.collect { entry -> helper.parameterNames[entry[1]] }]
                    })
                    boolean businessConversion = scopes.any { scopeBody ->
                        (JavaLexicalScanner.codeOnly(scopeBody.body) =~ /\bnew\s+(?!(?:[\w]+\.)*RepositoryOperationException\b)[\w.]+Exception\s*\(/).find()
                    }
                    if (businessConversion && !catchCalls.any { call ->
                        translatorCall(call, 'isConstraintViolation', imports) && call.arguments.size() == 2
                                && constraintName(type, call) instanceof String && !constraintName(type, call).isBlank()
                    }) {
                        violations.add("${path} persistence adapter integrity conflict conversion must check the exact named constraint")
                    }
                    scopes.each { scopeBody ->
                        def constructors = JavaLexicalScanner.codeOnly(scopeBody.body) =~ /\bnew\s+[\w.]+Exception\s*\(([^)]*)\)/
                        while (constructors.find()) {
                            String arguments = constructors.group(1)
                            if (!scopeBody.causes.any { cause -> (arguments =~ /\b${Pattern.quote(cause)}\b/).find() }) {
                                violations.add("${path} persistence adapter conflict conversion must preserve its original exception as cause")
                            }
                        }
                    }
                }
            }
        }
        return violations.unique()
    }

    private static Set<String> repositoryNames(JavaSourceAstInspector.TypeModel type, List methods) {
        Set<String> names = type.fields.findAll { it.type.replaceAll(/<.*>/, '').endsWith('Repository') }.collect { it.name }.toSet()
        methods.each { method ->
            method.parameterTypes.eachWithIndex { parameter, index ->
                if (parameter.replaceAll(/<.*>/, '').endsWith('Repository')) names.add(method.parameterNames[index])
            }
            String code = JavaLexicalScanner.codeOnly(method.codeBody)
            def declarations = code =~ /\b[\w.]+Repository\s+([\w]+)\s*=/
            while (declarations.find()) names.add(declarations.group(1))
            boolean changed = true
            while (changed) {
                changed = false
                def aliases = code =~ /\b([\w]+)\s*=\s*([\w]+)\s*;/
                while (aliases.find()) {
                    if (names.contains(aliases.group(2))) changed = names.add(aliases.group(1)) || changed
                }
            }
        }
        return names
    }

    private static boolean isRepositoryCall(JavaSourceAstInspector.InvocationModel call, Set<String> names) {
        String selector = call.selector.replaceFirst(/^this\./, '')
        names.any { selector.startsWith(it + '.') }
    }

    private static boolean deferredWrite(String name) {
        isWrite(name) && !(name in ['saveAndFlush', 'saveAllAndFlush', 'deleteAllInBatch', 'deleteAllByIdInBatch'])
    }

    private static boolean isWrite(String name) {
        name.startsWith('save') || name.startsWith('delete')
    }

    private static boolean translatorCall(JavaSourceAstInspector.InvocationModel call, String name, Set<String> imports) {
        call.selector.endsWith('.global.persistence.RepositoryExceptionTranslator.' + name)
                || (call.selector == name && imports.any { it.endsWith('.global.persistence.RepositoryExceptionTranslator.' + name) })
                || (call.selector == 'RepositoryExceptionTranslator.' + name
                && imports.any { it.endsWith('.global.persistence.RepositoryExceptionTranslator') })
    }

    private static Object constraintName(JavaSourceAstInspector.TypeModel type, JavaSourceAstInspector.InvocationModel call) {
        if (call.literalArguments[1] != null) return call.literalArguments[1]
        return type.fields.find { it.name == call.arguments[1] && it.staticField && it.finalField }?.literalInitializer
    }
}
