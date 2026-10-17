package io.github.dochiri0916.buildconvention.validator.domain.collection

import io.github.dochiri0916.buildconvention.source.kotlin.KotlinGuards
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinSourceAstInspector
import io.github.dochiri0916.buildconvention.validator.domain.KotlinDomainConventionValidator
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/** First-class collections own an immutable snapshot; derived scalar caches are not extra component values. */
final class KotlinCollectionRules {
    private KotlinCollectionRules() {
    }

    static void validate(KtClass type, List<KtParameter> fields, KtFile file, String path,
                         Map<String, KtClassOrObject> types, List<String> violations) {
        List<KtParameter> collections = fields.findAll { field ->
            field.typeReference?.text ==~ /(?:kotlin\.collections\.|java\.util\.)?(?:Mutable)?(?:List|Set|Map|Collection)<.+>/
        }
        if (collections.isEmpty()) return
        String prefix = "[ARCH-DOMAIN-AGGREGATE] ${path}"
        if (fields.size() != 1) violations.add("${prefix} first-class collection must own exactly one collection component")
        if (collections.any { it.typeReference.text.contains('Mutable') }) {
            violations.add("${prefix} first-class collection must not expose a mutable collection type")
        }
        List<KtCallExpression> calls = KotlinSourceAstInspector.nodes(type, KtCallExpression)
        List<KtCallExpression> constructors = calls.findAll { it.calleeExpression?.text == type.name }
        if (constructors.isEmpty() || constructors.any { call ->
            call.valueArguments.size() != 1 || !immutableCopy(call.valueArguments.first().argumentExpression, file)
        }) violations.add("${prefix} every collection construction must defensively copy into an immutable snapshot")
        collections.each { field ->
            def blocks = type.getAnonymousInitializers().collect { it.body }
            def guards = KotlinGuards.guards(blocks)
            Closure<Boolean> domainException = { thrown -> KotlinDomainConventionValidator.isDomainException(thrown, file, types) }
            boolean emptyFactory = type.companionObjects.any { companion -> companion.declarations.any { it.name == 'empty' } }
            if (!emptyFactory && (!KotlinGuards.rejects(guards, [(field.name): [empty: true]], domainException) ||
                    KotlinGuards.rejects(guards, [(field.name): [empty: false]], domainException))) {
                violations.add("${prefix} collection must reject empty input or expose an explicit empty factory")
            }
            def mutation = KotlinSourceAstInspector.nodes(type, KtDotQualifiedExpression).find { expression ->
                expression.receiverExpression?.text in [field.name, "this.${field.name}"] &&
                        expression.selectorExpression instanceof KtCallExpression &&
                        expression.selectorExpression.calleeExpression?.text in ['add', 'remove', 'clear', 'set', 'put', 'sort']
            }
            if (mutation != null) violations.add("${prefix} stored collection must not be mutated")
            String elementName = field.typeReference.text.replaceFirst(/^.*?</, '').replaceFirst(/>$/, '')
            def element = types[KotlinSourceAstInspector.resolve(file, elementName)]
            def key = element?.primaryConstructorParameters?.find { it.typeReference?.text?.endsWith('Id') }
            if (key != null) {
                List<KtProperty> sets = blocks.collectMany { KotlinSourceAstInspector.nodes(it, KtProperty) }
                        .findAll { it.initializer instanceof KtCallExpression && it.initializer.calleeExpression?.text == 'HashSet' }
                boolean unique = sets.any { set ->
                    blocks.any { block ->
                        block.statements.any { statement ->
                            if (!(statement instanceof KtDotQualifiedExpression) || statement.receiverExpression?.text != field.name ||
                                    !(statement.selectorExpression instanceof KtCallExpression) ||
                                    statement.selectorExpression.calleeExpression?.text != 'forEach') return false
                            def loop = statement.selectorExpression.lambdaArguments
                            List guardsInLoop = loop.size() == 1 ? KotlinGuards.guards([loop.first().lambdaExpression.bodyExpression]) : []
                            guardsInLoop.any { guard ->
                                KotlinGuards.references(guard.condition).contains(key.name) &&
                                        KotlinGuards.rejects([guard], [(set.name): [add: false]], domainException)
                            }
                        }
                    }
                }
                if (!unique) violations.add("${prefix} collection elements with an identifier must reject duplicate identifiers")
            }
        }
        calls.findAll { it.calleeExpression?.text == 'fold' }.each { fold ->
            List<KtBinaryExpression> arithmetic = KotlinSourceAstInspector.nodes(fold, KtBinaryExpression).findAll { expression ->
                expression.operationReference.text in ['+', '-']
            }
            boolean unchecked = arithmetic.any { !checkedValueOperator(fold, it, file, types) }
            boolean checked = KotlinSourceAstInspector.nodes(fold, KtDotQualifiedExpression).any { expression ->
                KotlinSourceAstInspector.resolve(file, expression.receiverExpression?.text) == 'java.lang.Math' &&
                        expression.selectorExpression instanceof KtCallExpression &&
                        expression.selectorExpression.calleeExpression?.text == 'addExact'
            }
            if (unchecked || arithmetic.isEmpty() && !checked) violations.add("${prefix} numeric collection totals must use checked addition")
        }
    }

    /**
     * A fold may use + or - only as the operator of a domain value type that checks overflow itself: the fold starts
     * from that type, the accumulator is the left operand, and the operator body calls Math.addExact/subtractExact.
     */
    private static boolean checkedValueOperator(KtCallExpression fold, KtBinaryExpression arithmetic, KtFile file,
                                                Map<String, KtClassOrObject> types) {
        def lambda = fold.lambdaArguments.isEmpty() ? null : fold.lambdaArguments.first().getLambdaExpression()
        String accumulator = lambda?.valueParameters?.isEmpty() == false ? lambda.valueParameters.first().name : null
        def initial = fold.valueArguments.isEmpty() ? null : KotlinGuards.unwrap(fold.valueArguments.first().argumentExpression)
        if (accumulator == null || !(initial instanceof KtDotQualifiedExpression)) return false
        def valueType = types[KotlinSourceAstInspector.resolve(file, initial.receiverExpression?.text)]
        if (valueType == null) return false
        def left = KotlinGuards.unwrap(arithmetic.left)
        boolean fromAccumulator = left instanceof KtNameReferenceExpression && left.referencedName == accumulator ||
                left instanceof KtBinaryExpression && left.operationReference.text in ['+', '-'] &&
                checkedValueOperator(fold, left, file, types)
        if (!fromAccumulator) return false
        boolean addition = arithmetic.operationReference.text == '+'
        return valueType.declarations.any { declaration ->
            declaration instanceof KtNamedFunction && declaration.hasModifier(KtTokens.OPERATOR_KEYWORD) &&
                    declaration.name == (addition ? 'plus' : 'minus') && declaration.valueParameters.size() == 1 &&
                    KotlinSourceAstInspector.nodes(declaration, KtDotQualifiedExpression).any { expression ->
                        KotlinSourceAstInspector.resolve(declaration.containingKtFile, expression.receiverExpression?.text) == 'java.lang.Math' &&
                                expression.selectorExpression instanceof KtCallExpression &&
                                expression.selectorExpression.calleeExpression?.text == (addition ? 'addExact' : 'subtractExact')
                    }
        }
    }

    private static boolean immutableCopy(def expression, KtFile file) {
        expression = KotlinGuards.unwrap(expression)
        if (!(expression instanceof KtDotQualifiedExpression) || !(expression.selectorExpression instanceof KtCallExpression)) return false
        def call = expression.selectorExpression
        return call.calleeExpression?.text == 'copyOf' &&
                KotlinSourceAstInspector.resolve(file, expression.receiverExpression?.text) in ['java.util.List', 'java.util.Set', 'java.util.Map'] &&
                call.valueArguments.size() == 1
    }
}
