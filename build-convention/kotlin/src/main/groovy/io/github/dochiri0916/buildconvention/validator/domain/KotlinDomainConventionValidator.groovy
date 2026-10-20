package io.github.dochiri0916.buildconvention.validator.domain

import io.github.dochiri0916.buildconvention.catalog.TechnicalDependencyCatalog
import io.github.dochiri0916.buildconvention.context.BoundedContexts
import io.github.dochiri0916.buildconvention.extension.HexagonalConventionExtension
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinGuards
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinSourceAstInspector
import io.github.dochiri0916.buildconvention.validator.adapter.identifier.KotlinIdentifierBoundaryRules
import io.github.dochiri0916.buildconvention.validator.domain.aggregate.KotlinAggregateRules
import io.github.dochiri0916.buildconvention.validator.domain.collection.KotlinCollectionRules
import io.github.dochiri0916.buildconvention.validator.domain.vo.KotlinIdentifierRules
import org.gradle.api.Project
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression

final class KotlinDomainConventionValidator {
    private KotlinDomainConventionValidator() {
    }

    static Map analyze(Project project, List<File> sources, HexagonalConventionExtension convention) {
        KotlinSourceAstInspector.inspect(sources) { List<Map> inspections ->
            Map<String, KtClassOrObject> types = [:]
            List<String> violations = []
            List<Map> domain = []
            inspections.each { inspection ->
                if (!inspection.errors.isEmpty()) {
                    violations.add("[ARCH-SOURCE-SYNTAX] ${project.relativePath(inspection.file)} cannot be parsed as Kotlin: ${inspection.errors.join('; ')}")
                    return
                }
                KtFile ast = inspection.ast
                boolean inDomain = ast.packageFqName.asString().tokenize('.').contains(convention.domainPackageSegment)
                if (inDomain || inspection.file.toPath().iterator().any { it.toString() == convention.domainPackageSegment }) {
                    domain.add(inspection)
                    if (!inDomain) violations.add("[ARCH-PACKAGE-TOPOLOGY] ${project.relativePath(inspection.file)} domain source must declare a domain package")
                }
                KotlinSourceAstInspector.nodes(ast, KtClassOrObject).findAll { !(it instanceof KtEnumEntry) }.each { type ->
                    String name = KotlinSourceAstInspector.qualifiedName(type)
                    if (name != null && types.containsKey(name)) {
                        violations.add("[ARCH-SOURCE-SYNTAX] ${project.relativePath(inspection.file)} duplicate Kotlin type '${name}'")
                    }
                    if (name != null) types[name] = type
                }
            }
            Set<String> roots = convention.basePackage ? [convention.basePackage] as Set : [] as Set
            BoundedContexts.withRoots(roots) {
                KotlinIdentifierBoundaryRules.validate(project, inspections, types, violations)
                domain.each { inspection ->
                    KtFile file = inspection.ast
                    String path = project.relativePath(inspection.file)
                    validateDependencies(file, path, convention, violations)
                    file.declarations.findAll { it instanceof KtClassOrObject }.each { type ->
                        validateType(type, file, path, types, violations)
                    }
                }
            }
            return [files: sources.size(), domainFiles: domain.size(), violations: violations.unique()]
        }
    }

    private static void validateDependencies(KtFile file, String path, HexagonalConventionExtension convention,
                                             List<String> violations) {
        List<String> dependencies = file.importDirectives.collect { it.importedFqName?.asString() }.findAll()
        dependencies.addAll(KotlinSourceAstInspector.nodes(file, KtDotQualifiedExpression).collect { it.text }
                .findAll { it ==~ /[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+/ })
        String context = BoundedContexts.contextOf(file.packageFqName.asString())
        dependencies.unique().each { String dependency ->
            if (dependency.tokenize('.').any { it in ['application', 'adapter'] } ||
                    TechnicalDependencyCatalog.DOMAIN_FORBIDDEN_ARCHUNIT_PACKAGES.any { pattern ->
                        dependency.startsWith(pattern.replace('..', ''))
                    }) {
                violations.add("[ARCH-DOMAIN-DEPENDENCY] ${path} domain must not depend on '${dependency}'")
            }
            String other = BoundedContexts.contextOf(dependency)
            if (other != null && other != context && other != 'global') {
                violations.add("[ARCH-DOMAIN-DEPENDENCY] ${path} domain must not directly reference another context '${other}'")
            }
        }
    }

    private static void validateType(KtClassOrObject type, KtFile file, String path,
                                     Map<String, KtClassOrObject> types, List<String> violations) {
        String pkg = file.packageFqName.asString()
        if (type.name?.endsWith('Id') || type instanceof KtClass && type.primaryConstructorParameters.any { field ->
            KotlinSourceAstInspector.resolve(file, field.typeReference?.text) == 'java.util.UUID'
        } && (type.hasModifier(KtTokens.VALUE_KEYWORD) || pkg.endsWith('.domain.vo'))) {
            KotlinIdentifierRules.validate(type, file, path, violations)
            return
        }
        if (pkg.tokenize('.').contains('exception')) {
            validateException(type, file, path, types, violations)
            return
        }
        if (!(type instanceof KtClass)) {
            violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} domain models must be immutable classes, not objects")
            return
        }
        if (type.isEnum()) {
            if (!pkg.tokenize('.').contains('vo')) violations.add("[ARCH-PACKAGE-TOPOLOGY] ${path} domain state enums must live in domain.vo")
            if (type.declarations.any { it instanceof KtProperty && it.isVar() }) {
                violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} domain state must be final and immutable val properties")
            }
            validateThrows(type, file, path, types, violations)
            return
        }
        if (type.isInterface()) return
        List<KtParameter> fields = type.primaryConstructorParameters.findAll { it.hasValOrVar() }
        List<KtProperty> properties = type.declarations.findAll { it instanceof KtProperty }
        if (type.hasModifier(KtTokens.OPEN_KEYWORD) || type.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                fields.any { it.mutable } || properties.any { it.isVar() }) {
            violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} domain state must be final and immutable val properties")
        }
        boolean eventOrOutbox = pkg.tokenize('.').any { it in ['event', 'outbox'] }
        if (!eventOrOutbox) {
            if (type.isData()) violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} data class copy must not bypass private domain factories")
            if (type.primaryConstructor == null || !type.primaryConstructor.hasModifier(KtTokens.PRIVATE_KEYWORD) ||
                    type.secondaryConstructors.any { !it.hasModifier(KtTokens.PRIVATE_KEYWORD) }) {
                violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} domain models must declare only private constructors")
            }
            if (type.secondaryConstructors.any { !it.valueParameters.isEmpty() }) {
                violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} secondary domain construction requires a validated primary factory")
            }
            List<KtNamedFunction> factories = type.companionObjects.collectMany { companion ->
                companion.declarations.findAll { it instanceof KtNamedFunction &&
                        !it.hasModifier(KtTokens.PRIVATE_KEYWORD) && !it.hasModifier(KtTokens.INTERNAL_KEYWORD) }
            }
            boolean aggregate = fields.any { it.name == 'id' }
            List<String> allowed = aggregate ? ['create', 'register', 'restore'] : ['of', 'from', 'parse', 'generate', 'empty']
            if (!factories.any { it.name == (aggregate ? 'restore' : 'of') && returnsSelf(it, type) } ||
                    aggregate && !factories.any { it.name in ['create', 'register'] && returnsSelf(it, type) }) {
                violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} domain must expose ${aggregate ? 'create/register and restore' : 'of'} companion factories")
            }
            factories.findAll { function ->
                returnsSelf(function, type) && !(function.name in allowed)
            }.each { function ->
                violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} unsupported domain factory '${function.name}'")
            }
            if (type.hasModifier(KtTokens.VALUE_KEYWORD)) {
                if (fields.size() != 1 || !type.annotationEntries.any { annotation ->
                    KotlinSourceAstInspector.resolve(file, annotation.typeReference?.text) == 'kotlin.jvm.JvmInline'
                }) violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} value class must wrap exactly one val and declare @JvmInline")
                if (!pkg.tokenize('.').contains('vo')) violations.add("[ARCH-PACKAGE-TOPOLOGY] ${path} value classes must live in domain.vo")
                validateInlineValue(type, fields, file, path, types, violations)
            } else {
                validateEquality(type, fields, aggregate, path, violations)
                KotlinCollectionRules.validate(type, fields, file, path, types, violations)
                if (aggregate) KotlinAggregateRules.validate(type, fields, file, path, types, violations)
            }
            boolean eventCarrier = !aggregate && (pkg.tokenize('.').contains('event') || changeResult(type, fields, file, types))
            fields.findAll { field -> referencesDomainEvent(field, file) }.each { field ->
                if (aggregate) {
                    violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} aggregates must not retain domain events in '${field.name}'")
                } else if (!eventCarrier) {
                    violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} domain events may be carried only by domain.event types and {Aggregate}Change results, not '${field.name}'")
                }
            }
        }
        validateThrows(type, file, path, types, violations)
        type.declarations.findAll { it instanceof KtClassOrObject && !(it instanceof KtEnumEntry) && !it.isCompanion() }.each { nested ->
            validateType(nested, file, path, types, violations)
        }
    }

    private static void validateInlineValue(KtClass type, List<KtParameter> fields, KtFile file, String path,
                                            Map<String, KtClassOrObject> types, List<String> violations) {
        if (fields.size() != 1) return
        KtParameter field = fields.first()
        def blocks = type.getAnonymousInitializers().collect { it.body }
        def guards = KotlinGuards.guards(blocks)
        Closure<Boolean> domainException = { thrown -> isDomainException(thrown, file, types) }
        boolean guarded = guards.any { guard ->
            KotlinGuards.references(guard.condition).contains(field.name) &&
                    (KotlinGuards.throwsOnBranch(guard.getThen(), domainException) ||
                            KotlinGuards.throwsOnBranch(guard.getElse(), domainException))
        }
        if (!guarded) violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} value class must validate '${field.name}' in init")
        if (type.name.endsWith('Quantity') || type.name.endsWith('Price')) {
            boolean quantity = type.name.endsWith('Quantity')
            List<Long> invalid = [Long.MIN_VALUE, -2147483648L, -1024L, -2L, -1L]
            if (quantity) invalid.add(0L)
            if (!invalid.every { value -> KotlinGuards.rejects(guards, [(field.name): value], domainException) } ||
                    KotlinGuards.rejects(guards, [(field.name): quantity ? 1L : 0L], domainException)) {
                violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} ${quantity ? 'quantity must be positive' : 'price must be non-negative'}")
            }
        }
    }

    private static boolean referencesDomainEvent(KtParameter field, KtFile file) {
        KotlinSourceAstInspector.nodes(field.typeReference, KtUserType).any { KtUserType reference ->
            KotlinSourceAstInspector.resolve(file, reference.text).contains('.domain.event.')
        }
    }

    private static boolean changeResult(KtClass type, List<KtParameter> fields, KtFile file, Map<String, KtClassOrObject> types) {
        if (!type.name?.endsWith('Change')) return false
        String aggregateName = type.name.substring(0, type.name.length() - 'Change'.length())
        return fields.any { KtParameter field ->
            def fieldType = types[KotlinSourceAstInspector.resolve(file, field.typeReference?.text)]
            fieldType instanceof KtClass && fieldType.name == aggregateName &&
                    fieldType.primaryConstructorParameters.any { it.name == 'id' }
        }
    }

    private static void validateEquality(KtClass type, List<KtParameter> fields, boolean aggregate,
                                         String path, List<String> violations) {
        Set<String> expected = aggregate ? ['id'] as Set : fields.collect { it.name }.toSet()
        ['equals', 'hashCode'].each { String name ->
            def method = type.declarations.find { it instanceof KtNamedFunction && it.name == name }
            def expression = method?.bodyExpression
            if (method?.bodyBlockExpression != null) {
                def statements = method.bodyBlockExpression.statements
                def last = statements.isEmpty() ? null : statements.last()
                expression = last instanceof KtReturnExpression ? last.returnedExpression : null
            }
            Set<String> used = KotlinGuards.references(expression).intersect(fields.collect { it.name }.toSet())
            boolean signature = method != null && method.hasModifier(KtTokens.OVERRIDE_KEYWORD) &&
                    (name == 'equals' ? method.valueParameters.size() == 1 &&
                            method.valueParameters.first().typeReference?.text == 'Any?' : method.valueParameters.isEmpty())
            if (!signature || used != expected) {
                violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} ${name} must use ${aggregate ? 'id only' : 'all primary component values'}")
            }
            if (name == 'equals' && signature && used == expected) {
                Map<String, Object> values = expected.collectEntries { [(it): 1L] }
                String other = method.valueParameters.first().name
                Map same = values + [(other): values]
                boolean faithful = KotlinGuards.evaluate(expression, same) == true && expected.every { field ->
                    Map changed = values + [(field): 2L]
                    KotlinGuards.evaluate(expression, values + [(other): changed]) == false
                }
                if (!faithful) violations.add("[ARCH-DOMAIN-AGGREGATE] ${path} equals must compare the actual identity or component values")
            }
        }
    }

    private static void validateThrows(KtClassOrObject type, KtFile file, String path,
                                       Map<String, KtClassOrObject> types, List<String> violations) {
        KotlinSourceAstInspector.nodes(type, KtThrowExpression).each { thrown ->
            if (!isDomainException(thrown, file, types)) {
                violations.add("[ARCH-EXCEPTION-CONTRACT] ${path} domain failures must throw the context's domain exception")
            }
        }
    }

    private static void validateException(KtClassOrObject type, KtFile file, String path,
                                          Map<String, KtClassOrObject> types, List<String> violations) {
        boolean base = type.name?.endsWith('DomainException')
        if (base) {
            if (!type.hasModifier(KtTokens.SEALED_KEYWORD) || !type.superTypeListEntries.any { entry ->
                entry.typeReference?.text == 'RuntimeException' || entry.typeReference?.text == 'java.lang.RuntimeException'
            } || type.primaryConstructor?.hasModifier(KtTokens.PUBLIC_KEYWORD)) {
                violations.add("[ARCH-EXCEPTION-CONTRACT] ${path} domain exception base must be sealed with a protected constructor and extend RuntimeException")
            }
        } else if (type.hasModifier(KtTokens.OPEN_KEYWORD) || type.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                !type.superTypeListEntries.any { entry ->
                    String parent = KotlinSourceAstInspector.resolve(file, entry.typeReference?.text)
                    types[parent]?.hasModifier(KtTokens.SEALED_KEYWORD) && parent.endsWith('DomainException')
                }) {
            violations.add("[ARCH-EXCEPTION-CONTRACT] ${path} domain exception subclasses must be final and extend a sealed domain exception base")
        }
    }

    static boolean isDomainException(KtThrowExpression thrown, KtFile file, Map<String, KtClassOrObject> types) {
        def expression = KotlinGuards.unwrap(thrown.thrownExpression)
        if (!(expression instanceof KtCallExpression)) return false
        String name = KotlinSourceAstInspector.resolve(file, expression.calleeExpression?.text)
        def type = types[name]
        String context = BoundedContexts.contextOf(file.packageFqName.asString())
        return type != null && BoundedContexts.contextOf(name) in [context, 'global'] &&
                type.superTypeListEntries.any { entry ->
                    String parent = KotlinSourceAstInspector.resolve(type.containingKtFile, entry.typeReference?.text)
                    types[parent]?.hasModifier(KtTokens.SEALED_KEYWORD) && parent.endsWith('DomainException')
                }
    }

    static boolean returnsSelf(KtNamedFunction function, KtClass type) {
        function.typeReference?.text == type.name || KotlinSourceAstInspector.nodes(function.bodyExpression, KtCallExpression)
                .any { it.calleeExpression?.text == type.name }
    }
}
