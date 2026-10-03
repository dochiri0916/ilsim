package com.example.coderiskanalyzer.production.rule.security;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;

/**
 * Flags data access on tenant-scoped entities that does not visibly restrict results to a tenant. An entity is
 * tenant-scoped when it declares a tenant field; Hibernate tenant identifiers and filters are trusted as protection.
 */
@Component
public class TenantIsolationRule implements RiskRule {

    private static final Set<String> TENANT_FIELDS = Set.of("tenantId", "tenant", "tenantCode", "tenantKey");
    private static final Set<String> FRAMEWORK_FILTERS = Set.of("TenantId", "Filter", "Filters", "FilterDef", "Where", "SQLRestriction");
    private static final Set<String> QUERY_PREFIXES = Set.of("find", "read", "get", "query", "search", "stream",
            "count", "exists", "delete", "remove");
    private static final Set<String> INHERITED_LOOKUPS = Set.of("findById", "findAll", "findAllById", "getById",
            "getReferenceById", "getOne", "existsById", "deleteById", "deleteAll", "deleteAllById", "count");

    @Override public String id() { return "SEC-007"; }
    @Override public RiskCategory category() { return RiskCategory.SECURITY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        Set<String> tenantEntities = new HashSet<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (ClassOrInterfaceDeclaration type : source.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (tenantScoped(type)) tenantEntities.add(type.getNameAsString());
            }
        }
        Map<String, String> repositories = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (ClassOrInterfaceDeclaration type : source.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
                String entity = repositoryEntity(type);
                if (entity == null || !tenantEntities.contains(entity)) continue;
                repositories.put(type.getNameAsString(), entity);
                for (MethodDeclaration method : type.getMethods()) {
                    if (method.getBody().isPresent() || !isQuery(method) || filtersTenant(method)) continue;
                    findings.add(finding(source, method, type.getNameAsString(), method.getNameAsString(), entity,
                            "Repository query on a tenant-scoped entity has no visible tenant condition"));
                }
            }
        }
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (ClassOrInterfaceDeclaration type : source.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
                Map<String, String> fields = new HashMap<>();
                for (FieldDeclaration field : type.getFields()) {
                    String repository = field.getElementType().asString();
                    if (!repositories.containsKey(repository)) continue;
                    field.getVariables().forEach(variable -> fields.put(variable.getNameAsString(), repository));
                }
                if (fields.isEmpty()) continue;
                for (MethodCallExpr call : type.findAll(MethodCallExpr.class)) {
                    String receiver = call.getScope().map(scope -> scope instanceof NameExpr name ? name.getNameAsString()
                            : scope instanceof FieldAccessExpr access ? access.getNameAsString() : null).orElse(null);
                    if (receiver == null || !fields.containsKey(receiver) || !INHERITED_LOOKUPS.contains(call.getNameAsString())) continue;
                    String repository = fields.get(receiver);
                    findings.add(finding(source, call, repository, call.getNameAsString(), repositories.get(repository),
                            "Inherited repository lookup on a tenant-scoped entity bypasses tenant filtering"));
                }
            }
        }
        return findings;
    }

    private static boolean tenantScoped(ClassOrInterfaceDeclaration type) {
        if (type.getAnnotations().stream().noneMatch(a -> a.getName().getIdentifier().equals("Entity"))) return false;
        if (type.getAnnotations().stream().anyMatch(a -> FRAMEWORK_FILTERS.contains(a.getName().getIdentifier()))) return false;
        boolean tenantField = false;
        for (FieldDeclaration field : type.getFields()) {
            boolean named = field.getVariables().stream().anyMatch(v -> TENANT_FIELDS.contains(v.getNameAsString()));
            if (!named) continue;
            if (field.getAnnotations().stream().anyMatch(a -> FRAMEWORK_FILTERS.contains(a.getName().getIdentifier()))) return false;
            tenantField = true;
        }
        return tenantField;
    }

    private static String repositoryEntity(ClassOrInterfaceDeclaration type) {
        if (!type.isInterface()) return null;
        for (ClassOrInterfaceType parent : type.getExtendedTypes()) {
            if (!parent.getNameAsString().endsWith("Repository")) continue;
            var arguments = parent.getTypeArguments();
            if (arguments.isPresent() && !arguments.get().isEmpty()) return arguments.get().get(0).asString();
        }
        return null;
    }

    private static boolean isQuery(MethodDeclaration method) {
        String name = method.getNameAsString();
        return method.getAnnotationByName("Query").isPresent()
                || QUERY_PREFIXES.stream().anyMatch(prefix -> name.startsWith(prefix) && name.length() > prefix.length());
    }

    private static boolean filtersTenant(MethodDeclaration method) {
        var query = method.getAnnotationByName("Query");
        if (query.isPresent()) {
            String text = query.get() instanceof SingleMemberAnnotationExpr single && single.getMemberValue() instanceof StringLiteralExpr literal
                    ? literal.getValue()
                    : query.get() instanceof NormalAnnotationExpr normal ? normal.getPairs().stream()
                            .filter(pair -> pair.getNameAsString().equals("value")).map(pair -> pair.getValue().toString())
                            .findFirst().orElse("") : "";
            return text.toLowerCase(Locale.ROOT).contains("tenant");
        }
        return method.getNameAsString().contains("Tenant");
    }

    private Finding finding(ParsedSource source, Node node, String repository, String method, String entity, String message) {
        SourceLocation location = location(source, node);
        Evidence evidence = new Evidence("TENANT_FILTER_NOT_VISIBLE", message, location,
                Map.of("repository", repository, "method", method, "entity", entity));
        return new Finding(id(), category(), Severity.HIGH, Confidence.LOW, "Tenant isolation not visible",
                message + "; confirm the query restricts rows to the caller's tenant.", location, List.of(evidence),
                FindingStatus.REVIEW);
    }

    private static SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
