package com.example.coderiskanalyzer.production.finding;

import java.util.Map;
import java.util.Collections;
import java.util.TreeMap;

import com.example.coderiskanalyzer.production.model.SourceLocation;

public record Evidence(String type, String message, SourceLocation location, Map<String, Object> attributes) {
    public Evidence {
        attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
    }
}
