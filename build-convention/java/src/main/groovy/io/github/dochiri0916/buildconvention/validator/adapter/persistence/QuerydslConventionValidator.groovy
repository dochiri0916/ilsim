package io.github.dochiri0916.buildconvention.validator.adapter.persistence

import io.github.dochiri0916.buildconvention.context.BoundedContexts
import io.github.dochiri0916.buildconvention.source.java.JavaLexicalScanner
import io.github.dochiri0916.buildconvention.source.java.JavaSourceAstInspector
import io.github.dochiri0916.buildconvention.validator.quality.ChangedCodeCoverageValidator
import java.beans.Introspector
import java.util.regex.Pattern
import org.gradle.api.Project

final class QuerydslConventionValidator {
    static List<String> validateTrackedSources(Project project) {
        File ancestor = project.projectDir.canonicalFile
        while (ancestor != null && !new File(ancestor, '.git').exists()) ancestor = ancestor.parentFile
        if (ancestor == null) return []
        def tracked
        try {
            tracked = ChangedCodeCoverageValidator.runProcess(
                    ['git', '-C', project.projectDir.absolutePath, 'ls-files', '-z', '--', '*.java'], project.projectDir)
        } catch (IOException exception) {
            return ["Querydsl tracked Q source audit could not inspect Git: ${exception.message}"]
        }
        if (tracked.exitValue != 0) return ["Querydsl tracked Q source audit could not inspect Git index"]
        List<String> violations = []
        tracked.stdout.split('\u0000').findAll { !it.empty }.each { relative ->
            def blob = ChangedCodeCoverageValidator.runProcess(
                    ['git', '-C', project.projectDir.absolutePath, 'show', ':./' + relative], project.projectDir)
            if (blob.exitValue != 0) {
                violations.add("${relative} Querydsl tracked Q source audit could not read Git index content")
                return
            }
            def inspected = JavaSourceAstInspector.inspectSource(relative, blob.stdout)
            if (!inspected.valid && !inspected.errors.contains('no top-level type declaration was found')
                    && ((relative.tokenize('/').last() ==~ /Q[A-Z].*\.java/)
                    || JavaLexicalScanner.codeOnly(blob.stdout).contains('com.querydsl.'))) {
                violations.add("${relative} Querydsl tracked Q source audit cannot parse indexed source")
            }
            if (inspected.valid && inspected.types.any { containsQuerydslType(it, inspected.imports) }) {
                violations.add("${relative} Querydsl Q types must be annotation-processor generated and must not be tracked in Git")
            }
        }
        return violations
    }

    private static boolean containsQuerydslType(JavaSourceAstInspector.TypeModel type, Set<String> imports) {
        return isQuerydslType(type, imports) || type.nestedTypes.any { containsQuerydslType(it, imports) }
    }

    private static boolean isQuerydslType(JavaSourceAstInspector.TypeModel type, Set<String> imports) {
        if (!(type.simpleName ==~ /Q[A-Z].*/)) return false
        String superclass = type.superType?.replaceFirst(/<.*>/, '')
        if (superclass == null) return false
        String qualified = resolved(superclass, imports)
        return qualified.startsWith('com.querydsl.') || (imports.contains('com.querydsl.core.types.dsl.*')
                && superclass in ['EntityPathBase', 'BeanPath', 'EnumPath', 'SimplePath'])
                || (imports.contains('com.querydsl.core.types.*') && superclass == 'ConstructorExpression')
    }

    static List<String> validate(String path, String packageName,
                                 JavaSourceAstInspector.TypeModel type, Set<String> imports,
                                 Map<String, JavaSourceAstInspector.TypeModel> knownTypes = [:]) {
        List<String> violations = []
        boolean persistence = packageName.contains('.adapter.out.persistence')
        boolean queryRepository = type.simpleName.endsWith('QueryRepository')
        List<String> codeBodies = type.fields.collect { JavaLexicalScanner.codeOnly(it.initializer ?: '') } +
                type.methods.collect { JavaLexicalScanner.codeOnly(it.codeBody) }
        codeBodies.each { body ->
            def defaults = body =~ /\b((?:[a-z]\w*\.)*Q([A-Z]\w*))\.([a-z]\w*)\b/
            while (defaults.find()) {
                if (defaults.group(3) == Introspector.decapitalize(defaults.group(2))
                        && isQReference(defaults.group(1), packageName, imports, knownTypes)) {
                    violations.add("${path} Querydsl default Q instance '${defaults.group(0)}' must use a static import")
                }
            }
        }

        if (queryRepository && (!persistence || type.kind != 'CLASS' || type.modifiers.contains('ABSTRACT')
                || !type.annotations.any { resolved(it.qualifiedName, imports) == 'org.springframework.stereotype.Component' })) {
            violations.add("${path} Querydsl QueryRepository must be a concrete @Component in adapter.out.persistence")
        }
        if (type.simpleName.endsWith('Row') && persistence
                && (type.kind != 'RECORD' || !type.modifiers.contains('PUBLIC'))) {
            violations.add("${path} Querydsl internal Row must be a public record in adapter.out.persistence")
        }
        boolean projectionAnnotation = (type.annotations + type.methods.collectMany { it.annotations }).any {
            resolved(it.qualifiedName, imports) == 'com.querydsl.core.annotations.QueryProjection'
        }
        if (projectionAnnotation && (!persistence || type.kind != 'RECORD'
                || !type.simpleName.endsWith('Row') || !type.modifiers.contains('PUBLIC'))) {
            violations.add("${path} Querydsl @QueryProjection is only allowed on an internal public Row record")
        }
        if (persistence) {
            (imports + type.qualifiedTypeReferences).each { reference ->
                def declaration = knownTypes[reference]
                boolean storageType = (reference.contains('.adapter.out.persistence.')
                        && (reference.endsWith('Entity') || reference.endsWith('Repository') || declaration?.annotation('Entity') != null))
                        || (reference.contains('.application.port.out.') && reference.endsWith('RepositoryPort'))
                if (storageType && BoundedContexts.contextOf(reference) != BoundedContexts.contextOf(packageName)) {
                    violations.add("${path} Querydsl persistence code must not query another bounded context's Entity or repository '${reference}'")
                }
            }
        }
        boolean factoryConfiguration = type.qualifiedName.endsWith('.global.configuration.QuerydslConfiguration')
        List<String> initializers = type.fields.collect { JavaLexicalScanner.codeOnly(it.initializer ?: '') }
        if (!factoryConfiguration && initializers.any { (it =~ /\bnew\s+(?:[\w]+\.)*JPAQueryFactory\s*\(/).find() }) {
            violations.add("${path} Querydsl JPAQueryFactory must be injected from global.configuration.QuerydslConfiguration")
        }
        if (queryRepository && type.fields.any { it.type ==~ /(?:[\w]+\.)*JPAQuery(?:<.*>)?/ }) {
            violations.add("${path} Querydsl QueryRepository must create a fresh query per invocation instead of retaining mutable JPAQuery state")
        }
        if (factoryConfiguration) {
            Set<String> managers = type.fields.findAll { it.initializer == null && it.finalField
                    && it.modifiers.contains('PRIVATE')
                    && resolved(it.type, imports) in ['jakarta.persistence.EntityManager', 'javax.persistence.EntityManager'] }
                    .collect { it.name }.toSet()
            type.methods.findAll { it.constructor || it.annotations.any { resolved(it.qualifiedName, imports) == 'org.springframework.context.annotation.Bean' } }
                    .each { method -> method.parameterTypes.eachWithIndex { parameter, index ->
                if (resolved(parameter, imports) in ['jakarta.persistence.EntityManager', 'javax.persistence.EntityManager']) managers.add(method.parameterNames[index])
            } }
            boolean factoryBean = type.methods.any { method -> method.returnType != null && resolved(method.returnType, imports) == 'com.querydsl.jpa.impl.JPAQueryFactory'
                    && method.annotations.any { resolved(it.qualifiedName, imports) == 'org.springframework.context.annotation.Bean' } }
            boolean configuration = type.annotations.any { resolved(it.qualifiedName, imports) == 'org.springframework.context.annotation.Configuration' }
            List<String> bodies = initializers + type.methods.collect { JavaLexicalScanner.codeOnly(it.codeBody) }
            boolean managedFactory = bodies.any { body ->
                def construction = body =~ /\bnew\s+(?:[\w]+\.)*JPAQueryFactory\s*\(([^;]+?)\)/
                construction.find() && managers.any { manager -> (construction.group(1) =~ /\b${Pattern.quote(manager)}\b/).find() }
            }
            if (!configuration || !factoryBean || !managedFactory
                    || initializers.any { (it =~ /\.createEntityManager\s*\(/).find() }
                    || type.methods.any { method -> method.invocations.any { it.selector.endsWith('.createEntityManager') } }) {
                violations.add("${path} QuerydslConfiguration must declare a JPAQueryFactory @Bean using Spring's injected EntityManager")
            }
        }
        type.methods.each { method ->
            def calls = method.invocations
            if (calls.any { it.selector.tokenize('.').last() in ['fetchResults', 'fetchCount'] }) {
                violations.add("${path} Querydsl must use separate list/count queries instead of fetchResults/fetchCount")
            }
            calls.findAll { it.selector.endsWith('.constructor')
                    && resolved(it.selector.substring(0, it.selector.lastIndexOf('.')), imports) == 'com.querydsl.core.types.Projections'
            }.each { call ->
                String target = call.arguments.empty ? '' : call.arguments.first().replaceFirst(/\.class$/, '')
                String qualified = target.contains('.') ? target : (imports.find { it.endsWith('.' + target) } ?: packageName + '.' + target)
                def projection = knownTypes[qualified]
                if (call.arguments.empty || !(call.arguments.first() ==~ /[\w.]+Row\.class/)
                        || (!knownTypes.empty && (projection == null || projection.kind != 'RECORD'
                        || !projection.modifiers.contains('PUBLIC') || !qualified.startsWith(packageName + '.')))) {
                    violations.add("${path} Querydsl constructor projection must target an internal public Row record")
                }
            }
            if (queryRepository && !method.constructor && method.modifiers.contains('PUBLIC')
                    && method.returnType?.contains('JPAQuery')) {
                violations.add("${path} Querydsl QueryRepository must execute queries instead of exposing mutable JPAQuery objects")
            }
            String body = JavaLexicalScanner.codeOnly(method.codeBody)
            if ((body =~ /\bnew\s+(?:[\w]+\.)*JPAQueryFactory\s*\(/).find()
                    && !type.qualifiedName.endsWith('.global.configuration.QuerydslConfiguration')) {
                violations.add("${path} Querydsl JPAQueryFactory must be injected from global.configuration.QuerydslConfiguration")
            }
            if (queryRepository && calls.any { it.selector.endsWith('.offset') || it.selector.endsWith('.limit') }) {
                if (!calls.findAll { it.selector.endsWith('.orderBy') }.any { order ->
                    order.arguments.any { argument -> uniqueOrder(argument, type, method, [] as Set, imports, knownTypes) }
                }) {
                    violations.add("${path} Querydsl pagination must declare an order including a unique key")
                }
                calls.findAll { it.selector.endsWith('.offset') && !it.arguments.empty }.each { offset ->
                    if (unsafeOffset(offset.arguments.first(), type, method, [] as Set)) {
                        violations.add("${path} Querydsl pagination offset must widen operands to long before multiplication")
                    }
                }
                if (calls.any { it.selector.endsWith('.fetchJoin') } && collectionJoin(type, method)) {
                    violations.add("${path} Querydsl pagination must not combine collection fetch joins; batch-load children after the page query")
                }
            }
        }
        type.nestedTypes.each { violations.addAll(validate(path, packageName, it, imports, knownTypes)) }
        return violations
    }

    private static boolean isQReference(String name, String packageName, Set<String> imports,
                                        Map<String, JavaSourceAstInspector.TypeModel> knownTypes) {
        String qualified = resolved(name, imports)
        if (!qualified.contains('.')) qualified = packageName + '.' + name
        def declared = knownTypes[qualified]
        if (declared != null) return isQuerydslType(declared, imports)
        String entityName = qualified.substring(0, qualified.lastIndexOf('.') + 1) + qualified.tokenize('.').last().substring(1)
        return knownTypes[entityName]?.annotation('Entity') != null
    }

    private static boolean uniqueOrder(String expression, JavaSourceAstInspector.TypeModel type,
                                       JavaSourceAstInspector.MethodModel method, Set<String> visited, Set<String> imports,
                                       Map<String, JavaSourceAstInspector.TypeModel> knownTypes) {
        String code = JavaLexicalScanner.codeOnly(expression).trim()
        if (!visited.add(code)) return false
        if (code ==~ /[\w.]+\.(?:id|publicId)\.(?:asc|desc)\(\)/) return true
        if (declaredUniqueOrder(code, method, type, imports, knownTypes)) return true
        def pathAlias = code =~ /^(\w+)\.(asc|desc)\(\)$/
        if (pathAlias.matches()) {
            def assignedPath = JavaLexicalScanner.codeOnly(method.codeBody) =~ /\b${Pattern.quote(pathAlias.group(1))}\s*=\s*([\w.]+);/
            if (assignedPath.find()) return uniqueOrder(assignedPath.group(1) + '.' + pathAlias.group(2) + '()', type, method, visited, imports, knownTypes)
        }

        if (code ==~ /\w+/) {
            String body = JavaLexicalScanner.codeOnly(method.codeBody)
            def assignment = body =~ /\b${Pattern.quote(code)}\s*=\s*([^;]+);/
            if (assignment.find()) return uniqueOrder(assignment.group(1), type, method, visited, imports, knownTypes)
            def field = type.fields.find { it.name == code }
            if (field?.initializer != null) return uniqueOrder(field.initializer, type, method, visited, imports, knownTypes)
        }
        def helper = code =~ /(?:this\.)?(\w+)\(\)/
        if (helper.matches()) {
            def target = type.methods.find { it.name == helper.group(1) && it.parameterTypes.empty }
            if (target != null) {
                def returned = JavaLexicalScanner.codeOnly(target.codeBody) =~ /\breturn\s+([^;]+);/
                boolean found = false
                while (returned.find()) {
                    found = true
                    if (!uniqueOrder(returned.group(1), type, target, new LinkedHashSet<>(visited), imports, knownTypes)) return false
                }
                return found
            }
        }
        return false
    }

    private static boolean declaredUniqueOrder(String code, JavaSourceAstInspector.MethodModel method,
                                               JavaSourceAstInspector.TypeModel owner, Set<String> imports,
                                               Map<String, JavaSourceAstInspector.TypeModel> knownTypes) {
        def path = code =~ /^(\w+)\.(\w+)\.(?:asc|desc)\(\)$/
        if (!path.matches()) return false
        String receiver = path.group(1)
        String property = path.group(2)
        String qType = owner.fields.find { it.name == receiver }?.type
        method.parameterNames.eachWithIndex { name, index ->
            if (name == receiver) qType = method.parameterTypes[index]
        }
        def local = JavaLexicalScanner.codeOnly(method.codeBody) =~ /\b(Q\w+)\s+${Pattern.quote(receiver)}\s*=/
        if (local.find()) qType = local.group(1)
        String staticPath = imports.find { it.endsWith('.' + receiver) && it.tokenize('.').size() > 1 }
        if (qType == null && staticPath != null) qType = staticPath.substring(0, staticPath.lastIndexOf('.'))
        if (qType == null) return false
        String qualifiedQ = resolved(qType, imports)
        String simpleEntity = qualifiedQ.tokenize('.').last().replaceFirst(/^Q/, '')
        def entity = knownTypes.values().find { it.simpleName == simpleEntity && it.qualifiedName.substring(0, it.qualifiedName.lastIndexOf('.')) == owner.qualifiedName.substring(0, owner.qualifiedName.lastIndexOf('.')) }
        def field = entity?.fields?.find { it.name == property }
        if (field == null) return false
        if (field.annotations.any { it.simpleName == 'Id' }) return true
        def column = field.annotation('Column')
        if (column?.arguments?.get('nullable') != 'false') return false
        if (column.arguments.get('unique') == 'true') return true
        String columnName = column?.arguments?.get('name') ?: property
        return entity.annotations.findAll { it.simpleName == 'Table' }.any { table ->
            table.nestedAnnotations.any { constraint -> constraint.simpleName == 'UniqueConstraint'
                    && constraint.arguments['columnNames']?.replaceAll(/[{}\s]/, '') == columnName }
        }
    }

    private static boolean unsafeOffset(String expression, JavaSourceAstInspector.TypeModel type,
                                        JavaSourceAstInspector.MethodModel method, Set<String> visited) {
        String code = JavaLexicalScanner.codeOnly(expression).trim()
        String key = method.name + ':' + code
        if (!visited.add(key)) return false
        if (code ==~ /\w+/) {
            def assignment = JavaLexicalScanner.codeOnly(method.codeBody) =~ /\b${Pattern.quote(code)}\s*=\s*([^;]+);/
            if (assignment.find()) return unsafeOffset(assignment.group(1), type, method, visited)
        }
        def call = method.invocations.find { invocation ->
            code == invocation.selector + '(' + invocation.arguments.join(', ') + ')'
                    || code.replaceAll(/\s/, '') == (invocation.selector + '(' + invocation.arguments.join(',') + ')').replaceAll(/\s/, '')
        }
        if (call != null) {
            def targets = io.github.dochiri0916.buildconvention.source.java.JavaMethodGraph.callees(type, [call])
            if (targets.any { target ->
                def returns = JavaLexicalScanner.codeOnly(target.codeBody) =~ /\breturn\s+([^;]+);/
                while (returns.find()) {
                    if (unsafeOffset(returns.group(1), type, target, new LinkedHashSet<>(visited))) return true
                }
                return false
            }) return true
        }
        return unsafeOffsetProduct(code, method)
    }

    private static boolean unsafeOffsetProduct(String expression, JavaSourceAstInspector.MethodModel method) {
        String code = JavaLexicalScanner.codeOnly(expression)
        if (!code.contains('*')) return false
        String withoutLateCast = code.replaceFirst(/^\s*\(long\)\s*\((.*\*.*)\)\s*$/, '$1')
        String firstProduct = withoutLateCast.split('\\*', 3).take(2).join('*')
        if ((firstProduct =~ /\(long\)\s*\w+/).find()
                || (firstProduct =~ /\b\d+[lL]\b/).find()) return false
        Set<String> longs = [] as Set
        method.parameterTypes.eachWithIndex { parameter, index ->
            if (parameter == 'long') longs.add(method.parameterNames[index])
        }
        def locals = JavaLexicalScanner.codeOnly(method.codeBody) =~ /\blong\s+(\w+)\s*=/
        while (locals.find()) longs.add(locals.group(1))
        return !longs.any { name -> (firstProduct =~ /\b${Pattern.quote(name)}\b/).find() }
    }

    private static boolean collectionJoin(JavaSourceAstInspector.TypeModel type,
                                          JavaSourceAstInspector.MethodModel method) {
        Set<String> collections = type.fields.findAll { it.type ==~ /.*(?:ListPath|SetPath|CollectionPath|CollectionExpression)<.*/ }
                .collect { it.name }.toSet()
        method.parameterTypes.eachWithIndex { parameter, index ->
            if (parameter ==~ /.*(?:ListPath|SetPath|CollectionPath|CollectionExpression)<.*/) collections.add(method.parameterNames[index])
        }
        def locals = JavaLexicalScanner.codeOnly(method.codeBody) =~ /\b(?:ListPath|SetPath|CollectionPath|CollectionExpression)<[^;=]+>\s+(\w+)\s*=/
        while (locals.find()) collections.add(locals.group(1))
        return method.invocations.any { call -> call.selector.tokenize('.').last() in ['join', 'leftJoin', 'innerJoin', 'rightJoin']
                && !call.arguments.empty && collections.contains(call.arguments.first()) }
    }

    private static String resolved(String name, Set<String> imports) {
        if (name.contains('.')) return name
        return imports.find { it.endsWith('.' + name) } ?: name
    }
}
