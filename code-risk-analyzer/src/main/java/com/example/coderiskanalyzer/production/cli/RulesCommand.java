package com.example.coderiskanalyzer.production.cli;

import java.util.List;
import java.util.stream.Collectors;

import com.example.coderiskanalyzer.production.rule.RuleMetadata;
import com.example.coderiskanalyzer.production.rule.RuleRegistry;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencySpecCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
public class RulesCommand {

    private final RuleRegistry registry;

    public RulesCommand(RuleRegistry registry) { this.registry = registry; }

    @Command(name = "rules", description = "List analyzer rules or show one rule")
    public String rules(@Argument(index = 0, defaultValue = "",
            description = "Optional analyzer or concurrency specification ID") String ruleId,
            @Option(longName = "spec", defaultValue = "false",
                    description = "Show concurrency specification priorities and implementation progress") boolean spec,
            @Option(longName = "format", defaultValue = "console", description = "Output format: console or json") String format) {
        if (!List.of("console", "json").contains(format)) return "Error: rules format must be console or json";
        var catalog = registry.concurrencySpecification();
        boolean hasId = ruleId != null && !ruleId.isBlank();
        var requirement = hasId ? catalog.find(ruleId) : null;
        if (spec || requirement != null) {
            if (hasId && requirement == null) return "Error: unknown specification rule: " + ruleId;
            if (format.equals("json")) return json(hasId ? requirement : catalog.catalog());
            if (hasId) return requirementDetails(requirement);
            return "Concurrency specification implementation progress (not scan execution coverage)\n"
                    + "Counts: " + catalog.progress().counts() + "\n"
                    + "Prerequisites and workflow:\n" + catalog.catalog().workItems().stream()
                            .sorted(java.util.Comparator.comparing(ConcurrencySpecCatalog.WorkItem::priority))
                            .map(item -> "%s  %s  %s  %s".formatted(item.id(), item.priority(),
                                    item.implementationStatus(), item.scope()))
                            .collect(Collectors.joining(System.lineSeparator())) + "\nSpecification rules:\n"
                    + catalog.all().stream().map(item -> "%s  %s  %s  [%s]  %s".formatted(item.id(),
                            item.priority(), item.implementationStatus(), analyzerIds(item), item.title()))
                            .collect(Collectors.joining(System.lineSeparator()));
        }
        if (ruleId != null && !ruleId.isBlank()) {
            RuleMetadata rule = registry.find(ruleId);
            if (rule == null) return "Error: unknown rule: " + ruleId;
            if (format.equals("json")) return json(new RuleView(rule, catalog.forAnalyzerRule(rule.id())));
            return "%s  %s%n%s%nCategory: %s  Default severity: %s".formatted(
                    rule.id(), rule.title(), rule.description(), rule.category(), rule.defaultSeverity())
                    + catalog.forAnalyzerRule(rule.id()).stream().map(item -> "%nSpecification: %s  %s  %s".formatted(
                            item.id(), item.priority(), item.implementationStatus())).collect(Collectors.joining());
        }
        if (format.equals("json")) return json(registry.all().stream()
                .map(rule -> new RuleView(rule, catalog.forAnalyzerRule(rule.id()))).toList());
        return registry.all().stream().map(rule -> "%s  %s".formatted(rule.id(), rule.title()))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    public String rules(String ruleId) { return rules(ruleId, false, "console"); }

    private String requirementDetails(ConcurrencySpecCatalog.Requirement rule) {
        return "%s  %s%nPriority: %s  Implementation: %s%nAnalyzer rules: %s%nAssessment: %s%nScope: %s%nRemaining: %s%nImplementation evidence: %s%nTest evidence: %s%nRecommended test: %s"
                .formatted(rule.id(), rule.title(), rule.priority(), rule.implementationStatus(), analyzerIds(rule),
                        rule.bindings().isEmpty() ? "not implemented" : rule.bindings().stream()
                                .map(binding -> binding.ruleId() + "=" + binding.outcome()).collect(Collectors.joining(", ")),
                        rule.implementedScope(), String.join("; ", rule.remainingWork()),
                        String.join(", ", rule.implementationEvidence()), String.join(", ", rule.testEvidence()),
                        rule.recommendedTest());
    }

    private String analyzerIds(ConcurrencySpecCatalog.Requirement rule) {
        return rule.bindings().isEmpty() ? "none" : rule.bindings().stream().map(binding -> binding.ruleId()
                + (binding.requiredEvidenceTypes().isEmpty() ? "" : " (requires "
                        + String.join(", ", binding.requiredEvidenceTypes()) + ")"))
                .collect(Collectors.joining(", "));
    }

    private String json(Object value) {
        try { return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (JsonProcessingException exception) { return "Error: could not render rule metadata"; }
    }

    private record RuleView(RuleMetadata rule, List<ConcurrencySpecCatalog.Requirement> concurrencySpecifications) {}
}
