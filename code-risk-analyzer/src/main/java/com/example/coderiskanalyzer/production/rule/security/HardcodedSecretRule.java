package com.example.coderiskanalyzer.production.rule.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.springframework.stereotype.Component;

/** Reports likely credentials without ever including their literal values in a finding. */
@Component
public class HardcodedSecretRule implements RiskRule {

    private static final Set<String> SECRET_NAME_PARTS = Set.of(
            "apikey", "secret", "password", "passwd", "token", "privatekey", "accesskey", "credential");
    private static final Set<String> PLACEHOLDERS = Set.of(
            "dummy", "placeholder", "changeme", "change_me", "your_secret", "your_api_key");
    /** Short words that only mark a placeholder when they are the value itself or its prefix (not any substring). */
    private static final Set<String> PLACEHOLDER_PREFIXES = Set.of("example", "test");
    /** Identifier suffixes that name a field or reference rather than hold a credential. */
    private static final Set<String> NON_SECRET_SUFFIXES = Set.of(
            "url", "uri", "type", "header", "param", "name", "path", "claim", "prefix", "endpoint", "field", "key_name");

    @Override public String id() { return "SEC-005"; }
    @Override public RiskCategory category() { return RiskCategory.SECURITY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null || isTestSource(source.sourceFile().path())) continue;
            for (StringLiteralExpr literal : source.compilationUnit().findAll(StringLiteralExpr.class)) {
                String identifier = enclosingIdentifier(literal);
                if (identifier == null || !looksSensitiveName(identifier) || !looksLikeCredential(literal.asString())) continue;
                var location = location(source.sourceFile().path(), literal);
                String kind = secretKind(identifier);
                Evidence evidence = new Evidence("HARDCODED_SECRET", "A credential-like string is assigned to a sensitive field or variable; its value is redacted.",
                        location, Map.of("identifier", identifier, "kind", kind, "redacted", true));
                findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.MEDIUM,
                        "Possible hardcoded secret", "A credential-like literal is stored in " + identifier
                                + "; the literal value is intentionally omitted from all analyzer output.",
                        location, List.of(evidence), FindingStatus.REVIEW));
            }
        }
        return findings.stream().distinct().toList();
    }

    private String enclosingIdentifier(StringLiteralExpr literal) {
        Node current = literal;
        while (current != null && !(current instanceof VariableDeclarator)) {
            current = current.getParentNode().orElse(null);
        }
        if (current instanceof VariableDeclarator variable
                && variable.getInitializer().filter(value -> value == literal).isPresent()) {
            return variable.getNameAsString();
        }
        current = literal;
        while (current != null && !(current instanceof FieldDeclaration)) {
            current = current.getParentNode().orElse(null);
        }
        FieldDeclaration field = current instanceof FieldDeclaration declaration ? declaration : null;
        if (field != null && field.getVariables().size() == 1) return field.getVariable(0).getNameAsString();
        return null;
    }

    private boolean looksSensitiveName(String name) {
        String normalized = name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (NON_SECRET_SUFFIXES.stream().anyMatch(suffix -> normalized.endsWith(suffix.replace("_", "")))) return false;
        return SECRET_NAME_PARTS.stream().anyMatch(normalized::contains);
    }

    private boolean looksLikeCredential(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 8 || PLACEHOLDERS.stream().anyMatch(normalized::contains)
                || PLACEHOLDER_PREFIXES.stream().anyMatch(normalized::startsWith)) return false;
        if (normalized.matches("[a-z][a-z0-9+.-]*://[^@\\s]*")) return false;
        if (value.matches("AKIA[0-9A-Z]{16}") || value.matches("gh[pousr]_[A-Za-z0-9_]{20,}")) return true;
        if (value.startsWith("-----BEGIN ") && value.contains("PRIVATE KEY-----")) return true;
        if (normalized.contains("example.com") || normalized.equals("password") || normalized.equals("secret")) return false;
        return true;
    }

    private String secretKind(String identifier) {
        String normalized = identifier.toLowerCase(Locale.ROOT);
        if (normalized.contains("password") || normalized.contains("passwd")) return "password";
        if (normalized.contains("token")) return "token";
        if (normalized.contains("privatekey")) return "private-key";
        if (normalized.contains("apikey") || normalized.contains("accesskey")) return "api-key";
        return "secret";
    }

    private boolean isTestSource(java.nio.file.Path path) {
        return path.toString().replace('\\', '/').contains("/src/test/");
    }

    private SourceLocation location(java.nio.file.Path path, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(path, -1, -1, -1, -1);
        return new SourceLocation(path, range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
