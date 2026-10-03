package com.example.coderiskanalyzer.production.model;

import java.nio.file.Path;

public record SourceLocation(Path file, int startLine, int startColumn, int endLine, int endColumn) {
    public static final int UNKNOWN = -1;
}
