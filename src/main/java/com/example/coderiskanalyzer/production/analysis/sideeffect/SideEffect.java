package com.example.coderiskanalyzer.production.analysis.sideeffect;

import com.example.coderiskanalyzer.production.model.SourceLocation;

public record SideEffect(SideEffectType type, String method, String receiver, SourceLocation location) {
}
