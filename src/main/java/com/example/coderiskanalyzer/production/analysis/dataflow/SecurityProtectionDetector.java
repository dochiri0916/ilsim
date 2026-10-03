package com.example.coderiskanalyzer.production.analysis.dataflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;

/** Conservative, intraprocedural path analysis for a specific security sink. */
public final class SecurityProtectionDetector {
    private SecurityProtectionDetector() {}

    // Without a sink, execution order cannot be established. Retained for API compatibility.
    public static boolean hasPathContainmentGuard(MethodDeclaration method, String sourceVariable) {
        return false;
    }

    public static boolean hasHostAllowlistGuard(MethodDeclaration method, String sourceVariable) {
        return false;
    }

    public static boolean hasPathContainmentGuard(MethodDeclaration method, String sourceVariable,
                                                   SourceLocation sinkLocation) {
        return protects(method, sourceVariable, sinkLocation, false);
    }

    public static boolean hasHostAllowlistGuard(MethodDeclaration method, String sourceVariable,
                                                SourceLocation sinkLocation) {
        return protects(method, sourceVariable, sinkLocation, true);
    }

    private static boolean protects(MethodDeclaration method, String source, SourceLocation location, boolean host) {
        Node sink = method.findAll(Node.class).stream().filter(node ->
                node instanceof MethodCallExpr || node instanceof ObjectCreationExpr)
                .filter(node -> node.getRange().map(range -> range.begin.line == location.startLine()
                        && range.begin.column == location.startColumn()
                        && range.end.line == location.endLine()
                        && range.end.column == location.endColumn()).orElse(false))
                .findFirst().orElse(null);
        if (sink == null || method.getBody().isEmpty()) return false;
        for (Node owner = sink.getParentNode().orElse(null); owner != null && owner != method;
             owner = owner.getParentNode().orElse(null)) {
            if (owner instanceof LambdaExpr || owner instanceof MethodDeclaration) return false;
        }
        var analysis = new GuardAnalysis(method, source, sink, host);
        State initial = new State();
        method.getParameters().forEach(parameter -> initial.values.put(parameter.getNameAsString(),
                new NameExpr(parameter.getNameAsString())));
        analysis.walk(method.getBody().get(), List.of(initial));
        return analysis.reached && analysis.protectedOnEveryPath;
    }

    private static final class State {
        final Map<String, Expression> values = new HashMap<>();
        final Set<String> guards = new HashSet<>();
        boolean uncertain;

        State copy() {
            State copy = new State();
            copy.values.putAll(values);
            copy.guards.addAll(guards);
            copy.uncertain = uncertain;
            return copy;
        }

        Expression value(Expression expression) {
            Expression result = expression.clone();
            // Distinct helper evaluations are not aliases, even when their text is identical.
            for (MethodCallExpr call : result.findAll(MethodCallExpr.class)) {
                if (!Set.of("of", "create", "resolve", "normalize", "toRealPath", "getHost",
                        "toString", "toASCIIString", "toURL").contains(call.getNameAsString())
                        && !call.getNameAsString().contains("__evaluation_")) {
                    call.getRange().ifPresent(range -> call.setName(call.getNameAsString()
                            + "__evaluation_" + range.begin.line + "_" + range.begin.column));
                }
            }
            // Values stored in the environment are snapshots, so substitution is one pass.
            if (result instanceof NameExpr name) return values.getOrDefault(name.getNameAsString(), result).clone();
            for (NameExpr name : new ArrayList<>(result.findAll(NameExpr.class))) {
                Expression replacement = values.get(name.getNameAsString());
                if (replacement != null) name.replace(replacement.clone());
            }
            return unwrap(result);
        }
    }

    private static final class GuardAnalysis {
        final String source;
        final Node sink;
        final boolean host;
        final Set<String> inputs = new HashSet<>();
        boolean reached;
        boolean protectedOnEveryPath = true;

        GuardAnalysis(MethodDeclaration method, String source, Node sink, boolean host) {
            inputs.add(source);
            boolean listener = method.getAnnotations().stream().anyMatch(annotation ->
                    Set.of("KafkaListener", "RabbitListener", "JmsListener")
                            .contains(annotation.getName().getIdentifier()));
            method.getParameters().stream().filter(parameter -> listener || parameter.getAnnotations().stream()
                    .anyMatch(annotation -> Set.of("RequestParam", "PathVariable", "RequestHeader", "RequestBody")
                            .contains(annotation.getName().getIdentifier())))
                    .forEach(parameter -> inputs.add(parameter.getNameAsString()));
            this.source = source;
            this.sink = sink;
            this.host = host;
        }

        List<State> walk(Statement statement, List<State> states) {
            if (states.isEmpty()) return states;
            if (states.size() > 128) {
                protectedOnEveryPath = false;
                return List.of();
            }
            if (statement instanceof BlockStmt block) {
                for (Statement child : block.getStatements()) states = walk(child, states);
                return states;
            }
            if (statement instanceof IfStmt branch) {
                if (contains(branch.getCondition(), sink)) {
                    visitSink(states);
                    return List.of();
                }
                if (hasUnknownCalls(branch.getCondition())
                        || !branch.getCondition().findAll(AssignExpr.class).isEmpty()
                        || branch.getCondition().findAll(UnaryExpr.class).stream().anyMatch(unary ->
                        unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT)) {
                    for (State state : states) state.uncertain = true;
                }
                List<State> result = new ArrayList<>();
                for (State state : states) {
                    State yes = state.copy();
                    State no = state.copy();
                    addGuard(branch.getCondition(), true, yes);
                    addGuard(branch.getCondition(), false, no);
                    result.addAll(walk(branch.getThenStmt(), List.of(yes)));
                    result.addAll(branch.getElseStmt().map(other -> walk(other, List.of(no))).orElse(List.of(no)));
                }
                return result;
            }
            if (statement instanceof ExpressionStmt expressionStmt) {
                if (contains(statement, sink)) {
                    // An assignment or another call in the same expression has unspecified effects.
                    if (!statement.findAll(AssignExpr.class).isEmpty()) protectedOnEveryPath = false;
                    visitSink(states);
                    return List.of();
                }
                for (State state : states) update(expressionStmt.getExpression(), state);
                return states;
            }
            if (statement instanceof ReturnStmt || statement instanceof ThrowStmt) {
                if (contains(statement, sink)) visitSink(states);
                return List.of();
            }
            if (statement instanceof EmptyStmt) return states;
            // Loops, try/catch/finally, switch, synchronized and nested executable scopes
            // require additional control-flow semantics; never use them as proof of protection.
            if (contains(statement, sink)) {
                reached = true;
                protectedOnEveryPath = false;
                return List.of();
            }
            for (State state : states) state.uncertain = true;
            return states;
        }

        void update(Expression expression, State state) {
            if (expression instanceof VariableDeclarationExpr declaration) {
                if (hasUnknownCalls(expression)) {
                    state.guards.clear();
                    state.uncertain = true;
                }
                declaration.getVariables().forEach(variable -> {
                    Expression value = variable.getInitializer().map(state::value)
                            .orElseGet(() -> new NameExpr(variable.getNameAsString()));
                    if (state.values.containsKey(variable.getNameAsString())) {
                        state.guards.clear();
                        state.uncertain = true;
                    }
                    state.values.put(variable.getNameAsString(), value);
                });
            } else if (expression instanceof AssignExpr assignment && assignment.getTarget() instanceof NameExpr name
                    && assignment.getOperator() == AssignExpr.Operator.ASSIGN) {
                Expression value = state.value(assignment.getValue());
                state.guards.clear();
                state.values.put(name.getNameAsString(), value);
            } else {
                // Unknown mutations and helper calls cannot preserve a proof of validation.
                state.guards.clear();
                boolean knownSink = expression instanceof MethodCallExpr call
                        && (call.getNameAsString().startsWith("read") || call.getNameAsString().startsWith("write")
                        || Set.of("newInputStream", "newOutputStream", "delete", "deleteIfExists", "copy", "move",
                        "getForObject", "getForEntity", "postForObject", "postForEntity", "exchange", "execute",
                        "send", "sendAsync", "openConnection", "openStream", "put", "patch")
                        .contains(call.getNameAsString()));
                if (!knownSink || !expression.findAll(AssignExpr.class).isEmpty()) state.uncertain = true;
            }
        }

        boolean hasUnknownCalls(Expression expression) {
            return expression.findAll(MethodCallExpr.class).stream().anyMatch(call ->
                    !Set.of("of", "create", "resolve", "normalize", "toRealPath", "getHost", "toString",
                            "toASCIIString", "toURL", "startsWith", "contains").contains(call.getNameAsString()));
        }

        void addGuard(Expression condition, boolean truth, State state) {
            condition = unwrap(condition);
            if (condition instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
                addGuard(unary.getExpression(), !truth, state);
            } else if (condition instanceof BinaryExpr binary) {
                if (binary.getOperator() == BinaryExpr.Operator.AND && truth
                        || binary.getOperator() == BinaryExpr.Operator.OR && !truth) {
                    addGuard(binary.getLeft(), truth, state);
                    addGuard(binary.getRight(), truth, state);
                } else if (binary.getRight() instanceof BooleanLiteralExpr literal
                        && (binary.getOperator() == BinaryExpr.Operator.EQUALS
                        || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS)) {
                    addGuard(binary.getLeft(), truth == (literal.getValue()
                            == (binary.getOperator() == BinaryExpr.Operator.EQUALS)), state);
                }
            } else if (truth && condition instanceof MethodCallExpr call && call.getScope().isPresent()
                    && call.getArguments().size() == 1) {
                Expression scope = state.value(call.getScope().get());
                Expression argument = state.value(call.getArgument(0));
                // Without normalize()/toRealPath(), "../" segments pass startsWith() containment checks.
                if (!host && call.getNameAsString().equals("startsWith") && referencesSource(scope)
                        && normalizesResult(scope)
                        && !referencesSource(argument) && isPath(call.getScope().get()) && isPath(call.getArgument(0))) {
                    state.guards.add(scope.toString());
                } else if (host && call.getNameAsString().equals("contains")
                        && call.getScope().get() instanceof NameExpr allowlist
                        && allowlist.getNameAsString().toLowerCase(java.util.Locale.ROOT).matches(".*(allowed|allowlist).*")) {
                    if (argument instanceof MethodCallExpr getter && getter.getNameAsString().equals("getHost")
                            && getter.getArguments().isEmpty() && getter.getScope().isPresent()
                            && referencesSource(getter.getScope().get()) && !referencesSource(scope)) {
                        state.guards.add(target(getter.getScope().get()).toString());
                    }
                }
            }
        }

        /** True when the value's own call chain (not an input it was built from) ends in normalize()/toRealPath(). */
        boolean normalizesResult(Expression expression) {
            for (Expression current = unwrap(expression); current instanceof MethodCallExpr call;
                 current = call.getScope().map(SecurityProtectionDetector::unwrap).orElse(null)) {
                if (call.getNameAsString().equals("normalize") || call.getNameAsString().equals("toRealPath")) return true;
                if (!call.getNameAsString().equals("toAbsolutePath")) return false;
            }
            return false;
        }

        boolean isPath(Expression expression) {
            expression = unwrap(expression);
            if (!(expression instanceof NameExpr name)) return false;
            Node child = expression;
            for (Node owner = child.getParentNode().orElse(null); owner != null;
                 child = owner, owner = owner.getParentNode().orElse(null)) {
                if (owner instanceof BlockStmt block) {
                    String type = null;
                    for (Statement statement : block.getStatements()) {
                        if (statement == child) break;
                        if (statement instanceof ExpressionStmt expressionStmt
                                && expressionStmt.getExpression() instanceof VariableDeclarationExpr declaration) {
                            for (var variable : declaration.getVariables()) {
                                if (variable.getNameAsString().equals(name.getNameAsString())) type = variable.getTypeAsString();
                            }
                        }
                    }
                    if (type != null) return Set.of("Path", "java.nio.file.Path").contains(type);
                }
                if (owner instanceof MethodDeclaration method) {
                    return method.getParameters().stream().anyMatch(parameter ->
                            parameter.getNameAsString().equals(name.getNameAsString())
                                    && Set.of("Path", "java.nio.file.Path").contains(parameter.getTypeAsString()));
                }
            }
            return false;
        }

        boolean referencesSource(Expression expression) {
            return expression.findAll(NameExpr.class).stream().anyMatch(name -> name.getNameAsString().equals(source));
        }

        void visitSink(List<State> states) {
            reached = true;
            List<Expression> arguments;
            if (sink instanceof MethodCallExpr call) {
                arguments = host && Set.of("openConnection", "openStream", "connect", "getInputStream")
                        .contains(call.getNameAsString()) ? call.getScope().stream().toList()
                        : call.getArguments().stream().limit(!host && Set.of("copy", "move")
                        .contains(call.getNameAsString()) ? 2 : 1).toList();
            } else arguments = ((ObjectCreationExpr) sink).getArguments().stream().limit(1).toList();
            for (State state : states) {
                boolean checked = false;
                // Every source-dependent argument must refer to a validated value (copy/move included).
                for (Expression argument : arguments) {
                    Expression value = state.value(argument);
                    if (value.findAll(NameExpr.class).stream().noneMatch(name -> inputs.contains(name.getNameAsString()))) continue;
                    checked = true;
                    String key = (host ? target(value) : value).toString();
                    if (!state.guards.contains(key)) protectedOnEveryPath = false;
                }
                if (!checked || state.uncertain) protectedOnEveryPath = false;
            }
        }
    }

    private static Expression target(Expression expression) {
        expression = unwrap(expression);
        if (expression instanceof MethodCallExpr call) {
            if (Set.of("toString", "toASCIIString", "toURL").contains(call.getNameAsString())
                    && call.getArguments().isEmpty() && call.getScope().isPresent()) return target(call.getScope().get());
            if (call.getNameAsString().equals("create") && call.getScope().map(Node::toString)
                    .filter(scope -> scope.equals("URI") || scope.equals("java.net.URI")).isPresent()
                    && call.getArguments().size() == 1) return target(call.getArgument(0));
        }
        if (expression instanceof ObjectCreationExpr creation && creation.getArguments().size() == 1
                && Set.of("URI", "URL", "java.net.URI", "java.net.URL").contains(creation.getTypeAsString())) {
            return target(creation.getArgument(0));
        }
        return expression;
    }

    private static Expression unwrap(Expression expression) {
        while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
        return expression;
    }

    private static boolean contains(Node parent, Node child) {
        for (Node current = child; current != null; current = current.getParentNode().orElse(null)) {
            if (current == parent) return true;
        }
        return false;
    }
}
