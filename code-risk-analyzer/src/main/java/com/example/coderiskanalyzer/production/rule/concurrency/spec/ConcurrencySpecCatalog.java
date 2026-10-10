package com.example.coderiskanalyzer.production.rule.concurrency.spec;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import com.example.coderiskanalyzer.production.finding.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Implementation ledger and conditional links to existing rules; it does not execute extra detectors. */
@Component
public final class ConcurrencySpecCatalog {
    private static final String RESOURCE = "/concurrency/specification.json";
    private final Catalog catalog;

    public ConcurrencySpecCatalog() {
        try (var input = ConcurrencySpecCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Concurrency specification catalog is missing");
            catalog = new ObjectMapper().readValue(input, Catalog.class);
            validate(catalog);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read concurrency specification catalog", exception);
        }
    }

    public Catalog catalog() { return catalog; }

    public List<Requirement> all() {
        return catalog.rules().stream().sorted(Comparator.comparing(Requirement::priority)
                .thenComparing(Requirement::id)).toList();
    }

    public Requirement find(String id) {
        if (id == null) return null;
        return catalog.rules().stream().filter(rule -> rule.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    public List<Requirement> forAnalyzerRule(String id) {
        if (id == null) return List.of();
        return all().stream().filter(rule -> rule.bindings().stream()
                .anyMatch(binding -> binding.ruleId().equalsIgnoreCase(id))).toList();
    }

    public Progress progress() {
        Map<String, Integer> counts = new TreeMap<>();
        for (var status : ImplementationStatus.values()) counts.put(status.name(), 0);
        all().forEach(rule -> counts.merge(rule.implementationStatus().name(), 1, Integer::sum));
        return new Progress(catalog.schemaVersion(), catalog.specification(), "IMPLEMENTATION_PROGRESS", counts,
                all().stream().map(rule -> new RuleProgress(rule.id(), rule.priority(), rule.implementationStatus())).toList());
    }

    /** Multiple specification IDs annotate one existing finding; conditional links require actual evidence. */
    public FindingMapping forFinding(Finding finding) {
        var matches = all().stream().filter(rule -> rule.bindings().stream()
                .anyMatch(binding -> binding.matches(finding))).toList();
        if (matches.isEmpty()) return null;
        Requirement primary = matches.stream().filter(rule -> rule.id().equals("DB-CONC-009"))
                .findFirst().orElse(matches.getFirst());
        Binding binding = primary.bindings().stream().filter(item -> item.matches(finding)).findFirst().orElseThrow();
        ConcurrencyAssessment assessment = finding.concurrencyAssessment();
        if (assessment != null && matches.stream().noneMatch(rule -> rule.id().equals(assessment.specificationRuleId())))
            throw new IllegalArgumentException("Assessment specification does not match the rule binding");
        List<String> limitations = matches.stream().flatMap(rule -> rule.remainingWork().stream()).distinct().toList();
        return new FindingMapping(matches.stream().map(Requirement::id).toList(), primary.id(),
                assessment == null ? binding.outcome() : assessment.outcome(),
                primary.priority(), primary.implementationStatus(), limitations,
                matches.stream().map(Requirement::recommendedTest).distinct().toList(),
                assessment == null ? List.of() : assessment.assumptions(),
                assessment == null ? List.of() : assessment.missingEvidence(),
                assessment == null ? List.of() : assessment.safeguards());
    }

    private static void validate(Catalog catalog) {
        if (catalog.schemaVersion() != 1 || catalog.specification().isBlank())
            throw new IllegalArgumentException("Unsupported concurrency specification catalog");
        var expected = IntStream.rangeClosed(1, 22).mapToObj(index -> {
            String prefix = index <= 8 ? "JAVA" : index <= 16 ? "DB" : index == 17 ? "SPRING"
                    : index <= 19 ? "JPA" : "DIST";
            return "%s-CONC-%03d".formatted(prefix, index);
        }).collect(java.util.stream.Collectors.toSet());
        var actual = catalog.rules().stream().map(Requirement::id).collect(java.util.stream.Collectors.toSet());
        if (catalog.rules().size() != 22 || !actual.equals(expected))
            throw new IllegalArgumentException("Concurrency catalog must contain each of the 22 specification IDs once");
        for (var rule : catalog.rules()) {
            boolean planned = rule.implementationStatus() == ImplementationStatus.PLANNED;
            if (rule.title().isBlank() || rule.recommendedTest().isBlank() || rule.priority() == ImplementationPriority.P0
                    || rule.remainingWork().isEmpty() && rule.implementationStatus() == ImplementationStatus.PARTIAL
                    || planned != rule.bindings().isEmpty()
                    || !planned && (rule.implementationEvidence().isEmpty() || rule.testEvidence().isEmpty()))
                throw new IllegalArgumentException("Incomplete concurrency implementation evidence: " + rule.id());
            if (rule.bindings().stream().map(Binding::ruleId).distinct().count() != rule.bindings().size())
                throw new IllegalArgumentException("Duplicate analyzer bindings: " + rule.id());
        }
    }

    public record Catalog(int schemaVersion, String specification, String scope,
                          List<WorkItem> workItems, List<Requirement> rules) {
        public Catalog { workItems = List.copyOf(workItems); rules = List.copyOf(rules); }
    }

    public record WorkItem(String id, ImplementationPriority priority, ImplementationStatus implementationStatus,
                           String scope, List<String> remainingWork) {
        public WorkItem { remainingWork = List.copyOf(remainingWork); }
    }

    public record Requirement(String id, String title, ImplementationPriority priority,
                              ImplementationStatus implementationStatus, List<Binding> bindings,
                              List<String> implementationEvidence, List<String> testEvidence,
                              String implementedScope, List<String> remainingWork, String recommendedTest) {
        public Requirement {
            bindings = List.copyOf(bindings);
            implementationEvidence = List.copyOf(implementationEvidence);
            testEvidence = List.copyOf(testEvidence);
            remainingWork = List.copyOf(remainingWork);
        }
    }

    public record Binding(String ruleId, List<String> requiredEvidenceTypes, ConcurrencyOutcome outcome) {
        public Binding { requiredEvidenceTypes = List.copyOf(requiredEvidenceTypes); }
        public boolean matches(Finding finding) {
            return ruleId.equals(finding.ruleId()) && requiredEvidenceTypes.stream()
                    .allMatch(type -> finding.evidence().stream().anyMatch(evidence -> evidence.type().equals(type)));
        }
    }

    public record Progress(int schemaVersion, String specification, String scope, Map<String, Integer> counts,
                           List<RuleProgress> rules) {
        public Progress { counts = java.util.Collections.unmodifiableMap(new TreeMap<>(counts)); rules = List.copyOf(rules); }
    }

    public record RuleProgress(String id, ImplementationPriority priority, ImplementationStatus implementationStatus) {}

    public record FindingMapping(List<String> specificationRuleIds, String primarySpecificationRuleId,
                                 ConcurrencyOutcome outcome, ImplementationPriority priority,
                                 ImplementationStatus implementationStatus, List<String> limitations,
                                 List<String> recommendedTests, List<String> assumptions,
                                 List<String> missingEvidence, List<String> safeguards) {
        public FindingMapping {
            specificationRuleIds = List.copyOf(specificationRuleIds);
            limitations = List.copyOf(limitations);
            recommendedTests = List.copyOf(recommendedTests);
            assumptions = List.copyOf(assumptions);
            missingEvidence = List.copyOf(missingEvidence);
            safeguards = List.copyOf(safeguards);
        }
    }
}
