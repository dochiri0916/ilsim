package com.example.coderiskanalyzer.production.rule.concurrency;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

final class DatabaseUniquenessProtection {
    private static final Set<String> INSERTS = Set.of("save", "saveAll", "insert", "persist");
    private static final Set<String> REPOSITORIES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.ListCrudRepository",
            "org.springframework.data.jpa.repository.JpaRepository");

    private final Map<String, ClassOrInterfaceDeclaration> sourceTypes = new HashMap<>();

    DatabaseUniquenessProtection(AnalysisContext context) {
        Set<String> ambiguous = new HashSet<>();
        for (var source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (var type : source.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(name -> {
                    if (sourceTypes.putIfAbsent(name, type) != null) ambiguous.add(name);
                });
            }
        }
        ambiguous.forEach(sourceTypes::remove);
    }

    boolean protects(MethodCallExpr check, MethodCallExpr write) {
        if (!INSERTS.contains(write.getNameAsString())) return false;
        try {
            Optional<ClassOrInterfaceDeclaration> checkedEntity = entity(check);
            Optional<ClassOrInterfaceDeclaration> writtenEntity = entity(write);
            if (checkedEntity.isEmpty() || writtenEntity.isEmpty()
                    || checkedEntity.get().getFullyQualifiedName().isEmpty()
                    || !checkedEntity.get().getFullyQualifiedName().equals(writtenEntity.get().getFullyQualifiedName())) {
                return false;
            }
            ClassOrInterfaceDeclaration entity = checkedEntity.get();
            if (entity.getAnnotations().stream().noneMatch(annotation -> named(annotation, "Entity"))) return false;
            Set<String> properties = checkedProperties(check);
            if (properties.isEmpty()) return false;
            Map<String, String> columns = new HashMap<>();
            Set<String> ids = new HashSet<>();
            Set<Set<String>> constraints = new HashSet<>();
            for (var field : entity.getFields()) {
                for (var variable : field.getVariables()) {
                    String property = variable.getNameAsString();
                    String column = property;
                    for (AnnotationExpr annotation : field.getAnnotations()) {
                        if (named(annotation, "Id")) ids.add(property);
                        if (named(annotation, "Column")) {
                            Optional<Expression> name = member(annotation, "name");
                            if (name.filter(Expression::isStringLiteralExpr).isPresent()) {
                                column = name.get().asStringLiteralExpr().asString();
                            }
                            if (member(annotation, "unique").filter(Expression::isBooleanLiteralExpr)
                                    .map(value -> value.asBooleanLiteralExpr().getValue()).orElse(false)) {
                                constraints.add(Set.of(property));
                            }
                        }
                    }
                    if (columns.containsKey(column)) return false;
                    columns.put(column, property);
                }
            }
            if (!columns.values().containsAll(properties)) return false;
            if (!ids.isEmpty()) constraints.add(ids);
            for (AnnotationExpr table : entity.getAnnotations()) {
                if (!named(table, "Table")) continue;
                Optional<Expression> unique = member(table, "uniqueConstraints");
                if (unique.isEmpty()) continue;
                for (Expression value : values(unique.get())) {
                    if (!(value instanceof AnnotationExpr annotation) || !named(annotation, "UniqueConstraint")) continue;
                    Optional<Expression> names = member(annotation, "columnNames");
                    if (names.isEmpty()) continue;
                    Set<String> constraint = new HashSet<>();
                    boolean known = true;
                    for (Expression name : values(names.get())) {
                        if (!name.isStringLiteralExpr()) { known = false; break; }
                        String property = columns.get(name.asStringLiteralExpr().asString());
                        if (property == null) { known = false; break; }
                        constraint.add(property);
                    }
                    if (known && !constraint.isEmpty()) constraints.add(constraint);
                }
            }
            return constraints.stream().anyMatch(properties::containsAll);
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    private static Set<String> checkedProperties(MethodCallExpr check) {
        String name = check.getNameAsString();
        String prefix = List.of("existsBy", "findBy", "countBy", "selectBy").stream()
                .filter(name::startsWith).findFirst().orElse(null);
        if (prefix == null) return Set.of();
        String[] parts = name.substring(prefix.length()).split("And(?=[A-Z])", -1);
        if (parts.length != check.getArguments().size()) return Set.of();
        Set<String> properties = new HashSet<>();
        for (String part : parts) {
            if (part.isEmpty()) return Set.of();
            properties.add(Character.toLowerCase(part.charAt(0)) + part.substring(1));
        }
        return properties;
    }

    private Optional<ClassOrInterfaceDeclaration> entity(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return Optional.empty();
        var type = call.getScope().get().calculateResolvedType();
        if (!type.isReferenceType()) return Optional.empty();
        var declaration = type.asReferenceType().getTypeDeclaration().flatMap(value -> value.toAst());
        if (declaration.isEmpty() || !(declaration.get() instanceof ClassOrInterfaceDeclaration repository)) {
            return Optional.empty();
        }
        repository = canonical(repository).orElse(null);
        if (repository == null) return Optional.empty();
        Map<String, ClassOrInterfaceDeclaration> parameters = new HashMap<>();
        var arguments = type.asReferenceType().typeParametersValues();
        for (int i = 0; i < Math.min(arguments.size(), repository.getTypeParameters().size()); i++) {
            var argument = arguments.get(i);
            String parameter = repository.getTypeParameters().get(i).getNameAsString();
            if (argument.isReferenceType()) {
                argument.asReferenceType().getTypeDeclaration().flatMap(value -> value.toAst())
                        .filter(ClassOrInterfaceDeclaration.class::isInstance)
                        .map(ClassOrInterfaceDeclaration.class::cast).flatMap(this::canonical)
                        .ifPresent(value -> parameters.put(parameter, value));
            }
        }
        return repositoryEntity(repository, parameters, new HashSet<>());
    }

    private Optional<ClassOrInterfaceDeclaration> repositoryEntity(ClassOrInterfaceDeclaration repository,
            Map<String, ClassOrInterfaceDeclaration> parameters, Set<String> visited) {
        if (!visited.add(repository.getFullyQualifiedName().orElse(repository.getNameAsString()))) return Optional.empty();
        List<ClassOrInterfaceType> parents = new ArrayList<>(repository.getExtendedTypes());
        parents.addAll(repository.getImplementedTypes());
        for (ClassOrInterfaceType parent : parents) {
            List<Type> arguments = parent.getTypeArguments().map(List::copyOf).orElse(List.of());
            if (isSpringRepository(parent) && !arguments.isEmpty()) {
                return sourceType(arguments.getFirst(), parameters);
            }
            var resolved = sourceType(parent, parameters);
            if (resolved.isEmpty()) continue;
            Map<String, ClassOrInterfaceDeclaration> inherited = new HashMap<>();
            for (int i = 0; i < Math.min(arguments.size(), resolved.get().getTypeParameters().size()); i++) {
                String parameter = resolved.get().getTypeParameters().get(i).getNameAsString();
                sourceType(arguments.get(i), parameters).ifPresent(value -> inherited.put(parameter, value));
            }
            var entity = repositoryEntity(resolved.get(), inherited, visited);
            if (entity.isPresent()) return entity;
        }
        return Optional.empty();
    }

    private Optional<ClassOrInterfaceDeclaration> sourceType(Type type,
            Map<String, ClassOrInterfaceDeclaration> parameters) {
        if (parameters.containsKey(type.asString())) return Optional.of(parameters.get(type.asString()));
        try {
            var resolved = type.resolve();
            if (!resolved.isReferenceType()) return Optional.empty();
            return resolved.asReferenceType().getTypeDeclaration().flatMap(value -> value.toAst())
                    .filter(ClassOrInterfaceDeclaration.class::isInstance).map(ClassOrInterfaceDeclaration.class::cast)
                    .flatMap(this::canonical);
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    private Optional<ClassOrInterfaceDeclaration> canonical(ClassOrInterfaceDeclaration declaration) {
        return declaration.getFullyQualifiedName().map(sourceTypes::get);
    }

    private static boolean isSpringRepository(ClassOrInterfaceType type) {
        if (REPOSITORIES.contains(type.getNameWithScope())) return true;
        if (type.getScope().isPresent()) return false;
        return type.findCompilationUnit().stream().flatMap(unit -> unit.getImports().stream())
                .filter(value -> !value.isStatic())
                .anyMatch(value -> (!value.isAsterisk() && REPOSITORIES.contains(value.getNameAsString())
                        && value.getNameAsString().endsWith("." + type.getNameAsString()))
                        || (value.isAsterisk() && REPOSITORIES.contains(value.getNameAsString() + "." + type.getNameAsString())));
    }

    private static boolean named(AnnotationExpr annotation, String name) {
        return annotation.getName().getIdentifier().equals(name);
    }

    private static Optional<Expression> member(AnnotationExpr annotation, String name) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) return Optional.empty();
        return normal.getPairs().stream().filter(pair -> pair.getNameAsString().equals(name))
                .map(pair -> pair.getValue()).findFirst();
    }

    private static List<Expression> values(Expression expression) {
        return expression.isArrayInitializerExpr() ? List.copyOf(expression.asArrayInitializerExpr().getValues())
                : List.of(expression);
    }
}
