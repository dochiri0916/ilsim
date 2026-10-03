package com.example.coderiskanalyzer.production.cli;

import java.util.stream.Collectors;

import com.example.coderiskanalyzer.production.rule.RuleMetadata;
import com.example.coderiskanalyzer.production.rule.RuleRegistry;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

@Component
public class RulesCommand {

    private final RuleRegistry registry;

    public RulesCommand(RuleRegistry registry) { this.registry = registry; }

    @Command(name = "rules", description = "List analyzer rules or show one rule")
    public String rules(@Argument(index = 0, defaultValue = "",
            description = "Optional rule ID") String ruleId) {
        if (ruleId != null && !ruleId.isBlank()) {
            RuleMetadata rule = registry.find(ruleId);
            if (rule == null) return "Error: unknown rule: " + ruleId;
            return "%s  %s%n%s%nCategory: %s  Default severity: %s".formatted(
                    rule.id(), rule.title(), rule.description(), rule.category(), rule.defaultSeverity());
        }
        return registry.all().stream().map(rule -> "%s  %s".formatted(rule.id(), rule.title()))
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
