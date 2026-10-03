package com.example.coderiskanalyzer.production.source;

public class SourceDiscoveryException extends RuntimeException {

    public SourceDiscoveryException(String message) {
        super(message);
    }

    public SourceDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
