package com.example.coderiskanalyzer.requirements;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/** Validates before any project discovery, execution, or remote review. */
public final class RequirementsInputValidator {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Schema schema;

    public RequirementsInputValidator() {
        try (var stream = getClass().getResourceAsStream("/requirements/requirements-analysis.schema.json")) {
            if (stream == null) throw new IllegalStateException("Packaged requirements schema is missing");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(mapper.readTree(stream));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not load requirements schema", exception);
        }
    }

    public ObjectNode validate(String json) {
        var node = parse(json);
        var errors = schema.validate(node.toString(), InputFormat.JSON);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Requirements input violates JSON Schema ("
                + errors.size() + " errors); check required fields, mode and permitted evidence");
        return node;
    }

    public ObjectNode parse(String json) {
        try {
            var value = mapper.readTree(json);
            if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("Expected a JSON object");
            return object;
        } catch (java.io.IOException exception) {
            // Parser messages may contain submitted secrets or arbitrary source snippets.
            throw new IllegalArgumentException("Invalid JSON: malformed input, duplicate keys or trailing content");
        }
    }

    public ObjectMapper mapper() { return mapper; }
}
