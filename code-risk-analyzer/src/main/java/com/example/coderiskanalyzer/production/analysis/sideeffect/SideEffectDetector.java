package com.example.coderiskanalyzer.production.analysis.sideeffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.example.coderiskanalyzer.production.finding.Evidence;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

@Component
public class SideEffectDetector {

    private static final Set<String> DATABASE_WRITES = Set.of("save", "saveAll", "persist", "merge", "insert", "update");
    private static final Set<String> DATABASE_DELETES = Set.of("delete", "deleteAll", "remove");
    private static final Set<String> MESSAGE_PUBLISHERS = Set.of("send", "convertAndSend", "publish", "publishEvent");
    private static final Set<String> HTTP_METHODS = Set.of("exchange", "execute", "getForObject", "getForEntity", "postForObject",
            "postForEntity", "put", "patch", "delete", "retrieve", "toBodilessEntity");

    public List<SideEffect> detect(ParsedSource source) {
        if (source.compilationUnit() == null) return List.of();
        List<SideEffect> result = new ArrayList<>();
        for (MethodCallExpr call : source.compilationUnit().findAll(MethodCallExpr.class)) {
            classify(call).ifPresent(type -> result.add(new SideEffect(type, call.getNameAsString(),
                    call.getScope().map(Node::toString).orElse(""), location(source, call))));
        }
        return List.copyOf(result);
    }

    public Optional<SideEffectType> classify(MethodCallExpr call) {
        return classification(call).map(Classification::type);
    }

    private record Classification(SideEffectType type, String basis, String signature) {}

    private Optional<Classification> classification(MethodCallExpr call) {
        ResolvedMethodDeclaration method;
        try {
            method = call.resolve();
        } catch (RuntimeException exception) {
            return classifyByName(call).map(type -> new Classification(type, "NAME_HEURISTIC", ""));
        }
        Set<String> types = new java.util.HashSet<>();
        types.add(method.declaringType().getQualifiedName());
        try {
            for (var ancestor : method.declaringType().getAllAncestors()) {
                ancestor.getTypeDeclaration().ifPresent(declaration -> {
                    if (declaration.getDeclaredMethods().stream().anyMatch(candidate -> sameSignature(method, candidate, ancestor))
                            || (ancestor.getQualifiedName().equals("org.springframework.data.repository.CrudRepository")
                                && (method.getName().startsWith("deleteBy") || method.getName().startsWith("removeBy"))))
                        types.add(ancestor.getQualifiedName());
                });
            }
        } catch (RuntimeException ignored) {
        }
        String name = method.getName();
        SideEffectType type = null;
        if (matches(types, Set.of("org.springframework.data.repository.CrudRepository",
                "org.springframework.data.repository.ListCrudRepository", "org.springframework.data.jpa.repository.JpaRepository",
                "jakarta.persistence.EntityManager", "javax.persistence.EntityManager",
                "org.hibernate.Session", "org.apache.ibatis.session.SqlSession"))) {
            if (DATABASE_WRITES.contains(name) || Set.of("saveAndFlush", "saveAllAndFlush").contains(name)
                    || name.startsWith("insert") || name.startsWith("update")) type = SideEffectType.DATABASE_WRITE;
            if (DATABASE_DELETES.contains(name) || name.startsWith("delete") || name.startsWith("removeBy")) type = SideEffectType.DATABASE_DELETE;
        }
        if (matches(types, Set.of("org.springframework.jdbc.core.JdbcOperations", "org.springframework.jdbc.core.JdbcTemplate",
                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate"))
                && Set.of("update", "batchUpdate", "execute").contains(name)) type = SideEffectType.DATABASE_WRITE;
        if (matches(types, Set.of("org.springframework.web.client.RestOperations", "org.springframework.web.client.RestTemplate",
                "org.springframework.web.client.RestClient", "org.springframework.web.reactive.function.client.WebClient"))
                && HTTP_METHODS.contains(name)) type = SideEffectType.EXTERNAL_HTTP_CALL;
        if (matches(types, Set.of("java.net.http.HttpClient")) && Set.of("send", "sendAsync").contains(name)
                || matches(types, Set.of("org.apache.http.client.HttpClient", "org.apache.hc.client5.http.classic.HttpClient"))
                && name.equals("execute")) type = SideEffectType.EXTERNAL_HTTP_CALL;
        if (matches(types, Set.of("org.springframework.kafka.core.KafkaOperations", "org.springframework.kafka.core.KafkaTemplate",
                "org.springframework.amqp.core.AmqpTemplate", "org.springframework.amqp.rabbit.core.RabbitTemplate",
                "org.springframework.jms.core.JmsOperations", "org.springframework.jms.core.JmsTemplate",
                "org.springframework.context.ApplicationEventPublisher")) && MESSAGE_PUBLISHERS.contains(name))
            type = SideEffectType.MESSAGE_PUBLISH;
        if (types.contains("java.lang.Runtime") && name.equals("exec")
                || types.contains("java.lang.ProcessBuilder") && name.equals("start")) type = SideEffectType.PROCESS_EXECUTION;
        if (types.contains("java.nio.file.Files") && Set.of("write", "writeString", "delete", "deleteIfExists", "copy", "move").contains(name)
                || matches(types, Set.of("java.io.OutputStream", "java.io.Writer")) && name.equals("write")) type = SideEffectType.FILE_WRITE;
        try {
            boolean feign = method.declaringType().hasAnnotation("org.springframework.cloud.openfeign.FeignClient");
            if (call.getScope().isPresent()) {
                var receiver = call.getScope().get().calculateResolvedType();
                if (receiver.isReferenceType()) feign |= receiver.asReferenceType().getTypeDeclaration()
                        .map(declaration -> declaration.hasAnnotation("org.springframework.cloud.openfeign.FeignClient")).orElse(false);
            }
            if (feign && method.isAbstract() && !method.isStatic() && !types.contains("java.lang.Object"))
                type = SideEffectType.EXTERNAL_HTTP_CALL;
        } catch (RuntimeException ignored) {
        }
        SideEffectType classified = type;
        return Optional.ofNullable(classified).map(value -> new Classification(value, "RESOLVED_TYPE", method.getQualifiedSignature()));
    }

    private boolean sameSignature(ResolvedMethodDeclaration first,
                                  ResolvedMethodDeclaration second,
                                  com.github.javaparser.resolution.types.ResolvedReferenceType ancestor) {
        if (!first.getName().equals(second.getName()) || first.getNumberOfParams() != second.getNumberOfParams()) return false;
        for (int index = 0; index < first.getNumberOfParams(); index++) {
            if (!first.getParam(index).getType().describe().equals(ancestor.typeParametersMap().replaceAll(second.getParam(index).getType()).describe())) return false;
        }
        return true;
    }

    private boolean matches(Set<String> types, Set<String> supported) {
        return types.stream().anyMatch(type -> supported.contains(type)
                || supported.stream().anyMatch(owner -> type.startsWith(owner + ".")));
    }

    public List<Evidence> withEvidence(
            ParsedSource source, List<MethodCallExpr> calls,
            List<Evidence> existing) {
        var evidence = new ArrayList<>(existing);
        for (MethodCallExpr call : calls) classification(call).ifPresent(result -> evidence.add(
                new Evidence("SIDE_EFFECT_CLASSIFICATION",
                        result.basis().equals("NAME_HEURISTIC")
                                ? "Call resolution failed; the side effect is inferred from names and may be inaccurate."
                                : "Side effect classified from the resolved API type and method signature.",
                        location(source, call), java.util.Map.of("basis", result.basis(), "signature", result.signature(),
                                "method", call.getNameAsString(), "sideEffect", result.type().name()))));
        return List.copyOf(evidence);
    }

    private Optional<SideEffectType> classifyByName(MethodCallExpr call) {
        String method = call.getNameAsString();
        String lowerMethod = method.toLowerCase(Locale.ROOT);
        String receiver = call.getScope().map(Node::toString).orElse("").toLowerCase(Locale.ROOT);
        if (DATABASE_WRITES.contains(method) || lowerMethod.startsWith("insert") || lowerMethod.startsWith("update")) {
            if (looksLikeDatabaseReceiver(receiver)) return Optional.of(SideEffectType.DATABASE_WRITE);
        }
        if (DATABASE_DELETES.contains(method) || lowerMethod.startsWith("deleteby")) {
            if (looksLikeDatabaseReceiver(receiver)) return Optional.of(SideEffectType.DATABASE_DELETE);
        }
        if (MESSAGE_PUBLISHERS.contains(method)
                && (receiver.contains("kafka") || receiver.contains("rabbit") || receiver.contains("jms")
                    || receiver.contains("template"))) {
            return Optional.of(SideEffectType.MESSAGE_PUBLISH);
        }
        if (HTTP_METHODS.contains(method)
                && (receiver.contains("client") || receiver.contains("webclient") || receiver.contains("resttemplate")
                    || receiver.contains("httpclient") || receiver.contains("feign"))) {
            return Optional.of(SideEffectType.EXTERNAL_HTTP_CALL);
        }
        if ((receiver.contains("client") || receiver.contains("feign")) && !looksLikeDatabaseReceiver(receiver)) {
            return Optional.of(SideEffectType.EXTERNAL_HTTP_CALL);
        }
        if (method.equals("exec") && receiver.contains("runtime")) return Optional.of(SideEffectType.PROCESS_EXECUTION);
        if (Set.of("write", "writeString", "delete", "copy", "move").contains(method)
                && (receiver.contains("files") || receiver.contains("outputstream") || receiver.contains("writer"))) {
            return Optional.of(SideEffectType.FILE_WRITE);
        }
        return Optional.empty();
    }

    private boolean looksLikeDatabaseReceiver(String receiver) {
        return receiver.contains("repo") || receiver.contains("repository") || receiver.contains("dao")
                || receiver.contains("jdbc") || receiver.contains("entitymanager") || receiver.contains("mapper");
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(
                source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(),
                range.get().begin.line, range.get().begin.column, range.get().end.line, range.get().end.column);
    }
}
