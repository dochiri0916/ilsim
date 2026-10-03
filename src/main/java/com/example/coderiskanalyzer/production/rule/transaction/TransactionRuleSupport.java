package com.example.coderiskanalyzer.production.rule.transaction;

import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

final class TransactionRuleSupport {

    private TransactionRuleSupport() {}

    static boolean hasAnnotation(MethodDeclaration method, String annotationName) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> simpleName(annotation.getNameAsString()).equals(annotationName));
    }

    static boolean hasAnnotation(TypeDeclaration<?> type, String annotationName) {
        return type.getAnnotations().stream()
                .anyMatch(annotation -> simpleName(annotation.getNameAsString()).equals(annotationName));
    }

    static boolean isTransactional(MethodDeclaration method, TypeDeclaration<?> owner) {
        return hasAnnotation(method, "Transactional") || hasAnnotation(owner, "Transactional");
    }

    static TypeDeclaration<?> enclosingType(MethodDeclaration method) {
        Node current = method.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) return type;
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    static SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
