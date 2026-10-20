package com.example.coderiskanalyzer.production.rule.concurrency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.RiskCategory;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyAssessment;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyOutcome;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;
import org.springframework.stereotype.Component;

@Component
public class DoubleCheckedLockingRule implements ConcurrencyRiskRule {
    private static final PrettyPrinterConfiguration PRINT = new PrettyPrinterConfiguration().setPrintComments(false);

    @Override public String id() { return "CON-005"; }
    @Override public RiskCategory category() { return RiskCategory.CONCURRENCY; }
    @Override public Severity defaultSeverity() { return Severity.HIGH; }

    @Override
    public List<ConcurrencyAssessment> assess(AnalysisContext context) {
        List<ConcurrencyAssessment> assessments = new ArrayList<>();
        for (ParsedSource source : context.parsedSources()) {
            if (source.compilationUnit() == null) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                if (method.getBody().isEmpty()) continue;
                TypeDeclaration<?> owner = method.findAncestor(TypeDeclaration.class).orElse(null);
                if (owner == null) continue;
                for (IfStmt outer : method.getBody().get().findAll(IfStmt.class)) {
                    if (!inMethod(outer, method)) continue;
                    FieldReference field = nullCheckedField(outer.getCondition(), method, owner);
                    if (field == null) continue;
                    boolean staticField = field.variable() != null
                            && ((FieldDeclaration) field.variable().getParentNode().orElseThrow()).isStatic();
                    if ((method.isSynchronized() && field.variable() != null && (!staticField || method.isStatic()))
                            || outer.findAncestor(SynchronizedStmt.class)
                                    .filter(enclosing -> stableMonitor(enclosing.getExpression(), field, method, owner)).isPresent()) continue;
                    for (SynchronizedStmt lock : outer.getThenStmt().findAll(SynchronizedStmt.class)) {
                        if (!inMethod(lock, method)) continue;
                        for (IfStmt inner : lock.getBody().findAll(IfStmt.class)) {
                            if (!inMethod(inner, method) || inner.findAncestor(SynchronizedStmt.class).orElse(null) != lock
                                    || !sameField(field, nullCheckedField(inner.getCondition(), method, owner))) continue;
                            for (AssignExpr publication : inner.getThenStmt().findAll(AssignExpr.class)) {
                                if (!inMethod(publication, method) || publication.getOperator() != AssignExpr.Operator.ASSIGN
                                        || !sameField(field, reference(publication.getTarget(), method, owner))) continue;
                                assessments.add(assessment(source, method, owner, outer, lock, inner, publication, field));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(assessments);
    }

    @Override
    public Finding finding(ConcurrencyAssessment assessment) {
        boolean definite = assessment.outcome() == ConcurrencyOutcome.DEFINITE_PATTERN;
        String message = definite ? "Double-checked publication of " + assessment.subject()
                + " lacks volatile visibility or publishes before initialization finishes."
                : "The protection of double-checked publication of " + assessment.subject()
                        + " could not be established: " + String.join("; ", assessment.missingEvidence());
        return new Finding(id(), category(), defaultSeverity(), definite ? Confidence.HIGH : Confidence.LOW,
                "Unsafe double-checked locking", message, assessment.location(), assessment.evidence(),
                definite ? FindingStatus.CONFIRMED : FindingStatus.REVIEW, assessment);
    }

    private ConcurrencyAssessment assessment(ParsedSource source, MethodDeclaration method, TypeDeclaration<?> owner,
                                             IfStmt outer, SynchronizedStmt lock, IfStmt inner,
                                             AssignExpr publication, FieldReference field) {
        SourceLocation location = location(source, publication);
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("DCL_PATTERN", "Null checks outside and inside a monitor precede publication",
                location, Map.of("class", ownerName(owner), "method", method.getSignature().asString(),
                        "field", field.identity(), "monitor", text(lock.getExpression()))));
        List<String> missing = new ArrayList<>();
        List<String> safeguards = new ArrayList<>();
        ConcurrencyOutcome outcome;
        if (field.variable() == null) {
            missing.add("The checked field declaration and its volatile modifier are unresolved");
            outcome = ConcurrencyOutcome.UNKNOWN;
        } else {
            FieldDeclaration declaration = (FieldDeclaration) field.variable().getParentNode().orElseThrow();
            boolean volatileField = declaration.isVolatile();
            evidence.add(new Evidence("DCL_FIELD", "Publication field modifiers", location(source, field.variable()),
                    Map.of("field", field.identity(), "volatile", volatileField)));
            if (!volatileField) outcome = ConcurrencyOutcome.DEFINITE_PATTERN;
            else {
                safeguards.add("The same publication field is volatile");
                Node lateWrite = lateWrite(inner, publication, field, method, owner);
                if (lateWrite != null) {
                    evidence.add(new Evidence("DCL_POST_PUBLICATION_WRITE", "Object state is written after publication",
                            location(source, lateWrite), Map.of("operation", text(lateWrite))));
                    outcome = ConcurrencyOutcome.DEFINITE_PATTERN;
                } else if (completePublication(inner, publication) && stableMonitor(lock.getExpression(), field, method, owner)
                        && lock.getBody().getStatements().size() == 1 && lock.getBody().getStatement(0) == inner
                        && onlyStatement(outer.getThenStmt(), lock)
                        && constructionKnownSafe((ObjectCreationExpr) unwrap(publication.getValue()))) {
                    safeguards.add("A constructor completes before the sole statement publishes its result");
                    safeguards.add("A stable monitor protects the second check for the same shared field");
                    outcome = ConcurrencyOutcome.SAFE_BY_PROOF;
                } else {
                    missing.add("Initialization completion and absence of post-publication mutation are not proven"
                            + " for a factory, alias, constructor side effects, anonymous object, additional statements,"
                            + " or unproven monitor identity");
                    outcome = ConcurrencyOutcome.UNKNOWN;
                }
            }
        }
        if (!source.errors().isEmpty()) {
            missing.add("The source has recoverable parse errors");
            outcome = ConcurrencyOutcome.UNKNOWN;
        }
        String visibility = field.variable() == null ? "unresolved"
                : ((FieldDeclaration) field.variable().getParentNode().orElseThrow()).isVolatile() ? "volatile" : "plain";
        List<String> path = List.of("outer:" + text(outer.getCondition()), "monitor:" + text(lock.getExpression()),
                "inner:" + text(inner.getCondition()), "publication:" + text(publication),
                "branch:" + text(outer.getThenStmt()), "visibility:" + visibility);
        return new ConcurrencyAssessment(id(), "JAVA-CONC-006", ownerName(owner) + "#" + method.getSignature().asString(),
                field.identity(), path, outcome, location, evidence,
                List.of("Concurrent callers may execute this method", "Assessment is limited to this publication path"),
                missing, safeguards);
    }

    private boolean completePublication(IfStmt inner, AssignExpr publication) {
        Expression value = unwrap(publication.getValue());
        if (!(value instanceof ObjectCreationExpr creation) || creation.getAnonymousClassBody().isPresent()
                || creation.getScope().isPresent()) return false;
        var then = inner.getThenStmt();
        if (then.isBlockStmt()) {
            var statements = then.asBlockStmt().getStatements();
            return statements.size() == 1 && statements.get(0).isExpressionStmt()
                    && statements.get(0).asExpressionStmt().getExpression() == publication;
        }
        return then.isExpressionStmt() && then.asExpressionStmt().getExpression() == publication;
    }

    private boolean onlyStatement(com.github.javaparser.ast.stmt.Statement container, Node statement) {
        return container == statement || container.isBlockStmt()
                && container.asBlockStmt().getStatements().size() == 1
                && container.asBlockStmt().getStatement(0) == statement;
    }

    private boolean constructionKnownSafe(ObjectCreationExpr creation) {
        if (creation.getArguments().stream().anyMatch(argument -> !simpleInitialization(argument))) return false;
        try {
            var resolved = creation.resolve();
            if (resolved.declaringType().getQualifiedName().equals("java.lang.Object")) return creation.getArguments().isEmpty();
            var type = creation.calculateResolvedType().asReferenceType().getTypeDeclaration()
                    .flatMap(declaration -> declaration.toAst()).orElse(null);
            if (!(type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration declaration)
                    || !declaration.getExtendedTypes().isEmpty()) return false;
            for (FieldDeclaration field : declaration.getFields()) {
                for (VariableDeclarator variable : field.getVariables()) {
                    if (variable.getInitializer().isPresent() && !simpleInitialization(variable.getInitializer().get())) return false;
                }
            }
            if (declaration.getMembers().stream().filter(InitializerDeclaration.class::isInstance)
                    .map(InitializerDeclaration.class::cast).anyMatch(initializer -> !simpleInitialization(initializer.getBody()))) return false;
            var constructor = resolved.toAst(ConstructorDeclaration.class);
            return constructor.isPresent() ? simpleInitialization(constructor.get().getBody())
                    : declaration.getConstructors().isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean simpleInitialization(Node node) {
        if (!node.findAll(MethodCallExpr.class).isEmpty() || !node.findAll(ObjectCreationExpr.class).isEmpty()
                || !node.findAll(LambdaExpr.class).isEmpty()
                || !node.findAll(com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt.class).isEmpty()) return false;
        for (ThisExpr self : node.findAll(ThisExpr.class)) {
            if (!(self.getParentNode().orElse(null) instanceof FieldAccessExpr access)
                    || !(access.getParentNode().orElse(null) instanceof AssignExpr assignment)
                    || assignment.getTarget() != access) return false;
        }
        for (AssignExpr assignment : node.findAll(AssignExpr.class)) {
            if (!(assignment.getTarget() instanceof NameExpr)
                    && !(assignment.getTarget() instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr)) return false;
        }
        return true;
    }

    private boolean stableMonitor(Expression expression, FieldReference publication, MethodDeclaration method,
                                   TypeDeclaration<?> owner) {
        expression = unwrap(expression);
        boolean staticPublication = publication.variable() != null
                && ((FieldDeclaration) publication.variable().getParentNode().orElseThrow()).isStatic();
        if (expression.isClassExpr()) return true;
        if (expression instanceof ThisExpr self) return !staticPublication && self.getTypeName().isEmpty();
        FieldReference monitor = reference(expression, method, owner);
        if (monitor == null || monitor.variable() == null) return false;
        FieldDeclaration declaration = (FieldDeclaration) monitor.variable().getParentNode().orElseThrow();
        return declaration.isFinal() && (!staticPublication || declaration.isStatic());
    }

    private Node lateWrite(IfStmt inner, AssignExpr publication, FieldReference field,
                           MethodDeclaration method, TypeDeclaration<?> owner) {
        if (!inner.getThenStmt().isBlockStmt()) return null;
        var statements = inner.getThenStmt().asBlockStmt().getStatements();
        int publicationIndex = -1;
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).isExpressionStmt()
                    && statements.get(i).asExpressionStmt().getExpression() == publication) publicationIndex = i;
        }
        if (publicationIndex < 0) return null;
        for (int i = publicationIndex + 1; i < statements.size(); i++) {
            var statement = statements.get(i);
            if (!statement.isExpressionStmt() || !statement.asExpressionStmt().getExpression().isAssignExpr()) continue;
            AssignExpr write = statement.asExpressionStmt().getExpression().asAssignExpr();
            if (write.getTarget() instanceof FieldAccessExpr target
                    && sameField(field, reference(target.getScope(), method, owner))) return write;
        }
        return null;
    }

    private FieldReference nullCheckedField(Expression condition, MethodDeclaration method, TypeDeclaration<?> owner) {
        Expression expression = unwrap(condition);
        if (!(expression instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.EQUALS) return null;
        if (unwrap(binary.getLeft()).isNullLiteralExpr()) return reference(binary.getRight(), method, owner);
        if (unwrap(binary.getRight()).isNullLiteralExpr()) return reference(binary.getLeft(), method, owner);
        return null;
    }

    private FieldReference reference(Expression expression, MethodDeclaration method, TypeDeclaration<?> owner) {
        expression = unwrap(expression);
        String name;
        boolean own;
        if (expression instanceof NameExpr bare) {
            name = bare.getNameAsString();
            try {
                if (!bare.resolve().isField()) return null;
            } catch (RuntimeException ignored) {
                if (method.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(name))
                        || method.findAll(VariableDeclarator.class).stream()
                                .anyMatch(variable -> variable.getNameAsString().equals(name))) return null;
            }
            own = true;
        } else if (expression instanceof FieldAccessExpr access) {
            name = access.getNameAsString();
            Expression scope = unwrap(access.getScope());
            own = scope instanceof ThisExpr self && self.getTypeName().isEmpty()
                    || scope instanceof NameExpr type && type.getNameAsString().equals(owner.getNameAsString());
            if (own && scope instanceof NameExpr type) {
                try { type.resolve(); own = false; }
                catch (RuntimeException ignored) { }
            }
        } else return null;
        if (!own) return new FieldReference(text(expression), null);
        VariableDeclarator variable = owner.getFields().stream().flatMap(field -> field.getVariables().stream())
                .filter(item -> item.getNameAsString().equals(name)).findFirst().orElse(null);
        if (variable != null && expression instanceof FieldAccessExpr access && access.getScope() instanceof NameExpr
                && !((FieldDeclaration) variable.getParentNode().orElseThrow()).isStatic()) variable = null;
        return new FieldReference(ownerName(owner) + "." + name, variable);
    }

    private boolean sameField(FieldReference first, FieldReference second) {
        return first != null && second != null && first.identity().equals(second.identity());
    }

    private boolean inMethod(Node node, MethodDeclaration method) {
        return node.findAncestor(MethodDeclaration.class).orElse(null) == method
                && node.findAncestor(LambdaExpr.class).isEmpty()
                && node.findAncestor(TypeDeclaration.class).orElse(null) == method.findAncestor(TypeDeclaration.class).orElse(null)
                && node.findAncestor(ObjectCreationExpr.class).isEmpty();
    }

    private Expression unwrap(Expression expression) {
        while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
        return expression;
    }

    private String ownerName(TypeDeclaration<?> owner) {
        List<String> names = new ArrayList<>();
        for (Node node = owner; node != null; node = node.getParentNode().orElse(null)) {
            if (node instanceof TypeDeclaration<?> type) names.add(type.getNameAsString());
        }
        Collections.reverse(names);
        String packageName = owner.findCompilationUnit().flatMap(unit -> unit.getPackageDeclaration())
                .map(declaration -> declaration.getNameAsString() + ".").orElse("");
        return packageName + String.join(".", names);
    }

    private String text(Node node) { return node.toString(PRINT); }

    private SourceLocation location(ParsedSource source, Node node) {
        return node.getRange().map(range -> new SourceLocation(source.sourceFile().path(), range.begin.line,
                range.begin.column, range.end.line, range.end.column))
                .orElseGet(() -> new SourceLocation(source.sourceFile().path(), -1, -1, -1, -1));
    }

    private record FieldReference(String identity, VariableDeclarator variable) {}
}
