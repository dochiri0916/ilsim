package com.example.coderiskanalyzer.production.model;

import java.util.List;
import java.util.Set;

public record ClassInfo(
        String fullyQualifiedName,
        String packageName,
        String simpleName,
        Set<String> annotations,
        List<FieldInfo> fields,
        List<MethodInfo> methods,
        SourceLocation location,
        List<String> interfaces,
        String superClass,
        boolean interfaceType,
        boolean enumType,
        boolean recordType) {

    public ClassInfo(String fullyQualifiedName, String packageName, String simpleName, Set<String> annotations,
                     List<FieldInfo> fields, List<MethodInfo> methods, SourceLocation location) {
        this(fullyQualifiedName, packageName, simpleName, annotations, fields, methods, location,
                List.of(), "", false, false, false);
    }

    public ClassInfo {
        annotations = Set.copyOf(annotations);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        interfaces = List.copyOf(interfaces);
        superClass = superClass == null ? "" : superClass;
    }
}
