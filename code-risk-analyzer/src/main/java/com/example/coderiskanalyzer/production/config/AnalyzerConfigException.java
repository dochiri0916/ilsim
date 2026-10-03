package com.example.coderiskanalyzer.production.config;

public class AnalyzerConfigException extends RuntimeException {
    public AnalyzerConfigException(String message) { super(message); }
    public AnalyzerConfigException(String message, Throwable cause) { super(message, cause); }
}
