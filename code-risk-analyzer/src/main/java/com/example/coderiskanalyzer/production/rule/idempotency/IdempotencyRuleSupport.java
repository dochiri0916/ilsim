package com.example.coderiskanalyzer.production.rule.idempotency;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector;
import com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectType;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

final class IdempotencyRuleSupport {

    static final Set<String> LISTENER_ANNOTATIONS = Set.of("KafkaListener", "RabbitListener", "JmsListener");
    private static final Set<String> RETRY_ANNOTATIONS = Set.of("Retryable", "Retry", "CircuitBreaker");
    private static final Set<String> BUSINESS_KEY_PARTS = Set.of(
            "eventid", "messageid", "businessid", "aggregateid", "orderid", "paymentid", "idempotencykey");

    private IdempotencyRuleSupport() {}

    static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    static boolean isListener(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> simpleName(annotation.getNameAsString())).anyMatch(LISTENER_ANNOTATIONS::contains);
    }

    static boolean isRetryable(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> simpleName(annotation.getNameAsString())).anyMatch(RETRY_ANNOTATIONS::contains)
                || method.findAll(MethodCallExpr.class).stream().anyMatch(call -> {
                    String receiver = call.getScope().map(Object::toString).orElse("").toLowerCase(Locale.ROOT);
                    return receiver.contains("retrytemplate") || receiver.contains("retry");
                });
    }

    static List<MethodCallExpr> effects(MethodDeclaration method, SideEffectDetector detector) {
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> detector.classify(call).isPresent()).toList();
    }

    static boolean hasDatabaseMutation(MethodDeclaration method, SideEffectDetector detector) {
        return effects(method, detector).stream().map(call -> detector.classify(call).orElse(null))
                .anyMatch(type -> type == SideEffectType.DATABASE_WRITE || type == SideEffectType.DATABASE_DELETE);
    }

    static boolean hasExternalMutation(MethodDeclaration method, SideEffectDetector detector) {
        return effects(method, detector).stream().anyMatch(call -> isExternalMutation(call, detector));
    }

    static boolean isExternalMutation(MethodCallExpr call, SideEffectDetector detector) {
        if (detector.classify(call).orElse(null) != SideEffectType.EXTERNAL_HTTP_CALL) return false;
        String name = call.getNameAsString().toLowerCase(Locale.ROOT);
        return name.startsWith("post") || name.equals("put") || name.equals("patch") || name.equals("delete")
                || name.startsWith("pay") || name.startsWith("charge") || name.startsWith("create")
                || name.startsWith("submit") || name.startsWith("cancel") || name.startsWith("refund")
                || name.startsWith("transfer") || name.startsWith("execute");
    }

    static boolean hasIdempotencyKey(MethodDeclaration method) {
        String text = method.toString().toLowerCase(Locale.ROOT);
        if (text.contains("idempotency-key") || text.contains("x-idempotency-key")) return true;
        return method.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                call.getNameAsString().toLowerCase(Locale.ROOT).contains("idempot")
                        || call.getArguments().stream().anyMatch(argument ->
                                argument.toString().toLowerCase(Locale.ROOT).contains("idempotencykey")));
    }

    static boolean hasStableBusinessKey(MethodDeclaration method) {
        return method.findAll(com.github.javaparser.ast.Node.class).stream()
                .map(Object::toString).map(text -> text.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT))
                .anyMatch(text -> BUSINESS_KEY_PARTS.stream().anyMatch(text::contains))
                || method.getBody().map(body -> body.findAll(com.github.javaparser.ast.expr.NameExpr.class).stream()
                        .anyMatch(name -> name.getNameAsString().equals("id"))
                        || body.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                                call.getNameAsString().equals("getId") || call.getNameAsString().equals("id"))).orElse(false);
    }
}
