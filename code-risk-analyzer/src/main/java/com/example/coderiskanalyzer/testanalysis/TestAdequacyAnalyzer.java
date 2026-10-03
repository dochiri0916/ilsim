package com.example.coderiskanalyzer.testanalysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.model.SourceLocation;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import org.springframework.stereotype.Component;

/** Deterministic AST-based test adequacy analysis, kept separate from production risk rules. */
@Component
public class TestAdequacyAnalyzer {
    private static final Set<String> ASSERTIONS = Set.of("assertEquals", "assertNotEquals", "assertTrue", "assertFalse",
            "assertNull", "assertNotNull", "assertThrows", "assertDoesNotThrow", "assertAll", "assertThat",
            "verify", "fail");

    public List<TestAdequacyResult> analyze(List<ParsedSource> sources, List<Finding> risks) {
        Set<Path> productionScope = sources.stream().filter(s -> !isTestPath(s.sourceFile().path()))
                .map(s -> s.sourceFile().path()).collect(Collectors.toSet());
        Set<Path> testScope = sources.stream().filter(s -> isTestPath(s.sourceFile().path()))
                .map(s -> s.sourceFile().path()).collect(Collectors.toSet());
        return analyze(sources, risks, productionScope, testScope);
    }

    public List<TestAdequacyResult> analyze(List<ParsedSource> sources, List<Finding> risks,
                                             Set<Path> productionScope, Set<Path> testScope) {
        List<MethodDeclaration> production = new ArrayList<>();
        List<MethodDeclaration> tests = new ArrayList<>();
        boolean testParsingIncomplete = sources.stream().filter(s -> isTestPath(s.sourceFile().path())
                        && testScope.contains(s.sourceFile().path()))
                .anyMatch(s -> s.compilationUnit() == null || !s.errors().isEmpty());
        for (ParsedSource source : sources) {
            if (source.compilationUnit() == null) continue;
            boolean testSource = isTestPath(source.sourceFile().path());
            if (testSource && !testScope.contains(source.sourceFile().path())) continue;
            if (!testSource && !productionScope.contains(source.sourceFile().path())) continue;
            for (MethodDeclaration method : source.compilationUnit().findAll(MethodDeclaration.class)) {
                if (testSource && isTestMethod(method)) tests.add(method);
                else if (!testSource && method.getBody().isPresent() && !method.isPrivate() && !isTrivialAccessor(method)) production.add(method);
            }
        }

        List<TestObligation> obligations = new ArrayList<>();
        int[] next = {1};
        for (MethodDeclaration method : production) extract(method, sources, obligations, next);
        for (Finding risk : risks) {
            MethodDeclaration method = production.stream().filter(m -> m.findCompilationUnit()
                    .flatMap(c -> c.getStorage().map(s -> s.getPath().equals(risk.location().file()))).orElse(false))
                    .filter(m -> m.getRange().map(r -> r.begin.line <= risk.location().startLine()
                            && r.end.line >= risk.location().startLine()).orElse(false)).findFirst().orElse(null);
            if (method != null) obligations.add(new TestObligation(id(next), "RISK", risk.ruleId() + ": " + risk.title(),
                    ownerName(method), method.getNameAsString(), location(sources, risk.location().file(), method),
                    "test the production risk and its failure condition", risk.ruleId(), risk.category(), risk.severity()));
        }
        extractWeakTests(production, tests, sources, obligations, next);
        obligations = obligations.stream().map(this::stableIdentifier).toList();

        List<TestAdequacyResult> results = new ArrayList<>();
        for (TestObligation obligation : obligations) {
            List<MethodDeclaration> matches = tests.stream().filter(test -> calls(test, obligation))
                    .filter(test -> !obligation.type().equals("WEAK_TEST") || testName(test).equals(obligation.condition())).toList();
            List<String> names = matches.stream().map(this::testName).sorted().toList();
            boolean assertion = matches.stream().anyMatch(this::hasAssertion);
            boolean exactException = matches.stream().anyMatch(test -> expectedException(test,
                    obligation.requiredEvidence(), obligation.method()));
            CoverageStatus status;
            String reason;
            List<String> covered = List.of();
            List<String> missing = List.of();
            if (matches.isEmpty()) {
                if (testParsingIncomplete || hasUnresolvedCandidate(tests, obligation)) {
                    status = CoverageStatus.UNKNOWN;
                    reason = "Test linkage could not be resolved completely by static analysis.";
                } else {
                    status = CoverageStatus.MISSING;
                    reason = "No test method was linked to the production method by resolved method symbol.";
                    missing = List.of(obligation.requiredEvidence());
                }
            } else if (obligation.type().equals("EXCEPTION_PATH") && exactException) {
                status = CoverageStatus.COVERED;
                reason = "A linked test asserts the expected exception type.";
                covered = List.of(obligation.requiredEvidence());
            } else if (obligation.type().equals("EXCEPTION_PATH") && assertion) {
                status = CoverageStatus.PARTIAL;
                reason = "A linked test asserts another outcome, but not the expected exception path.";
                missing = List.of(obligation.requiredEvidence());
            } else if (obligation.type().equals("PERSISTENCE") && matches.stream().anyMatch(t -> verifies(t, obligation.requiredEvidence()))) {
                status = CoverageStatus.COVERED;
                reason = "A linked test verifies the persistence interaction.";
                covered = List.of(obligation.requiredEvidence());
            } else if (!assertion) {
                status = CoverageStatus.MISSING;
                reason = "A linked test invokes the method but has no recognized assertion or interaction verification.";
                missing = List.of(obligation.requiredEvidence());
            } else if (obligation.type().equals("BRANCH") || obligation.type().equals("BOUNDARY")
                    || obligation.type().equals("STATE_CHANGE") || obligation.type().equals("RISK")
                    || obligation.type().equals("VALIDATION") || obligation.type().equals("RETURN_VALUE")
                    || obligation.type().equals("AUTHORIZATION") || obligation.type().equals("TRANSACTION_ROLLBACK")
                    || obligation.type().equals("PERSISTENCE")) {
                status = CoverageStatus.PARTIAL;
                reason = "A linked test has an assertion, but static analysis cannot tie it to this specific behavior.";
                covered = List.of("related assertion exists");
                missing = List.of(obligation.requiredEvidence());
                if (obligation.type().equals("BOUNDARY")) {
                    BoundaryCoverage boundary = boundaryCoverage(obligation, matches, production);
                    if (boundary != null) {
                        reason = "Input boundary cases are identified, but assertion outcomes cannot be tied to each case statically.";
                        covered = boundary.covered();
                        missing = boundary.missing();
                    }
                }
            } else {
                status = CoverageStatus.UNKNOWN;
                reason = "A linked test exists, but its assertion meaning cannot be determined statically.";
            }
            results.add(new TestAdequacyResult(obligation, status, names, covered, missing, reason));
        }
        return results.stream().sorted(Comparator.comparing(r -> r.obligation().id())).toList();
    }

    private void extract(MethodDeclaration method, List<ParsedSource> sources,
                         List<TestObligation> out, int[] next) {
        int startSize = out.size();
        String owner = ownerName(method);
        SourceLocation location = locationFor(method, sources);
        method.findAll(IfStmt.class).forEach(branch -> {
            String condition = branch.getCondition().toString();
            out.add(new TestObligation(id(next), "BRANCH", "condition true: " + condition, owner,
                    method.getNameAsString(), location, "assert the true-path outcome for " + condition, null));
            out.add(new TestObligation(id(next), "BRANCH", "condition false: " + condition, owner,
                    method.getNameAsString(), location, "assert the false-path outcome for " + condition, null));
            branch.getCondition().findAll(BinaryExpr.class).forEach(binary -> {
                String caseText = switch (binary.getOperator()) {
                    case LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> "less than, equal to, and greater than boundary";
                    case EQUALS, NOT_EQUALS -> "equal and not equal cases";
                    default -> null;
                };
                if (caseText != null) out.add(new TestObligation(id(next), "BOUNDARY", binary.toString(), owner,
                        method.getNameAsString(), location, "exercise " + caseText + " for " + binary, null));
            });
        });
        method.findAll(SwitchStmt.class).forEach(branch -> out.add(new TestObligation(id(next), "BRANCH",
                "switch cases: " + branch.getEntries().stream().map(Object::toString).collect(Collectors.joining(", ")),
                owner, method.getNameAsString(), location, "assert each meaningful switch outcome", null)));
        method.findAll(SwitchExpr.class).forEach(branch -> out.add(new TestObligation(id(next), "BRANCH",
                "switch expression cases: " + branch.getEntries().stream().map(Object::toString).collect(Collectors.joining(", ")),
                owner, method.getNameAsString(), location, "assert each meaningful switch expression outcome", null)));
        method.findAll(ConditionalExpr.class).forEach(branch -> out.add(new TestObligation(id(next), "BRANCH",
                "ternary condition: " + branch.getCondition(), owner, method.getNameAsString(), location,
                "assert both ternary outcomes", null)));
        method.findAll(ObjectCreationExpr.class).stream().filter(c -> c.getParentNode().map(p -> p instanceof com.github.javaparser.ast.stmt.ThrowStmt).orElse(false))
                .forEach(creation -> out.add(new TestObligation(id(next), "EXCEPTION_PATH", "throws " + creation.getType(), owner,
                        method.getNameAsString(), location, creation.getType().getNameAsString(), null)));
        method.findAll(MethodCallExpr.class).stream().filter(call -> call.getNameAsString().equals("orElseThrow"))
                .forEach(call -> out.add(new TestObligation(id(next), "EXCEPTION_PATH", "orElseThrow path", owner,
                        method.getNameAsString(), location, call.getArguments().isEmpty() ? "RuntimeException" : exceptionName(call.getArgument(0).toString()), null)));
        method.findAll(MethodCallExpr.class).stream().filter(call -> Set.of("save", "saveAll", "saveAndFlush", "delete", "deleteAll").contains(call.getNameAsString())
                || call.getNameAsString().startsWith("deleteBy") || call.getNameAsString().startsWith("update"))
                .forEach(call -> out.add(new TestObligation(id(next), "PERSISTENCE", call.getNameAsString(), owner,
                        method.getNameAsString(), location, call.getNameAsString(), null)));
        boolean directStateMutation = method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                .anyMatch(assign -> assign.getTarget() instanceof com.github.javaparser.ast.expr.FieldAccessExpr
                        || assign.getTarget() instanceof com.github.javaparser.ast.expr.NameExpr name
                        && resolvesToField(name));
        if (directStateMutation || method.findAll(MethodCallExpr.class).stream().anyMatch(TestAdequacyAnalyzer::looksLikeMutatingCall))
            out.add(new TestObligation(id(next), "STATE_CHANGE", "state-changing call in method", owner,
                    method.getNameAsString(), location, "assert resulting entity or returned state", null));
        method.getParameters().forEach(parameter -> parameter.getAnnotations().forEach(annotation -> {
            String name = annotation.getName().getIdentifier();
            if (Set.of("NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max", "Positive", "PositiveOrZero", "Negative", "Pattern", "Valid").contains(name))
                out.add(new TestObligation(id(next), "VALIDATION", name + " on " + parameter.getNameAsString(), owner,
                        method.getNameAsString(), location, "assert valid and invalid input behavior for " + name, null));
        }));
        if (method.getAnnotations().stream().map(a -> a.getName().getIdentifier())
                .anyMatch(name -> Set.of("PreAuthorize", "Secured", "RolesAllowed").contains(name))) {
            out.add(new TestObligation(id(next), "AUTHORIZATION", "authorization guard on " + method.getNameAsString(),
                    owner, method.getNameAsString(), location, "assert authorized and unauthorized access behavior", null));
        }
        long writes = method.findAll(MethodCallExpr.class).stream().filter(call -> Set.of("save", "saveAll", "saveAndFlush", "delete", "deleteAll").contains(call.getNameAsString())
                || call.getNameAsString().startsWith("deleteBy") || call.getNameAsString().startsWith("update")).count();
        boolean hasExceptionPath = method.findAll(com.github.javaparser.ast.stmt.ThrowStmt.class).size() > 0
                || method.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals("orElseThrow"));
        if (method.getAnnotations().stream().anyMatch(a -> a.getName().getIdentifier().equals("Transactional"))
                && (writes > 1 || writes > 0 && hasExceptionPath)) {
            out.add(new TestObligation(id(next), "TRANSACTION_ROLLBACK", "transaction has multiple writes or a write and exception path",
                    owner, method.getNameAsString(), location, "assert state rollback after failure", null));
        }
        if (out.size() == startSize && !method.getType().isVoidType()) {
            out.add(new TestObligation(id(next), "RETURN_VALUE", "return value from " + method.getNameAsString(), owner,
                    method.getNameAsString(), location, "assert the returned behavior or value", null));
        }
    }

    private boolean calls(MethodDeclaration test, TestObligation obligation) {
        return test.findAll(MethodCallExpr.class).stream().anyMatch(call -> calls(call, obligation));
    }

    private boolean calls(MethodCallExpr call, TestObligation obligation) {
        try {
            var resolved = call.resolve();
            boolean sameOwner = resolved.getName().equals(obligation.method())
                    && (resolved.declaringType().getQualifiedName().equals(obligation.owner())
                    || resolved.declaringType().getQualifiedName().endsWith("." + obligation.owner()));
            boolean sameDeclaration = resolved.toAst(MethodDeclaration.class).flatMap(MethodDeclaration::getRange)
                    .map(range -> range.begin.line == obligation.location().startLine()
                            && range.begin.column == obligation.location().startColumn()).orElse(false);
            return sameOwner && sameDeclaration;
        } catch (RuntimeException ignored) { return false; }
    }

    private boolean hasUnresolvedCandidate(List<MethodDeclaration> tests, TestObligation obligation) {
        for (MethodDeclaration test : tests) {
            for (MethodCallExpr call : test.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().equals(obligation.method())) continue;
                try {
                    var resolved = call.resolve();
                    if (resolved.declaringType().getQualifiedName().equals(obligation.owner())
                            && resolved.toAst(MethodDeclaration.class).isEmpty()) return true;
                } catch (RuntimeException ignored) { return true; }
            }
        }
        return false;
    }

    private BoundaryCoverage boundaryCoverage(TestObligation obligation, List<MethodDeclaration> tests,
                                               List<MethodDeclaration> production) {
        MethodDeclaration target = production.stream().filter(method -> method.findCompilationUnit()
                .flatMap(unit -> unit.getStorage().map(storage -> storage.getPath().equals(obligation.location().file())))
                .orElse(false))
                .filter(method -> ownerName(method).equals(obligation.owner())
                        && method.getNameAsString().equals(obligation.method()))
                .filter(method -> method.getRange().map(range -> range.begin.line == obligation.location().startLine()
                        && range.begin.column == obligation.location().startColumn()).orElse(false))
                .findFirst().orElse(null);
        if (target == null) return null;
        BinaryExpr binary = target.findAll(BinaryExpr.class).stream()
                .filter(candidate -> candidate.toString().equals(obligation.condition())).findFirst().orElse(null);
        if (binary == null || !binary.getLeft().isNameExpr()) return null;
        Long boundary = integerValue(binary.getRight().toString());
        if (boundary == null) return null;
        int parameterIndex = -1;
        for (int index = 0; index < target.getParameters().size(); index++) {
            if (target.getParameter(index).getNameAsString().equals(binary.getLeft().asNameExpr().getNameAsString())) {
                parameterIndex = index;
                break;
            }
        }
        if (parameterIndex < 0) return null;
        List<String> required = switch (binary.getOperator()) {
            case LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> List.of("less than", "equal to", "greater than");
            case EQUALS, NOT_EQUALS -> List.of("equal to", "not equal to");
            default -> List.of();
        };
        if (required.isEmpty()) return null;
        Set<String> exercised = new java.util.LinkedHashSet<>();
        for (MethodDeclaration test : tests) {
            if (!hasAssertion(test)) continue;
            for (MethodCallExpr call : test.findAll(MethodCallExpr.class)) {
                if (!calls(call, obligation) || call.getArguments().size() <= parameterIndex) continue;
                Long value = integerValue(call.getArgument(parameterIndex).toString());
                if (value == null) continue;
                int compare = Long.compare(value, boundary);
                if (compare < 0) exercised.add("less than");
                else if (compare == 0) exercised.add("equal to");
                else exercised.add("greater than");
                if ((binary.getOperator() == BinaryExpr.Operator.EQUALS || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                        && compare != 0) exercised.add("not equal to");
            }
        }
        List<String> covered = required.stream().filter(exercised::contains).toList();
        List<String> missing = required.stream().filter(item -> !exercised.contains(item)).toList();
        return new BoundaryCoverage(covered, missing);
    }

    private Long integerValue(String expression) {
        try { return Long.parseLong(expression.replace("_", "").replaceAll("[lL]$", "")); }
        catch (NumberFormatException ignored) { return null; }
    }

    private boolean hasAssertion(MethodDeclaration test) {
        return test.findAll(MethodCallExpr.class).stream().anyMatch(call -> ASSERTIONS.contains(call.getNameAsString())
                || call.getNameAsString().startsWith("assert")
                || (call.getNameAsString().startsWith("is") && hasAssertThatAncestor(call))
                || (call.getNameAsString().startsWith("has") && hasAssertThatAncestor(call)));
    }

    private void extractWeakTests(List<MethodDeclaration> production, List<MethodDeclaration> tests,
                                  List<ParsedSource> sources, List<TestObligation> obligations, int[] next) {
        for (MethodDeclaration test : tests) {
            if (hasAssertion(test)) continue;
            for (MethodDeclaration method : production) {
                SourceLocation methodLocation = locationFor(method, sources);
                TestObligation linkedMethod = new TestObligation("", "", "", ownerName(method),
                        method.getNameAsString(), methodLocation, "", null);
                if (calls(test, linkedMethod)) {
                    obligations.add(new TestObligation(id(next), "WEAK_TEST", testName(test), ownerName(method),
                            method.getNameAsString(), methodLocation,
                            "assert a behavior or verify the intended interaction", null));
                }
            }
        }
    }
    private boolean hasAssertThatAncestor(MethodCallExpr call) {
        return call.findAncestor(MethodCallExpr.class).map(parent -> parent.getNameAsString().equals("assertThat")).orElse(false);
    }
    private boolean verifies(MethodDeclaration test, String operation) {
        return test.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals(operation)
                && call.getScope().map(scope -> scope.findAll(MethodCallExpr.class).stream()
                        .anyMatch(parent -> parent.getNameAsString().equals("verify"))).orElse(false));
    }
    private boolean expectedException(MethodDeclaration test, String expected, String method) {
        return test.findAll(MethodCallExpr.class).stream().filter(call -> call.getNameAsString().equals("assertThrows"))
                .anyMatch(call -> call.toString().contains(expected) && call.getArguments().stream().skip(1)
                        .flatMap(argument -> argument.findAll(MethodCallExpr.class).stream())
                        .anyMatch(invocation -> invocation.getNameAsString().equals(method)));
    }
    private static String exceptionName(String expression) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:new\\s+)?([\\w.]+)(?:::new)?").matcher(expression);
        return matcher.matches() ? matcher.group(1).substring(matcher.group(1).lastIndexOf('.') + 1) : expression;
    }
    private static boolean looksLikeMutatingCall(MethodCallExpr call) {
        try {
            return call.resolve().toAst(MethodDeclaration.class).flatMap(MethodDeclaration::getBody)
                    .map(body -> body.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream()
                            .anyMatch(assign -> assign.getTarget() instanceof com.github.javaparser.ast.expr.FieldAccessExpr
                                    || assign.getTarget() instanceof com.github.javaparser.ast.expr.NameExpr name
                                    && call.resolve().toAst(MethodDeclaration.class).stream()
                                    .flatMap(declaration -> declaration.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream())
                                    .anyMatch(field -> field.getVariable(0).getNameAsString().equals(name.getNameAsString()))))
                    .orElse(false);
        }
        catch (RuntimeException ignored) { return false; }
    }
    private boolean isTestMethod(MethodDeclaration method) {
        return method.getAnnotations().stream().map(a -> a.getName().getIdentifier())
                .anyMatch(n -> Set.of("Test", "ParameterizedTest", "RepeatedTest").contains(n));
    }
    private boolean isTrivialAccessor(MethodDeclaration method) {
        boolean getter = method.getParameters().isEmpty() && (method.getNameAsString().startsWith("get")
                || method.getNameAsString().startsWith("is")) && method.getBody().map(b -> b.getStatements().size() <= 1).orElse(true);
        boolean setter = method.getNameAsString().startsWith("set") && method.getParameters().size() == 1
                && method.findAll(com.github.javaparser.ast.expr.AssignExpr.class).size() == 1
                && method.getBody().map(body -> body.getStatements().size() == 1).orElse(false);
        return getter || setter;
    }
    private boolean resolvesToField(com.github.javaparser.ast.expr.NameExpr expression) {
        try { return expression.resolve().isField(); }
        catch (RuntimeException ignored) { return false; }
    }
    private static boolean isTestPath(Path path) { return path.toString().replace('\\', '/').contains("/src/test/"); }
    private static String ownerName(MethodDeclaration method) {
        return method.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                .map(t -> {
                    com.github.javaparser.ast.body.TypeDeclaration<?> type =
                            (com.github.javaparser.ast.body.TypeDeclaration<?>) t;
                    return type.getFullyQualifiedName().orElse(type.getNameAsString());
                }).orElse("");
    }
    private String testName(MethodDeclaration method) { return ownerName(method) + "#" + method.getNameAsString(); }
    private SourceLocation locationFor(MethodDeclaration method, List<ParsedSource> sources) {
        Path file = method.findCompilationUnit().flatMap(c -> c.getStorage().map(s -> s.getPath())).orElse(Path.of("."));
        return location(sources, file, method);
    }
    private SourceLocation location(List<ParsedSource> sources, Path file, MethodDeclaration method) {
        var r = method.getRange().orElse(null);
        Path absolute = sources.stream().filter(s -> s.sourceFile().path().equals(file)).map(s -> s.sourceFile().path()).findFirst().orElse(file);
        return r == null ? new SourceLocation(absolute, -1, -1, -1, -1)
                : new SourceLocation(absolute, r.begin.line, r.begin.column, r.end.line, r.end.column);
    }
    private static String id(int[] next) { return "OBLIGATION-%03d".formatted(next[0]++); }

    private record BoundaryCoverage(List<String> covered, List<String> missing) {}

    private TestObligation stableIdentifier(TestObligation obligation) {
        String key = "%s|%s|%s|%s|%d|%d|%s".formatted(obligation.owner(), obligation.method(), obligation.type(),
                obligation.condition(), obligation.location().startLine(), obligation.location().startColumn(),
                obligation.riskId() == null ? "" : obligation.riskId());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String stable = java.util.HexFormat.of().formatHex(digest, 0, 6).toUpperCase(java.util.Locale.ROOT);
            return new TestObligation("OBLIGATION-" + stable, obligation.type(), obligation.condition(),
                    obligation.owner(), obligation.method(), obligation.location(), obligation.requiredEvidence(),
                    obligation.riskId(), obligation.riskCategory(), obligation.riskSeverity());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
