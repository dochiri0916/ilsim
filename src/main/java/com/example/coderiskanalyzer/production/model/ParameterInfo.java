package com.example.coderiskanalyzer.production.model;

import java.util.Set;

public record ParameterInfo(String name, String type, Set<String> annotations, SourceLocation location) {
    public ParameterInfo { annotations = Set.copyOf(annotations); }
}
