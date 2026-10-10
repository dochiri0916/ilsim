package io.github.dochiri0916.buildconvention.validator.domain.aggregate

import io.github.dochiri0916.buildconvention.source.kotlin.KotlinGuards
import io.github.dochiri0916.buildconvention.source.kotlin.KotlinSourceAstInspector
import io.github.dochiri0916.buildconvention.validator.domain.KotlinDomainConventionValidator
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtReturnExpression

final class KotlinAggregateRules {
    private KotlinAggregateRules() {
    }

    static void validate(KtClass type, List<KtParameter> fields, KtFile file, String path,
                         Map<String, KtClassOrObject> types, List<String> violations) {
        String prefix = "[ARCH-DOMAIN-AGGREGATE] ${path}"
        int idIndex = fields.findIndexOf { it.name == 'id' }
        if (!fields[idIndex].typeReference?.text?.endsWith('Id')) violations.add("${prefix} aggregate id must use an identifier VO")
        List<KtNamedFunction> methods = type.declarations.findAll { it instanceof KtNamedFunction }
        List<KtNamedFunction> restores = type.companionObjects.collectMany { companion ->
            companion.declarations.findAll { it instanceof KtNamedFunction && it.name == 'restore' }
        }
        (methods + restores).each { method ->
            List<KtCallExpression> calls = KotlinSourceAstInspector.nodes(method.bodyExpression, KtCallExpression)
            if (method.name == 'restore' && calls.any { it.calleeExpression?.text in ['generate', 'randomUUID', 'now'] }) {
                violations.add("${prefix} restore must preserve the supplied id and state without generation or clock side effects")
            }
            calls.findAll { it.calleeExpression?.text == type.name }.each { constructor ->
                def arguments = constructor.valueArguments
                def id = arguments.find { it.argumentName?.asName?.asString() == 'id' } ?: (arguments.size() > idIndex ? arguments[idIndex] : null)
                if (!(id?.argumentExpression?.text in ['id', 'this.id'])) violations.add("${prefix} aggregate changes must preserve id")
                if (method.name == 'restore' && fields.any { field ->
                    def argument = argumentFor(arguments, field, fields.indexOf(field))
                    argument?.argumentExpression?.text != field.name
                }) violations.add("${prefix} restore must preserve every supplied component")
                if (method.name != 'restore') {
                    fields.eachWithIndex { field, index ->
                        def stateType = types[KotlinSourceAstInspector.resolve(file, field.typeReference?.text)]
                        def state = argumentFor(arguments, field, index)
                        if (!(stateType instanceof KtClass) || !stateType.isEnum() || state == null ||
                                state.argumentExpression?.text in [field.name, "this.${field.name}"]) return
                        def guards = method.bodyBlockExpression == null ? [] : KotlinGuards.guards([method.bodyBlockExpression])
                        Closure<Boolean> domainException = { thrown -> KotlinDomainConventionValidator.isDomainException(thrown, file, types) }
                        boolean certified = false
                        guards.each { guard ->
                            KotlinSourceAstInspector.nodes(guard.condition, KtDotQualifiedExpression).findAll { expression ->
                                expression.receiverExpression?.text == field.name && expression.selectorExpression instanceof KtCallExpression
                            }.each { expression ->
                                String policyName = expression.selectorExpression.calleeExpression?.text
                                def policy = stateType.declarations.find { it instanceof KtNamedFunction && it.name == policyName }
                                if (policy != null && !meaningfulPolicy(stateType, policy)) {
                                    violations.add("${prefix} enum transition policy must allow and reject transitions; constants cannot bypass it")
                                }
                                def targets = expression.selectorExpression.valueArguments
                                if (policy != null && targets.size() == 1 &&
                                        targets.first().argumentExpression?.text == state.argumentExpression?.text &&
                                        !KotlinGuards.rejects([guard], [(field.name): [(policyName): true]], domainException) &&
                                        KotlinGuards.rejects([guard], [(field.name): [(policyName): false]], domainException)) certified = true
                            }
                        }
                        if (!certified) violations.add("${prefix} enum state changes must be guarded by a domain exception that rejects disallowed transitions")
                    }
                }
            }
        }
    }

    private static def argumentFor(def arguments, KtParameter field, int index) {
        arguments.find { it.argumentName?.asName?.asString() == field.name } ?:
                (arguments.size() > index ? arguments[index] : null)
    }

    private static boolean meaningfulPolicy(KtClass stateType, KtNamedFunction policy) {
        if (policy.valueParameters.size() != 1) return false
        def expression = policy.bodyExpression
        if (policy.bodyBlockExpression != null) {
            def statements = policy.bodyBlockExpression.statements
            def returned = statements.isEmpty() ? null : statements.last()
            expression = returned instanceof KtReturnExpression ? returned.returnedExpression : null
        }
        List<String> states = stateType.declarations.findAll { it instanceof KtEnumEntry }.collect { it.name }
        List<Object> results = states.collectMany { String current ->
            states.collect { String target ->
                Map<String, Object> bindings = states.collectEntries { [(it): it] }
                bindings['this'] = current
                bindings[policy.valueParameters.first().name] = target
                KotlinGuards.evaluate(expression, bindings)
            }
        }
        return !results.contains(null) && results.contains(true) && results.contains(false)
    }
}
