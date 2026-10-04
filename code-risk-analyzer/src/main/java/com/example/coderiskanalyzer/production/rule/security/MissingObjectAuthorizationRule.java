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
 * Flags request handlers that load a resource by a client-supplied identifier while no caller identity or access
 * rule is visible on the handler or on the project methods it reaches (bounded call graph). A caller identity
 * parameter counts only when the handler uses it. Checks inside unresolved library calls are not seen; the
 * evidence records how much of the path was inspected so a reviewer (or JEV) can judge the rest.
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
    /** checkOwner(..), verifyAccess(..), assertPermission(..), ensureMember(..), canAccess(..) and similar. */
    private static final java.util.regex.Pattern OWNERSHIP_CHECK = java.util.regex.Pattern.compile(
            "(?i)(check|verify|assert|ensure|validate|require|authori[sz]e|can|has|is)\\w*"
                    + "(owner|ownership|access|permission|authori[sz]|member|allowed|permitted)\\w*");
    /** findByIdAndOwnerId(..), findByIdAndUser(..), existsByIdAndMemberId(..): the lookup itself is scoped to the caller. */
    private static final java.util.regex.Pattern SCOPED_LOOKUP = java.util.regex.Pattern.compile(
            "(?i)\\w*By\\w*And(owner|user|member|account|customer|author|creator|tenant)\\w*");

    @Override public String id() { return "SEC-006"; }
    @Override public RiskCategory category() { return RiskCategory.SECURITY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<Finding> analyze(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        CallPaths paths = new CallPaths(context);
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (ClassOrInterfaceDeclaration type : source.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!annotated(type, CONTROLLERS) || annotated(type, ACCESS_ANNOTATIONS)) continue;
                for (MethodDeclaration method : type.getMethods()) {
                    if (!annotated(method, MAPPINGS) || annotated(method, ACCESS_ANNOTATIONS) || usesCallerIdentity(method)) continue;
                    CallPaths.Reach reach = null;
                    for (Parameter parameter : method.getParameters()) {
                        Optional<String> requestName = requestIdentifier(parameter);
                        if (requestName.isEmpty() || !usedInCall(method, parameter.getNameAsString())) continue;
                        if (reach == null) reach = paths.from(type, method);
                        if (protectedOnPath(reach)) break;
                        SourceLocation location = location(source, parameter);
                        Evidence evidence = new Evidence("REQUEST_IDENTIFIER_WITHOUT_CALLER",
                                "A client-supplied identifier is used without a visible caller identity or access rule "
                                        + "on the handler or the project methods it reaches",
                                location, Map.of("handler", type.getNameAsString() + "#" + method.getNameAsString(),
                                        "parameter", requestName.get(),
                                        "inspectedMethods", reach.methods().stream()
                                                .map(declared -> declared.type().getNameAsString() + "#"
                                                        + declared.method().getNameAsString()).toList(),
                                        "externalCalls", reach.unresolvedCalls()));
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

    /** A caller identity parameter that the handler actually uses, or the security context read directly. */
    private static boolean usesCallerIdentity(MethodDeclaration method) {
        boolean parameter = method.getParameters().stream().filter(p -> annotated(p, CALLER_ANNOTATIONS)
                        || CALLER_TYPES.contains(p.getType().asString().replaceAll("<.*", "")))
                .anyMatch(p -> method.getBody().map(body -> body.findAll(NameExpr.class).stream()
                        .anyMatch(name -> name.getNameAsString().equals(p.getNameAsString()))).orElse(false));
        return parameter || readsSecurityContext(method);
    }

    private static boolean readsSecurityContext(MethodDeclaration method) {
        return method.findAll(NameExpr.class).stream().anyMatch(name -> name.getNameAsString().equals("SecurityContextHolder"));
    }

    /** An access rule, security context read, ownership check or caller-scoped lookup on the reachable project methods. */
    private static boolean protectedOnPath(CallPaths.Reach reach) {
        for (CallPaths.Declared declared : reach.methods()) {
            MethodDeclaration method = declared.method();
            if (annotated(method, ACCESS_ANNOTATIONS) || declared.type() instanceof NodeWithAnnotations<?> owner
                    && annotated(owner, ACCESS_ANNOTATIONS) || readsSecurityContext(method)) return true;
            boolean checked = method.findAll(MethodCallExpr.class).stream().map(MethodCallExpr::getNameAsString)
                    .anyMatch(name -> OWNERSHIP_CHECK.matcher(name).matches() || SCOPED_LOOKUP.matcher(name).matches());
            if (checked) return true;
        }
        return false;
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
