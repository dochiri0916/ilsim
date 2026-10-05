package com.example.coderiskanalyzer.production.rule.concurrency;

import java.util.ArrayList;
import java.util.HashSet;
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
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;

@Component
public class LostUpdateRiskRule implements RiskRule {

    private static final Set<String> READ_PREFIXES = Set.of("find", "get", "load");
    private static final Set<String> WRITE_NAMES = Set.of("save", "saveAll", "update", "merge", "persist");

    @Override public String id() { return "CON-002"; }
    @Override public RiskCategory category() { return RiskCategory.CONCURRENCY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        Set<String> versionedTypes = versionedTypes(context);
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                List<MethodCallExpr> reads = method.findAll(MethodCallExpr.class).stream()
                        .filter(this::isRead).toList();
                List<MethodCallExpr> writes = method.findAll(MethodCallExpr.class).stream()
                        .filter(this::isWrite).toList();
                for (MethodCallExpr read : reads) {
                    if (hasPessimisticProtection(read)) continue;
                    VariableDeclarator entity = assignedVariable(method, read);
                    if (entity == null || versionedTypes.contains(entity.getTypeAsString())) continue;
                    for (Node mutation : mutationsBasedOn(method, entity.getNameAsString())) {
                        if (writes.stream().noneMatch(write -> isAfter(write, mutation))) continue;
                        SourceLocation location = location(source, mutation);
                        Evidence evidence = new Evidence("READ_MODIFY_WRITE", "A value is read, changed using that entity's existing state, and later persisted without visible optimistic or pessimistic locking.",
                                location, Map.of("entityVariable", entity.getNameAsString(), "readMethod", read.getNameAsString()));
                        findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                                "Possible lost update", "Concurrent updates may overwrite each other because the entity is modified from a previously read value without a visible lock or @Version field.",
                                location, List.of(evidence), FindingStatus.REVIEW));
                    }
                }
            }
        }
        return findings.stream().distinct().toList();
    }

    private Set<String> versionedTypes(AnalysisContext context) {
        Set<String> result = new HashSet<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                if (type.getFields().stream().anyMatch(field -> field.getAnnotations().stream()
                        .anyMatch(annotation -> simpleName(annotation.getNameAsString()).equals("Version")))) {
                    result.add(type.getNameAsString());
                }
            }
        }
        return result;
    }

    private boolean hasPessimisticProtection(MethodCallExpr read) {
        try {
            var resolved = read.resolve();
            Set<String> owners = new HashSet<>();
            owners.add(resolved.declaringType().getQualifiedName());
            resolved.declaringType().getAllAncestors().forEach(type -> owners.add(type.getQualifiedName()));
            boolean repository = owners.stream().anyMatch(type -> type.startsWith("org.springframework.data.repository.")
                    || type.startsWith("org.springframework.data.jpa.repository."));
            if (repository && resolved.toAst(MethodDeclaration.class).stream().anyMatch(declaration ->
                    declaration.getAnnotations().stream().anyMatch(annotation -> {
                        String name = annotation.getNameAsString();
                        boolean lock = name.equals("org.springframework.data.jpa.repository.Lock")
                                || name.equals("Lock") && declaration.findCompilationUnit().stream().anyMatch(unit ->
                                unit.getImports().stream().anyMatch(imported ->
                                        imported.getNameAsString().equals("org.springframework.data.jpa.repository.Lock")
                                                || imported.isAsterisk() && imported.getNameAsString().equals("org.springframework.data.jpa.repository")));
                        return lock && annotation.findAll(com.github.javaparser.ast.expr.FieldAccessExpr.class).stream()
                                .anyMatch(field -> field.getNameAsString().equals("PESSIMISTIC_WRITE"));
                    }))) return true;
            boolean entityManager = owners.stream().anyMatch(type -> Set.of("jakarta.persistence.EntityManager",
                    "javax.persistence.EntityManager", "org.hibernate.Session").contains(type));
            return entityManager && read.getArguments().stream().anyMatch(argument ->
                    argument.isFieldAccessExpr() && argument.asFieldAccessExpr().getNameAsString().equals("PESSIMISTIC_WRITE")
                            && Set.of("jakarta.persistence.LockModeType", "javax.persistence.LockModeType", "org.hibernate.LockMode")
                            .contains(argument.calculateResolvedType().describe()));
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    private boolean isRead(MethodCallExpr call) {
        String name = call.getNameAsString();
        return READ_PREFIXES.stream().anyMatch(name::startsWith) && !call.getScope().map(Object::toString)
                .orElse("").toLowerCase().matches(".*(client|http|webclient).*");
    }

    private boolean isWrite(MethodCallExpr call) {
        String name = call.getNameAsString();
        return WRITE_NAMES.contains(name) || name.startsWith("update");
    }

    private VariableDeclarator assignedVariable(MethodDeclaration method, MethodCallExpr read) {
        return method.findAll(VariableDeclarator.class).stream()
                .filter(variable -> variable.getInitializer().filter(initializer -> initializer == read
                        || initializer.findAll(MethodCallExpr.class).contains(read)).isPresent())
                .findFirst().orElse(null);
    }

    private List<Node> mutationsBasedOn(MethodDeclaration method, String entityName) {
        List<Node> result = new ArrayList<>();
        method.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().startsWith("set") && !call.getArguments().isEmpty())
                .filter(call -> call.getArguments().stream().anyMatch(argument -> argument.findAll(MethodCallExpr.class)
                        .stream().anyMatch(nested -> nested.getScope().map(Object::toString).orElse("").equals(entityName))))
                .filter(call -> call.getScope().map(Object::toString).orElse("").equals(entityName))
                .forEach(result::add);
        for (AssignExpr assignment : method.findAll(AssignExpr.class)) {
            String target = assignment.getTarget().toString();
            if (target.startsWith(entityName + ".") && assignment.getValue().toString().contains(target)) result.add(assignment);
        }
        return result;
    }

    private boolean isAfter(Node candidate, Node preceding) {
        var candidatePosition = candidate.getBegin().orElse(null);
        var precedingPosition = preceding.getBegin().orElse(null);
        if (candidatePosition == null || precedingPosition == null) return false;
        return candidatePosition.line > precedingPosition.line
                || candidatePosition.line == precedingPosition.line
                        && candidatePosition.column > precedingPosition.column;
    }

    private String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
