package com.example.coderiskanalyzer.production.analysis.sideeffect;

public enum SideEffectType {
    DATABASE_WRITE,
    DATABASE_DELETE,
    EXTERNAL_HTTP_CALL,
    MESSAGE_PUBLISH,
    FILE_WRITE,
    PROCESS_EXECUTION,
    UNKNOWN_EXTERNAL_CALL
}
