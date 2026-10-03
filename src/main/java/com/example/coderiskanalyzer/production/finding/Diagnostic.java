package com.example.coderiskanalyzer.production.finding;

import java.nio.file.Path;

public record Diagnostic(DiagnosticSeverity severity, String code, String message, Path file) {}
