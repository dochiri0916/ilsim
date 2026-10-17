package io.github.dochiri0916.buildconvention.source.kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

/** A deliberately bounded evaluator: unknown conditions cannot certify an invariant. */
final class KotlinGuards {
    private KotlinGuards() {
    }

    static List<KtIfExpression> guards(Collection<KtBlockExpression> blocks) {
        blocks.collectMany { block -> block.statements.findAll { it instanceof KtIfExpression } }
    }

    static KtExpression unwrap(KtExpression expression) {
        while (expression instanceof KtParenthesizedExpression) expression = expression.expression
        return expression
    }

    static boolean throwsOnBranch(KtExpression branch, Closure<Boolean> domainException) {
        branch = unwrap(branch)
        if (branch instanceof KtThrowExpression) return domainException.call(branch)
        if (!(branch instanceof KtBlockExpression) || branch.statements.isEmpty()) return false
        List<KtExpression> statements = branch.statements
        if (statements.dropRight(1).any { statement ->
            !KotlinSourceAstInspector.nodes(statement, KtReturnExpression).isEmpty()
        }) return false
        return throwsOnBranch(statements.last(), domainException)
    }

    static Set<String> references(PsiElement node) {
        KotlinSourceAstInspector.nodes(node, KtNameReferenceExpression)
                .collect { reference -> reference.referencedName }.toSet()
    }

    static boolean rejects(Collection<KtIfExpression> guards, Map<String, Object> bindings,
                           Closure<Boolean> domainException) {
        guards.any { guard ->
            Object condition = evaluate(guard.condition, bindings)
            condition == Boolean.TRUE && throwsOnBranch(guard.getThen(), domainException) ||
                    condition == Boolean.FALSE && throwsOnBranch(guard.getElse(), domainException)
        }
    }

    static boolean validatesUuid(Collection<KtIfExpression> guards, String field,
                                Closure<Boolean> domainException) {
        if (rejects(guards, [(field): [version: 7, variant: 2]], domainException)) return false
        (0..15).every { int version ->
            (0..7).every { int variant ->
                version == 7 && variant == 2 ||
                        rejects(guards, [(field): [version: version, variant: variant]], domainException)
            }
        }
    }

    static Object evaluate(KtExpression expression, Map<String, Object> bindings) {
        expression = unwrap(expression)
        if (expression instanceof KtConstantExpression) {
            String literal = expression.text.replace('_', '')
            if (literal in ['true', 'false']) return Boolean.valueOf(literal)
            if (literal == 'null') return null
            try {
                return Long.decode(literal.replaceFirst(/[Ll]$/, ''))
            } catch (NumberFormatException ignored) {
                return null
            }
        }
        if (expression instanceof KtThisExpression) return bindings['this']
        if (expression instanceof KtNameReferenceExpression) return bindings[expression.referencedName]
        if (expression instanceof KtUnaryExpression) {
            Object value = evaluate(expression.baseExpression, bindings)
            if (expression.operationReference.text == '!' && value instanceof Boolean) return !value
            if (expression.operationReference.text == '-' && value instanceof Number) return -value.longValue()
            return null
        }
        if (expression instanceof KtDotQualifiedExpression) {
            Object receiver = evaluate(expression.receiverExpression, bindings)
            def selector = expression.selectorExpression
            String name = selector instanceof KtCallExpression ? selector.calleeExpression?.text : selector?.text
            if (receiver instanceof Map && name in ['version', 'variant', 'add']) return receiver[name]
            if (receiver instanceof Map && name == 'isEmpty') return receiver.empty
            if (receiver instanceof Map && name == 'isNotEmpty') return receiver.empty == null ? null : !receiver.empty
            if (receiver instanceof Map && receiver.containsKey(name)) return receiver[name]
            return null
        }
        if (expression instanceof KtWhenExpression) return evaluateWhen(expression, bindings)
        if (expression instanceof KtBinaryExpression) {
            Object left = evaluate(expression.left, bindings)
            Object right = evaluate(expression.right, bindings)
            if (left == null || right == null) return null
            switch (expression.operationReference.text) {
                case '&&': return left instanceof Boolean && right instanceof Boolean ? left && right : null
                case '||': return left instanceof Boolean && right instanceof Boolean ? left || right : null
                case '==': return left == right
                case '!=': return left != right
                case '<': return left instanceof Number && right instanceof Number ? left < right : null
                case '<=': return left instanceof Number && right instanceof Number ? left <= right : null
                case '>': return left instanceof Number && right instanceof Number ? left > right : null
                case '>=': return left instanceof Number && right instanceof Number ? left >= right : null
                default: return null
            }
        }
        return null
    }

    /** Branches are tried in order; an unknown subject, condition or branch result makes the whole when unknown. */
    private static Object evaluateWhen(KtWhenExpression expression, Map<String, Object> bindings) {
        boolean hasSubject = expression.subjectExpression != null
        Object subject = hasSubject ? evaluate(expression.subjectExpression, bindings) : null
        if (hasSubject && subject == null) return null
        for (def entry : expression.entries) {
            if (entry.isElse()) return evaluate(entry.expression, bindings)
            for (def condition : entry.conditions) {
                if (!(condition instanceof KtWhenConditionWithExpression)) return null
                Object value = evaluate(condition.expression, bindings)
                if (value == null || !hasSubject && !(value instanceof Boolean)) return null
                if (hasSubject ? value == subject : value) return evaluate(entry.expression, bindings)
            }
        }
        return null
    }
}
