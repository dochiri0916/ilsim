package com.example.coderiskanalyzer.requirements;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequirementsAnalysisSchemaTests {
    private static final Path DIRECTORY = Path.of("docs/requirements-analysis");
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @ParameterizedTest
    @ValueSource(strings = {"order-inventory.analysis.json", "order-inventory.conflict.analysis.json", "order-inventory.code-review.analysis.json"})
    void acceptsDocumentedExamplesIncludingExplicitSemanticConflict(String filename) throws Exception {
        assertValid(example(filename));
    }

    @Test
    void keepsUnimplementedBusinessAndUnverifiedExecutionExplicit() throws Exception {
        var requirement = requirement(example("order-inventory.analysis.json"));
        assertThat(example("order-inventory.analysis.json").path("mode").asText()).isEqualTo("SPEC_ONLY");
        assertThat(requirement.has("mappings")).isFalse();
        assertThat(requirement.path("review").path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(requirement.path("scenarios").properties()).extracting(entry -> entry.getValue().path("type").asText())
                .contains("NORMAL", "DUPLICATE", "CONCURRENCY", "ROLLBACK");
        assertThat(requirement.path("scenarios").properties()).allSatisfy(entry ->
                assertThat(entry.getValue().path("testTypes")).isNotEmpty());
    }

    @Test
    void representsContradictionAsReviewDataRatherThanPretendingSchemaProvesBusinessCorrectness() throws Exception {
        var requirement = requirement(example("order-inventory.conflict.analysis.json"));
        assertThat(requirement.path("review").path("status").asText()).isEqualTo("CONTRADICTED");
        assertThat(requirement.path("review").path("evidence")).hasSize(2);
        assertThat(requirement.path("review").path("openQuestions")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "domain", "preconditions", "postconditions", "invariants", "coreLogic",
            "transaction", "concurrency", "idempotency", "consistency", "scenarios", "review"})
    void rejectsMissingMandatoryAnalysisPerspective(String field) throws Exception {
        var example = example("order-inventory.analysis.json");
        requirement(example).remove(field);
        assertInvalid(example);
    }

    @ParameterizedTest
    @ValueSource(strings = {"transaction", "concurrency", "idempotency", "consistency"})
    void requiresConcreteExpectationsForApplicablePerspective(String field) throws Exception {
        var example = example("order-inventory.analysis.json");
        ((ObjectNode) requirement(example).get(field)).putArray("expectations");
        assertInvalid(example);
    }

    @Test
    void requiresReasonWhenPerspectiveDoesNotApply() throws Exception {
        var example = example("order-inventory.analysis.json");
        var transaction = (ObjectNode) requirement(example).get("transaction");
        transaction.put("applicability", "NOT_APPLICABLE");
        transaction.putArray("expectations");
        transaction.put("reason", "No atomic writes are required in this alternative contract.");
        assertValid(example);
        transaction.put("reason", "   ");
        assertInvalid(example);
    }

    @Test
    void requiresOpenQuestionWhenEvidenceIsInsufficient() throws Exception {
        var example = example("order-inventory.analysis.json");
        ((ObjectNode) requirement(example).get("review")).putArray("openQuestions");
        assertInvalid(example);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVIDENCE_SUPPORTED", "CONTRADICTED"})
    void rejectsEvidenceFreeReviewDecision(String status) throws Exception {
        var example = example("order-inventory.analysis.json");
        var review = (ObjectNode) requirement(example).get("review");
        review.put("status", status);
        review.putArray("evidence");
        assertInvalid(example);
    }

    @Test
    void requiresCodeMappingForImplementedRequirement() throws Exception {
        var example = example("order-inventory.code-review.analysis.json");
        ((ObjectNode) requirement(example).get("mappings")).put("implementationStatus", "IMPLEMENTED");
        assertInvalid(example);
    }

    @Test
    void rejectsScenarioWithoutExpectedOutcomeOrTestLevel() throws Exception {
        var example = example("order-inventory.analysis.json");
        var scenario = (ObjectNode) requirement(example).path("scenarios").path("SCN-NORMAL");
        scenario.putArray("expectedOutcomes");
        assertInvalid(example);
        scenario.putArray("expectedOutcomes").add("An order is persisted.");
        scenario.putArray("testTypes");
        assertInvalid(example);
    }

    @Test
    void rejectsUnsupportedVersionUnknownFieldsAndUnknownStatus() throws Exception {
        var example = example("order-inventory.analysis.json");
        example.put("version", 2);
        assertInvalid(example);
        example.put("version", 1);
        requirement(example).put("transactoin", "typo");
        assertInvalid(example);
        requirement(example).remove("transactoin");
        ((ObjectNode) requirement(example).get("review")).put("status", "SAFE");
        assertInvalid(example);
    }

    @Test
    void rejectsEmptyRequirementCollectionAndBlankInvariant() throws Exception {
        var example = example("order-inventory.analysis.json");
        requirement(example).putArray("invariants").add(" ");
        assertInvalid(example);
        example.putObject("requirements");
        assertInvalid(example);
    }

    @Test
    void rejectsCodeMappingsInSpecificationOnlyMode() throws Exception {
        var example = example("order-inventory.code-review.analysis.json");
        example.put("mode", "SPEC_ONLY");
        assertInvalid(example);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CODE", "STATIC_FINDING", "TEST", "EXECUTION"})
    void rejectsCodeOrExecutionEvidenceInSpecificationOnlyMode(String kind) throws Exception {
        var example = example("order-inventory.analysis.json");
        var evidence = ((ObjectNode) requirement(example).get("review")).putArray("evidence").addObject();
        evidence.put("kind", kind).put("path", "sample.java").put("locator", "method")
                .put("observation", "This must not be a specification-only claim.");
        assertInvalid(example);
    }

    @Test
    void requiresExplicitModeAndCodeRevisionForCodeReview() throws Exception {
        var example = example("order-inventory.analysis.json");
        example.remove("mode");
        assertInvalid(example);
        example = example("order-inventory.code-review.analysis.json");
        example.remove("codeRevision");
        assertInvalid(example);
        example = example("order-inventory.code-review.analysis.json");
        requirement(example).remove("mappings");
        assertInvalid(example);
    }

    @Test
    void acceptsCodeChangeModeWithoutTreatingItAsSpecificationOnly() throws Exception {
        var example = example("order-inventory.code-review.analysis.json");
        example.put("mode", "CODE_CHANGE");
        assertValid(example);
    }

    @Test
    void unknownPerspectiveRequiresAQuestionEvenWhenReviewHasOtherEvidence() throws Exception {
        var example = example("order-inventory.conflict.analysis.json");
        ((ObjectNode) requirement(example).get("transaction")).put("applicability", "UNKNOWN");
        assertValid(example);
        ((ObjectNode) requirement(example).get("review")).putArray("openQuestions");
        assertInvalid(example);
    }

    private ObjectNode example(String filename) throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(DIRECTORY.resolve(filename)));
    }

    private ObjectNode requirement(ObjectNode example) {
        return (ObjectNode) example.path("requirements").path("REQ-ORDER-PLACE");
    }

    private void assertValid(ObjectNode example) throws Exception {
        assertThat(validate(example)).isEmpty();
    }

    private void assertInvalid(ObjectNode example) throws Exception {
        assertThat(validate(example)).isNotEmpty();
    }

    private java.util.List<com.networknt.schema.Error> validate(ObjectNode example) throws Exception {
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        var schema = registry.getSchema(mapper.readTree(
                Files.readString(DIRECTORY.resolve("requirements-analysis.schema.json"))));
        return schema.validate(example.toString(), InputFormat.JSON);
    }
}
