package com.example.coderiskanalyzer.production.parser;

import java.util.List;

import com.example.coderiskanalyzer.production.source.SourceFile;
import com.github.javaparser.ast.CompilationUnit;

public record ParsedSource(SourceFile sourceFile, CompilationUnit compilationUnit, List<String> errors) {
    public ParsedSource {
        errors = List.copyOf(errors);
    }

    public boolean parsedSuccessfully() {
        return compilationUnit != null && errors.isEmpty();
    }
}
