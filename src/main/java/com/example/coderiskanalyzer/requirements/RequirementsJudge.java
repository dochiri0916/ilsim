package com.example.coderiskanalyzer.requirements;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Separate document and code questions; an answer never grants execution permissions. */
public interface RequirementsJudge extends AutoCloseable {
    ObjectNode reviewDocuments(ObjectNode documents);
    ObjectNode reviewCode(ObjectNode comparison);
    @Override default void close() {}
}
