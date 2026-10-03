package com.example.coderiskanalyzer.production.model;

import java.util.List;

public record MethodCallInfo(String name, String scope, List<String> arguments, SourceLocation location) {
    public MethodCallInfo { arguments = List.copyOf(arguments); }
}
