package com.example.coderiskanalyzer.production.model;

import java.util.Set;

public record FieldInfo(String name, String type, Set<String> modifiers, SourceLocation location,
                        Set<String> annotations) {
    public FieldInfo(String name, String type, Set<String> modifiers, SourceLocation location) {
        this(name, type, modifiers, location, Set.of());
    }

    public FieldInfo {
        modifiers = Set.copyOf(modifiers);
        annotations = Set.copyOf(annotations);
    }
}
