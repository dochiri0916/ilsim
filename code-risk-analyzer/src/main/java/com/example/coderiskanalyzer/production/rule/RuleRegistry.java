package com.example.coderiskanalyzer.production.rule;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencySpecCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RuleRegistry {

    private static final Map<String, Descriptor> DESCRIPTORS = Map.ofEntries(
            Map.entry("SEC-001", new Descriptor("SQL injection", "Untrusted input reaches SQL execution without safe parameter binding.")),
            Map.entry("SEC-002", new Descriptor("Command injection", "Untrusted input reaches operating-system process execution.")),
            Map.entry("SEC-003", new Descriptor("Path traversal", "Untrusted input reaches a filesystem operation without proven containment.")),
            Map.entry("SEC-004", new Descriptor("SSRF", "Untrusted input controls an outbound request target.")),
            Map.entry("SEC-005", new Descriptor("Hardcoded secret", "A credential-like value is embedded in source code.")),
            Map.entry("SEC-006", new Descriptor("Missing object-level authorization", "A request handler loads a resource by client-supplied identifier without visible caller identity or access rule.")),
            Map.entry("SEC-007", new Descriptor("Tenant isolation not visible", "Data access on a tenant-scoped entity does not visibly restrict rows to a tenant.")),
            Map.entry("CON-001", new Descriptor("Check-then-act race", "A check and later mutation can race across concurrent executions.")),
            Map.entry("CON-002", new Descriptor("Lost update risk", "A value is modified from previously read state without visible concurrency protection.")),
            Map.entry("CON-003", new Descriptor("Shared mutable singleton state", "Concurrent entry points mutate state stored on a singleton bean.")),
            Map.entry("CON-004", new Descriptor("Unsafe async state access", "An async method uses context bound to the caller thread.")),
            Map.entry("CON-005", new Descriptor("Unsafe double-checked locking", "Double-checked publication lacks proven volatile visibility and initialization ordering.")),
            Map.entry("IDEM-001", new Descriptor("Message consumer side effect", "A message listener has side effects without visible deduplication.")),
            Map.entry("IDEM-002", new Descriptor("External side effect without idempotency key", "A retried external mutation has no visible idempotency key.")),
            Map.entry("IDEM-003", new Descriptor("Retryable non-idempotent operation", "A retryable method performs a persistent or external side effect.")),
            Map.entry("IDEM-004", new Descriptor("Event handler without stable key", "A message handler has side effects without a recognizable stable business key.")),
            Map.entry("TX-001", new Descriptor("Transactional self invocation", "An internal call may bypass Spring's transactional proxy.")),
            Map.entry("TX-002", new Descriptor("Async transaction boundary", "A transaction assumption crosses an async thread boundary.")),
            Map.entry("TX-003", new Descriptor("Remote call inside transaction", "A remote call may prolong a transaction or diverge from database state.")),
            Map.entry("TX-004", new Descriptor("Multiple writes without transaction", "Multiple database mutations may partially commit.")),
            Map.entry("TX-005", new Descriptor("Swallowed exception in transaction", "A caught exception may allow a transaction to commit after partial work.")),
            Map.entry("REL-001", new Descriptor("Database and external side-effect consistency", "Database and remote side effects may succeed or fail independently.")));

    private final List<RuleMetadata> metadata;
    private final ConcurrencySpecCatalog concurrencySpecification;

    public RuleRegistry(List<RiskRule> rules) {
        this(rules, new ConcurrencySpecCatalog());
    }

    @Autowired
    public RuleRegistry(List<RiskRule> rules, ConcurrencySpecCatalog concurrencySpecification) {
        this.concurrencySpecification = concurrencySpecification;
        this.metadata = rules.stream().map(rule -> metadata(rule)).sorted(Comparator.comparing(RuleMetadata::id)).toList();
        long distinctIds = metadata.stream().map(RuleMetadata::id).distinct().count();
        if (distinctIds != metadata.size()) throw new IllegalArgumentException("Rule IDs must be unique");
    }

    public List<RuleMetadata> all() { return metadata; }

    public ConcurrencySpecCatalog concurrencySpecification() { return concurrencySpecification; }

    public RuleMetadata find(String id) {
        return metadata.stream().filter(rule -> rule.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    private RuleMetadata metadata(RiskRule rule) {
        Descriptor descriptor = DESCRIPTORS.getOrDefault(rule.id(),
                new Descriptor(rule.id(), "Static code risk rule " + rule.id()));
        return new RuleMetadata(rule.id(), descriptor.title(), descriptor.description(),
                rule.category(), rule.defaultSeverity());
    }

    private record Descriptor(String title, String description) {}
}
