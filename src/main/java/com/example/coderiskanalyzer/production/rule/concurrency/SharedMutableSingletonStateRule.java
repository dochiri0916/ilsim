package com.example.coderiskanalyzer.production.rule.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;

@Component
public class SharedMutableSingletonStateRule implements RiskRule {

    private static final Set<String> BEAN_ANNOTATIONS = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController");
    private static final Set<String> ENTRY_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping",
            "KafkaListener", "RabbitListener", "JmsListener", "Scheduled");

    @Override public String id() { return "CON-003"; }
    @Override public RiskCategory category() { return RiskCategory.CONCURRENCY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                if (!hasBeanAnnotation(type) || hasNonSingletonScope(type) || type.getMethods().stream().noneMatch(this::isEntryPoint)) continue;
                List<MethodDeclaration> methods = type.getMethods();
                for (FieldDeclaration field : type.getFields()) {
                    if (!isMutableInstanceField(field)) continue;
                    field.getVariables().forEach(variable -> {
                        if (isThreadSafeType(variable.getTypeAsString())) return;
                        for (MethodDeclaration method : methods) {
                            if (method.getModifiers().stream().anyMatch(mod -> mod.getKeyword().asString().equals("synchronized"))) {
                                continue;
                            }
                            Node mutation = findMutation(method, variable.getNameAsString());
                            if (mutation != null) findings.add(finding(source, type.getNameAsString(),
                                    variable.getNameAsString(), method, mutation));
                        }
                    });
                }
            }
        }
        return findings;
    }

    private boolean hasBeanAnnotation(TypeDeclaration<?> type) {
        return type.getAnnotations().stream().map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(BEAN_ANNOTATIONS::contains);
    }

    private boolean hasNonSingletonScope(TypeDeclaration<?> type) {
        return type.getAnnotations().stream().map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(name -> Set.of("RequestScope", "SessionScope", "ApplicationScope").contains(name))
                || type.getAnnotations().stream().filter(annotation -> simpleName(annotation.getNameAsString()).equals("Scope"))
                        .anyMatch(annotation -> !annotation.toString().contains("singleton"));
    }

    private boolean isEntryPoint(MethodDeclaration method) {
        return method.getAnnotations().stream().map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(ENTRY_ANNOTATIONS::contains);
    }

    private boolean isMutableInstanceField(FieldDeclaration field) {
        return !field.isStatic() && !field.isFinal();
    }

    private boolean isThreadSafeType(String type) {
        return type.contains("Atomic") || type.contains("Concurrent") || type.contains("ThreadLocal");
    }

    private Node findMutation(MethodDeclaration method, String fieldName) {
        boolean shadowed = method.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(fieldName))
                || method.findAll(VariableDeclarator.class).stream()
                        .anyMatch(variable -> variable.getNameAsString().equals(fieldName));
        for (AssignExpr assignment : method.findAll(AssignExpr.class)) {
            if (referencesField(assignment.getTarget(), fieldName, shadowed) && !insideSynchronized(assignment)) return assignment;
        }
        for (UnaryExpr unary : method.findAll(UnaryExpr.class)) {
            if (isIncrementOrDecrement(unary) && referencesField(unary.getExpression(), fieldName, shadowed)
                    && !insideSynchronized(unary)) return unary;
        }
        return null;
    }

    private boolean isIncrementOrDecrement(UnaryExpr unary) {
        return switch (unary.getOperator()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        };
    }

    private boolean insideSynchronized(Node node) {
        return node.findAncestor(SynchronizedStmt.class).isPresent();
    }

    /** A bare name that a local variable or parameter shadows does not refer to the field; {@code this.x} always does. */
    private boolean referencesField(Node expression, String fieldName, boolean shadowed) {
        if (expression instanceof NameExpr name) return !shadowed && name.getNameAsString().equals(fieldName);
        if (expression instanceof FieldAccessExpr field) {
            return field.getNameAsString().equals(fieldName) && field.getScope() instanceof ThisExpr;
        }
        return false;
    }

    private Finding finding(ParsedSource source, String owner, String field, MethodDeclaration method, Node mutation) {
        SourceLocation location = location(source, mutation);
        Evidence evidence = new Evidence("SHARED_FIELD_MUTATION",
                "A concurrent entry point mutates an instance field on a singleton Spring bean",
                location, Map.of("class", owner, "field", field, "method", method.getNameAsString()));
        return new Finding(id(), category(), Severity.HIGH, Confidence.HIGH,
                "Shared mutable singleton state",
                "Concurrent requests may mutate the shared field " + field + " at the same time.",
                location, List.of(evidence), FindingStatus.CONFIRMED);
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
