package com.example.coderiskanalyzer.production.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import org.springframework.stereotype.Component;

@Component
public class ProgramModelBuilder {

    public ProjectModel build(List<ParsedSource> sources) {
        List<ClassInfo> classes = new ArrayList<>();
        for (ParsedSource source : sources) {
            if (source.compilationUnit() != null) {
                appendClasses(source.sourceFile().path(), source.compilationUnit(), classes);
            }
        }
        return new ProjectModel(classes);
    }

    private void appendClasses(java.nio.file.Path path, CompilationUnit unit, List<ClassInfo> result) {
        String packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            List<FieldInfo> fields = new ArrayList<>();
            List<MethodInfo> methods = new ArrayList<>();
            for (var member : type.getMembers()) {
                if (member instanceof FieldDeclaration field) {
                    for (var variable : field.getVariables()) {
                        fields.add(new FieldInfo(variable.getNameAsString(), variable.getTypeAsString(),
                                modifiers(field), location(path, variable), annotations(field)));
                    }
                } else if (member instanceof MethodDeclaration method) {
                    methods.add(toMethodInfo(path, type.getNameAsString(), method));
                }
            }
            String qualifiedName = packageName.isBlank() ? type.getNameAsString()
                    : packageName + "." + type.getNameAsString();
            result.add(new ClassInfo(qualifiedName, packageName, type.getNameAsString(),
                    annotations(type), fields, methods, location(path, type), interfaces(type), superClass(type),
                    type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface(),
                    type instanceof EnumDeclaration, type instanceof RecordDeclaration));
        }
    }

    private MethodInfo toMethodInfo(java.nio.file.Path path, String owner, MethodDeclaration method) {
        List<MethodCallExpr> callNodes = method.findAll(MethodCallExpr.class);
        List<String> calls = callNodes.stream().map(MethodCallExpr::getNameAsString).toList();
        List<MethodCallInfo> callInfos = callNodes.stream().map(call -> new MethodCallInfo(call.getNameAsString(),
                call.getScope().map(Object::toString).orElse(""),
                call.getArguments().stream().map(Object::toString).toList(), location(path, call))).toList();
        List<ParameterInfo> parameters = method.getParameters().stream().map(parameter -> new ParameterInfo(
                parameter.getNameAsString(), parameter.getTypeAsString(), annotations(parameter),
                location(path, parameter))).toList();
        List<String> fieldAccesses = method.findAll(FieldAccessExpr.class).stream()
                .map(FieldAccessExpr::toString).toList();
        return new MethodInfo(owner, method.getNameAsString(), method.getTypeAsString(),
                parameters.stream().map(ParameterInfo::type).toList(), annotations(method), modifiers(method), calls,
                location(path, method), parameters, method.getThrownExceptions().stream().map(Object::toString).toList(),
                fieldAccesses, method.getBody().map(Object::toString).orElse(""), callInfos);
    }

    private List<String> interfaces(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.getImplementedTypes().stream().map(Object::toString).toList();
        }
        if (type instanceof EnumDeclaration declaration) {
            return declaration.getImplementedTypes().stream().map(Object::toString).toList();
        }
        if (type instanceof RecordDeclaration declaration) {
            return declaration.getImplementedTypes().stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private String superClass(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration && !declaration.isInterface()) {
            return declaration.getExtendedTypes().getFirst().map(Object::toString).orElse("");
        }
        return "";
    }

    private Set<String> annotations(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().map(annotation -> annotation.getNameAsString()).collect(Collectors.toSet());
    }

    private Set<String> modifiers(NodeWithModifiers<?> node) {
        return node.getModifiers().stream().map(modifier -> modifier.getKeyword().asString()).collect(Collectors.toSet());
    }

    private SourceLocation location(java.nio.file.Path path, com.github.javaparser.ast.Node node) {
        var range = node.getRange();
        if (range.isEmpty()) {
            return new SourceLocation(path, SourceLocation.UNKNOWN, SourceLocation.UNKNOWN,
                    SourceLocation.UNKNOWN, SourceLocation.UNKNOWN);
        }
        return new SourceLocation(path, range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
