package com.example.coderiskanalyzer.production.analysis.dataflow;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import org.springframework.stereotype.Component;

@Component
public class DataFlowAnalyzer {

    private static final Set<String> SOURCE_ANNOTATIONS = Set.of(
            "RequestParam", "PathVariable", "RequestHeader", "RequestBody", "KafkaListener", "RabbitListener", "JmsListener");

    private static final Set<String> NON_INJECTABLE_TYPES = Set.of("byte", "short", "int", "long", "float", "double",
            "boolean", "char", "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "BigDecimal",
            "BigInteger", "UUID", "LocalDate", "LocalDateTime", "Instant");

    private static boolean nonInjectable(String type) {
        return NON_INJECTABLE_TYPES.contains(simpleTypeName(type));
    }

    private static String simpleTypeName(String type) {
        String raw = type.replaceAll("<.*>", "").trim();
        int dot = raw.lastIndexOf('.');
        return dot < 0 ? raw : raw.substring(dot + 1);
    }

    public List<DataFlowPath> analyze(MethodDeclaration method, ParsedSource source) {
        return analyze(method, source, TaintSinkKind.SQL);
    }

    public List<DataFlowPath> analyze(MethodDeclaration method, ParsedSource source, TaintSinkKind sinkKind) {
        return analyze(method, source, sinkKind, collectTaintedVariables(method, Map.of()));
    }

    public List<DataFlowPath> analyze(MethodDeclaration method, ParsedSource source, TaintSinkKind sinkKind,
                                      List<ParsedSource> context) {
        record Declared(MethodDeclaration method, ParsedSource source) {}
        record Work(Declared declared, Map<String, TaintSource> incoming) {}
        List<Declared> declarations = context.stream().filter(p -> p.compilationUnit() != null)
                .flatMap(p -> p.compilationUnit().findAll(MethodDeclaration.class).stream().map(m -> new Declared(m, p)))
                .toList();
        List<MethodDeclaration> methods = declarations.stream().map(Declared::method).toList();
        var pending = new ArrayDeque<Work>();
        pending.add(new Work(new Declared(method, source), Map.of()));
        Map<MethodDeclaration, Map<String, TaintSource>> seen = new IdentityHashMap<>();
        List<DataFlowPath> paths = new ArrayList<>();
        while (!pending.isEmpty()) {
            Work work = pending.removeFirst();
            MethodDeclaration current = work.declared().method();
            Map<String, TaintSource> incoming = new HashMap<>(seen.getOrDefault(current, Map.of()));
            boolean first = !seen.containsKey(current);
            work.incoming().forEach(incoming::putIfAbsent);
            if (!first && seen.get(current).keySet().containsAll(incoming.keySet())) continue;
            seen.put(current, incoming);
            Map<String, TaintSource> tainted = collectTaintedVariables(current, incoming);
            paths.addAll(analyze(current, work.declared().source(), sinkKind, tainted));
            for (MethodCallExpr call : current.findAll(MethodCallExpr.class)) {
                MethodDeclaration resolved = projectTarget(call, current, methods);
                if (resolved == null) continue;
                Declared target = declarations.stream().filter(d -> d.method() == resolved).findFirst().orElseThrow();
                Map<String, TaintSource> arguments = new HashMap<>();
                for (int index = 0; index < call.getArguments().size() && !resolved.getParameters().isEmpty(); index++) {
                    int parameterIndex = Math.min(index, resolved.getParameters().size() - 1);
                    if (index >= resolved.getParameters().size() && !resolved.getParameter(parameterIndex).isVarArgs()) break;
                    TaintSource value = sourceIn(call.getArgument(index), tainted);
                    var parameter = resolved.getParameter(parameterIndex);
                    if (value != null && !nonInjectable(parameter.getTypeAsString())) {
                        arguments.putIfAbsent(parameter.getNameAsString(), new TaintSource(parameter.getNameAsString(), value.kind()));
                    }
                }
                if (!arguments.isEmpty()) pending.add(new Work(target, arguments));
            }
        }
        return paths.stream().distinct().toList();
    }

    private MethodDeclaration projectTarget(MethodCallExpr call, MethodDeclaration caller,
                                             List<MethodDeclaration> declarations) {
        try {
            var ast = call.resolve().toAst(MethodDeclaration.class);
            if (ast.isEmpty()) return null;
            var target = ast.get();
            var path = target.findCompilationUnit().flatMap(unit -> unit.getStorage().map(storage -> storage.getPath()));
            return declarations.stream().filter(candidate -> candidate == target || path.isPresent()
                    && candidate.findCompilationUnit().flatMap(unit -> unit.getStorage().map(storage -> storage.getPath()))
                    .filter(path.get()::equals).isPresent() && candidate.getRange().equals(target.getRange()))
                    .findFirst().orElse(null);
        } catch (RuntimeException unresolved) {
            if (call.getScope().isPresent() && !call.getScope().get().isThisExpr()) return null;
            var owner = caller.findAncestor(TypeDeclaration.class);
            var candidates = declarations.stream().filter(candidate -> candidate.findAncestor(TypeDeclaration.class)
                    .filter(type -> type == owner.orElse(null)).isPresent()
                    && candidate.getNameAsString().equals(call.getNameAsString())
                    && candidate.getParameters().size() == call.getArguments().size()).toList();
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }
    }

    private List<DataFlowPath> analyze(MethodDeclaration method, ParsedSource source, TaintSinkKind sinkKind,
                                       Map<String, TaintSource> tainted) {
        Set<String> processBuilderVariables = method.findAll(VariableDeclarator.class).stream()
                .filter(variable -> variable.getTypeAsString().endsWith("ProcessBuilder"))
                .map(variable -> variable.getNameAsString()).collect(java.util.stream.Collectors.toSet());
        List<DataFlowPath> paths = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!isSink(call, sinkKind) && !isProcessBuilderSink(call, sinkKind, processBuilderVariables)) continue;
            if (sinkKind == TaintSinkKind.COMMAND && call.getNameAsString().equals("command")
                    && call.getScope().stream().anyMatch(scope -> method.findAll(MethodCallExpr.class).stream()
                            .anyMatch(other -> other.getNameAsString().equals("start")
                                    && other.getScope().map(Node::toString).filter(scope.toString()::equals).isPresent()))) continue;
            if (sinkKind == TaintSinkKind.COMMAND && call.getNameAsString().equals("start")
                    && call.getScope().stream().anyMatch(scope -> scope.findAll(ObjectCreationExpr.class).stream()
                            .anyMatch(creation -> creation.getTypeAsString().endsWith("ProcessBuilder")))) continue;
            for (Expression argument : sinkArguments(call, sinkKind)) {
                TaintSource taint = sourceIn(argument, tainted);
                if (taint != null) paths.add(new DataFlowPath(taint, call.getNameAsString(), location(source, call)));
            }
        }
        for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
            if (!isCreationSink(creation, sinkKind)) continue;
            for (Expression argument : creation.getArguments()) {
                TaintSource taint = sourceIn(argument, tainted);
                if (taint != null) {
                    paths.add(new DataFlowPath(taint, "new " + creation.getTypeAsString(), location(source, creation)));
                    break;
                }
            }
        }
        return List.copyOf(paths);
    }

    private Map<String, TaintSource> collectTaintedVariables(MethodDeclaration method, Map<String, TaintSource> incoming) {
        Map<String, TaintSource> tainted = new HashMap<>(incoming);
        Set<String> safeTyped = new java.util.HashSet<>();
        method.getParameters().stream().filter(parameter -> nonInjectable(parameter.getTypeAsString()))
                .forEach(parameter -> safeTyped.add(parameter.getNameAsString()));
        method.findAll(VariableDeclarator.class).stream().filter(variable -> nonInjectable(variable.getTypeAsString()))
                .forEach(variable -> safeTyped.add(variable.getNameAsString()));
        boolean messageListener = method.getAnnotations().stream()
                .map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(name -> name.equals("KafkaListener") || name.equals("RabbitListener") || name.equals("JmsListener"));
        method.getParameters().forEach(parameter -> {
            if (safeTyped.contains(parameter.getNameAsString())) return;
            var sourceAnnotation = parameter.getAnnotations().stream()
                    .filter(annotation -> SOURCE_ANNOTATIONS.contains(simpleName(annotation.getNameAsString())))
                    .findFirst();
            if (sourceAnnotation.isPresent()) {
                String name = simpleName(sourceAnnotation.get().getNameAsString());
                tainted.put(parameter.getNameAsString(), new TaintSource(parameter.getNameAsString(), name));
            } else if (messageListener) {
                tainted.put(parameter.getNameAsString(), new TaintSource(parameter.getNameAsString(), "MESSAGE_PAYLOAD"));
            } else if (Set.of("HttpServletRequest", "ServletRequest", "ServerHttpRequest")
                    .contains(simpleName(parameter.getTypeAsString()))) {
                tainted.put(parameter.getNameAsString(), new TaintSource(parameter.getNameAsString(), "HTTP_REQUEST"));
            }
        });

        boolean changed;
        do {
            changed = false;
            for (VariableDeclarationExpr declaration : method.findAll(VariableDeclarationExpr.class)) {
                for (var variable : declaration.getVariables()) {
                    if (!tainted.containsKey(variable.getNameAsString()) && !safeTyped.contains(variable.getNameAsString())
                            && variable.getInitializer().isPresent()) {
                        TaintSource sourceValue = sourceIn(variable.getInitializer().get(), tainted);
                        if (sourceValue != null) {
                            tainted.put(variable.getNameAsString(), sourceValue);
                            changed = true;
                        }
                    }
                }
            }
            for (AssignExpr assignment : method.findAll(AssignExpr.class)) {
                String target = assignment.getTarget().toString();
                if (target.matches("[A-Za-z_$][A-Za-z0-9_$]*") && !tainted.containsKey(target) && !safeTyped.contains(target)) {
                    TaintSource sourceValue = sourceIn(assignment.getValue(), tainted);
                    if (sourceValue != null) {
                        tainted.put(target, sourceValue);
                        changed = true;
                    }
                }
            }
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("append") && call.getScope().isPresent()
                        && call.getArguments().stream().anyMatch(argument -> sourceIn(argument, tainted) != null)) {
                    String target = call.getScope().get().toString();
                    if (target.matches("[A-Za-z_$][A-Za-z0-9_$]*") && !tainted.containsKey(target)) {
                        tainted.put(target, call.getArguments().stream()
                                .map(argument -> sourceIn(argument, tainted)).filter(java.util.Objects::nonNull)
                                .findFirst().orElseThrow());
                        changed = true;
                    }
                }
                if (call.getNameAsString().equals("command") && call.getScope().isPresent()) {
                    String target = call.getScope().get().toString();
                    if (target.matches("[A-Za-z_$][A-Za-z0-9_$]*")
                            && method.findAll(VariableDeclarator.class).stream().anyMatch(variable ->
                                    variable.getNameAsString().equals(target)
                                            && variable.getTypeAsString().endsWith("ProcessBuilder"))
                            && !tainted.containsKey(target)) {
                        TaintSource sourceValue = call.getArguments().stream()
                                .map(argument -> sourceIn(argument, tainted)).filter(java.util.Objects::nonNull)
                                .findFirst().orElse(null);
                        if (sourceValue != null) {
                            tainted.put(target, sourceValue);
                            changed = true;
                        }
                    }
                }
            }
        } while (changed);

        return tainted;
    }

    private TaintSource sourceIn(Expression expression, Map<String, TaintSource> tainted) {
        for (NameExpr name : expression.findAll(NameExpr.class)) {
            TaintSource source = tainted.get(name.getNameAsString());
            if (source != null) return source;
        }
        if (expression instanceof NameExpr name) return tainted.get(name.getNameAsString());
        return null;
    }

    private boolean isSink(MethodCallExpr call, TaintSinkKind kind) {
        String method = call.getNameAsString();
        String receiver = call.getScope().map(Node::toString).orElse("").toLowerCase(Locale.ROOT);
        Set<String> types = receiverTypes(call);
        return switch (kind) {
            case SQL -> {
                if (!types.isEmpty()) {
                    yield types.contains("java.sql.Connection") && method.equals("prepareStatement")
                            || types.stream().anyMatch(type -> Set.of("java.sql.Statement", "java.sql.PreparedStatement",
                                    "java.sql.CallableStatement").contains(type))
                            && Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate").contains(method)
                            || types.stream().anyMatch(type -> type.startsWith("org.springframework.jdbc.core."))
                            && Set.of("query", "queryForList", "queryForObject", "update", "execute", "batchUpdate").contains(method)
                            || types.stream().anyMatch(type -> Set.of("jakarta.persistence.EntityManager",
                                    "javax.persistence.EntityManager").contains(type)) && method.equals("createNativeQuery");
                }
                boolean statementCall = Set.of("execute", "executeQuery", "executeUpdate", "prepareStatement")
                        .contains(method) && (receiver.contains("statement") || receiver.contains("connection"));
                boolean templateCall = Set.of("query", "queryForList", "queryForObject", "update", "execute",
                        "executeLargeUpdate", "executeBatch").contains(method)
                        && (receiver.contains("jdbc") || receiver.contains("template"));
                yield statementCall || templateCall
                        || method.equals("createNativeQuery") && receiver.contains("entitymanager");
            }
            case COMMAND -> types.contains("java.lang.Runtime") && method.equals("exec")
                    || types.contains("java.lang.ProcessBuilder") && Set.of("command", "start").contains(method)
                    || types.isEmpty() && ((method.equals("exec") && receiver.contains("runtime"))
                    || (method.equals("command") && receiver.contains("processbuilder"))
                    || (method.equals("start") && receiver.contains("processbuilder")));
            case PATH -> isPathIoMethod(method, receiver);
            case SSRF -> isHttpRequestMethod(method, receiver);
        };
    }

    private Set<String> receiverTypes(MethodCallExpr call) {
        Set<String> types = new java.util.HashSet<>();
        try {
            var declaration = call.resolve().declaringType();
            types.add(declaration.getQualifiedName());
            declaration.getAllAncestors().forEach(type -> types.add(type.getQualifiedName()));
        } catch (RuntimeException unresolved) {
            try {
                if (call.getScope().isPresent()) {
                    var type = call.getScope().get().calculateResolvedType();
                    if (type.isReferenceType()) types.add(type.asReferenceType().getQualifiedName());
                }
            } catch (RuntimeException ignored) {
            }
        }
        return types;
    }

    private boolean isProcessBuilderSink(MethodCallExpr call, TaintSinkKind kind, Set<String> processBuilders) {
        if (kind != TaintSinkKind.COMMAND || call.getScope().isEmpty()) return false;
        String name = call.getNameAsString();
        String receiver = call.getScope().get().toString();
        return (name.equals("command") || name.equals("start")) && processBuilders.contains(receiver);
    }

    private List<Expression> sinkArguments(MethodCallExpr call, TaintSinkKind kind) {
        if (kind == TaintSinkKind.COMMAND && call.getNameAsString().equals("start")) {
            return call.getScope().stream().toList();
        }
        if (kind == TaintSinkKind.SSRF && Set.of("openConnection", "openStream", "connect", "getInputStream")
                .contains(call.getNameAsString())) return call.getScope().stream().toList();
        if (call.getArguments().isEmpty()) return List.of();
        if (kind == TaintSinkKind.PATH && Set.of("copy", "move").contains(call.getNameAsString())
                && call.getArguments().size() > 1) {
            return call.getArguments().stream().limit(2).filter(this::pathArgument).toList();
        }
        return List.of(call.getArgument(0));
    }

    private boolean pathArgument(Expression expression) {
        try {
            var type = expression.calculateResolvedType();
            return type.isReferenceType() && (type.asReferenceType().getQualifiedName().equals("java.nio.file.Path")
                    || type.asReferenceType().getAllAncestors().stream().anyMatch(ancestor -> ancestor.getQualifiedName().equals("java.nio.file.Path")));
        } catch (RuntimeException unresolved) {
            return true;
        }
    }

    private boolean isPathIoMethod(String method, String receiver) {
        boolean fileApi = receiver.endsWith("files") || receiver.contains("filechannel")
                || receiver.contains("fileinputstream") || receiver.contains("fileoutputstream")
                || receiver.contains("randomaccessfile");
        return fileApi && (method.startsWith("read") || method.startsWith("write")
                || Set.of("newInputStream", "newOutputStream", "delete", "deleteIfExists", "copy", "move")
                        .contains(method));
    }

    private boolean isHttpRequestMethod(String method, String receiver) {
        boolean httpClient = receiver.contains("resttemplate") || receiver.contains("webclient")
                || receiver.contains("httpclient") || receiver.contains("feign")
                || receiver.contains("urlconnection") || receiver.contains("url") || receiver.endsWith("client")
                || receiver.contains("uribuilder");
        return httpClient && (Set.of("uri", "url", "exchange", "execute", "getForObject", "getForEntity",
                "postForObject", "postForEntity", "put", "patch", "delete", "send", "sendAsync",
                "openConnection", "openStream").contains(method));
    }

    private boolean isCreationSink(ObjectCreationExpr creation, TaintSinkKind kind) {
        String type = creation.getTypeAsString();
        return switch (kind) {
            case COMMAND -> type.endsWith("ProcessBuilder");
            case PATH -> Set.of("FileInputStream", "FileOutputStream", "RandomAccessFile")
                    .stream().anyMatch(type::endsWith);
            case SSRF -> false;
            case SQL -> false;
        };
    }

    private String simpleName(String annotation) {
        int dot = annotation.lastIndexOf('.');
        return dot < 0 ? annotation : annotation.substring(dot + 1);
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
