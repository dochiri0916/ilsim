package com.example.coderiskanalyzer.production.rule;

import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;

public record RuleMetadata(String id, String title, String description,
                           RiskCategory category, Severity defaultSeverity) {}
