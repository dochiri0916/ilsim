package com.example.coderiskanalyzer.production.rule.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.springframework.stereotype.Component;

/**
 * Flags request handlers that load a resource by a client-supplied identifier while no caller identity or
 * access annotation is visible at the entry point. Ownership checks deeper in the call chain are not proven.
 */
@Component
public class MissingObjectAuthorizationRule implements RiskRule {

    private static final Set<String> CONTROLLERS = Set.of("RestController", "Controller");
    private static final Set<String> MAPPINGS = Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "PatchMapping", "DeleteMapping");
    private static final Set<String> ACCESS_ANNOTATIONS = Set.of("PreAuthorize", "PostAuthorize", "Secured",
            "RolesAllowed", "PreFilter", "PostFilter");
    private static final Set<String> CALLER_TYPES = Set.of("Principal", "Authentication", "JwtAuthenticationToken",
            "OAuth2AuthenticationToken", "UsernamePasswordAuthenticationToken");
    private static final Set<String> CALLER_ANNOTATIONS = Set.of("AuthenticationPrincipal", "CurrentSecurityContext");
    private static final Set<String> REQUEST_INPUTS = Set.of("PathVariable", "RequestParam");

    @Override public String id() { return "SEC-006"; }
    @Override public RiskCategory category() { return RiskCategory.SECURITY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (ClassOrInterfaceDeclaration type : source.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!annotated(type, CONTROLLERS) || annotated(type, ACCESS_ANNOTATIONS)) continue;
                for (MethodDeclaration method : type.getMethods()) {
                    if (!annotated(method, MAPPINGS) || annotated(method, ACCESS_ANNOTATIONS) || hasCallerIdentity(method)) continue;
                    for (Parameter parameter : method.getParameters()) {
                        Optional<String> requestName = requestIdentifier(parameter);
                        if (requestName.isEmpty() || !usedInCall(method, parameter.getNameAsString())) continue;
                        SourceLocation location = location(source, parameter);
                        Evidence evidence = new Evidence("REQUEST_IDENTIFIER_WITHOUT_CALLER",
                                "A client-supplied identifier is used without a visible caller identity or access rule",
                                location, Map.of("handler", type.getNameAsString() + "#" + method.getNameAsString(),
                                        "parameter", requestName.get()));
                        findings.add(new Finding(id(), category(), Severity.HIGH, Confidence.LOW,
                                "Missing object-level authorization",
                                "The handler uses a request identifier but no caller identity or access annotation is "
                                        + "visible; confirm the resource owner or tenant is checked.",
                                location, List.of(evidence), FindingStatus.REVIEW));
                    }
                }
            }
        }
        return findings;
    }

    private static boolean annotated(NodeWithAnnotations<?> node, Set<String> names) {
        return node.getAnnotations().stream().anyMatch(annotation -> names.contains(annotation.getName().getIdentifier()));
    }

    private static boolean hasCallerIdentity(MethodDeclaration method) {
        boolean parameter = method.getParameters().stream().anyMatch(p -> annotated(p, CALLER_ANNOTATIONS)
                || CALLER_TYPES.contains(p.getType().asString().replaceAll("<.*", "")));
        boolean context = method.findAll(NameExpr.class).stream()
                .anyMatch(name -> name.getNameAsString().equals("SecurityContextHolder"));
        return parameter || context;
    }

    private static Optional<String> requestIdentifier(Parameter parameter) {
        for (AnnotationExpr annotation : parameter.getAnnotations()) {
            if (!REQUEST_INPUTS.contains(annotation.getName().getIdentifier())) continue;
            String name = explicitName(annotation).orElse(parameter.getNameAsString());
            if (name.equalsIgnoreCase("id") || name.endsWith("Id") || name.endsWith("ID") || name.endsWith("_id")) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> explicitName(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single && single.getMemberValue() instanceof StringLiteralExpr text) {
            return Optional.of(text.getValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream().filter(pair -> Set.of("value", "name").contains(pair.getNameAsString()))
                    .map(pair -> pair.getValue()).filter(StringLiteralExpr.class::isInstance)
                    .map(value -> ((StringLiteralExpr) value).getValue()).findFirst();
        }
        return Optional.empty();
    }

    private static boolean usedInCall(MethodDeclaration method, String name) {
        return method.findAll(MethodCallExpr.class).stream().flatMap(call -> call.getArguments().stream())
                .anyMatch(argument -> argument.findAll(NameExpr.class).stream().anyMatch(n -> n.getNameAsString().equals(name))
                        || argument instanceof NameExpr n && n.getNameAsString().equals(name));
    }

    private static SourceLocation location(ParsedSource source, Node node) {
        var range = node.getRange();
        if (range.isEmpty()) return new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1);
        return new SourceLocation(source.sourceFile().path(), range.get().begin.line, range.get().begin.column,
                range.get().end.line, range.get().end.column);
    }
}
