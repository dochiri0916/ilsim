package com.example.coderiskanalyzer.mutation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.source.SourceFile;
import com.example.coderiskanalyzer.testanalysis.TestAdequacyAnalyzer;
import com.example.coderiskanalyzer.testanalysis.TestAdequacyResult;
import com.example.coderiskanalyzer.testanalysis.TestObligation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/** Explicit opt-in dynamic verification. This command executes the target Gradle build and tests. */
@Component
public class MutationCommand {
    private final MutationRunner runner;
    private final SourceDiscoveryService discovery;
    private final JavaSourceParser parser;
    private final TestAdequacyAnalyzer adequacyAnalyzer;
    private final MutationObligationMatcher matcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public MutationCommand(MutationRunner runner, SourceDiscoveryService discovery, JavaSourceParser parser,
                           TestAdequacyAnalyzer adequacyAnalyzer, MutationObligationMatcher matcher) {
        this.runner = runner;
        this.discovery = discovery;
        this.parser = parser;
        this.adequacyAnalyzer = adequacyAnalyzer;
        this.matcher = matcher;
    }

    @Command(name = "mutation", description = "Run opt-in PIT mutation verification (executes the target Gradle build and tests)")
    public String mutation(@Argument(index = 0, description = "Path to the Gradle target project") String rawPath,
                           @Option(longName = "files-file", defaultValue = "", description = "Limit mutation targets to changed Java files") String filesFile) {
        return verify(rawPath, filesFile).output();
    }

    public Response verify(String rawPath, String filesFile) {
        Path root;
        try { root = Path.of(rawPath).toAbsolutePath().normalize(); }
        catch (RuntimeException exception) { return error("Invalid target project path.", 2); }
        if (!Files.isDirectory(root)) return error("Target project directory does not exist.", 2);
        try {
            var config = new com.example.coderiskanalyzer.production.config.AnalyzerConfigLoader().load(root);
            var policy = config.ciPolicy();
            List<SourceFile> production = discovery.discover(root, false, config.sourceRoots(), config.excludes()).stream().filter(f -> !isTest(f.path())).toList();
            List<SourceFile> tests = discovery.discover(root, true, List.of(), config.excludes()).stream().filter(f -> isTest(f.path())).toList();
            List<SourceFile> all = new ArrayList<>(production);
            all.addAll(tests);
            List<Path> roots = all.stream().map(SourceFile::root).distinct().toList();
            List<ParsedSource> parsed = parser.parse(all, roots, config.classpath().stream().map(root::resolve).toList());
            List<SourceFile> seeds = readSeeds(root, filesFile, all);
            java.util.Map<String, List<String>> projectTargets = filesFile == null || filesFile.isBlank()
                    ? java.util.Map.of() : resolveTargets(root, seeds, parsed);
            List<String> targetClasses = projectTargets.values().stream().flatMap(List::stream).distinct().sorted().toList();
            if (filesFile != null && !filesFile.isBlank() && targetClasses.isEmpty())
                return new Response(render(new Report("NO_MUTATION_TARGETS", List.of(),
                        "No production classes were selected by the changed Java Seed file."
                                + unmatchedNote(root, filesFile, all), List.of(), List.of())), 0);

            List<TestAdequacyResult> staticResults = adequacyAnalyzer.analyze(parsed, List.of()).stream()
                    .filter(result -> projectTargets.isEmpty() || projectTargets.entrySet().stream().anyMatch(entry ->
                            entry.getValue().contains(result.obligation().owner())
                                    && entry.getKey().equals(gradleProjectPath(root, result.obligation().location().file()))))
                    .toList();
            List<StaticAdequacy> staticAdequacy = staticResults.stream().map(MutationCommand::toStaticAdequacy).toList();
            MutationExecutionResult execution = runner.run(root, projectTargets);
            if (execution.outcome() != MutationExecutionResult.Outcome.MUTATION_COMPLETED)
                return new Response(render(new Report(execution.outcome().name(), List.of(), execution.diagnostic(),
                        targetClasses, staticAdequacy)), execution.outcome() == MutationExecutionResult.Outcome.UNSUPPORTED_BUILD_SYSTEM ? 2 : 3);
            List<MutationObligationMatcher.Match> matches = matcher.match(staticResults, execution.mutations());
            var exceptions = policy.mutationExceptions().stream().collect(java.util.stream.Collectors.toMap(
                    com.example.coderiskanalyzer.production.config.AnalyzerConfig.MutationException::fingerprint,
                    com.example.coderiskanalyzer.production.config.AnalyzerConfig.MutationException::reason));
            var identityCounts = execution.mutations().stream().collect(java.util.stream.Collectors.groupingBy(
                    MutationEvidence::fingerprint, java.util.stream.Collectors.counting()));
            List<MutationReportItem> items = matches.stream().map(match -> new MutationReportItem(
                    match.mutation(), match.mutation().fingerprint(),
                    match.mutation().status() == MutationStatus.SURVIVED
                            && identityCounts.get(match.mutation().fingerprint()) == 1
                            ? exceptions.get(match.mutation().fingerprint()) : null, match.mappingStatus(), match.adequacy() == null ? null
                    : new Obligation(match.adequacy().obligation().id(), match.adequacy().obligation().type(),
                    match.adequacy().obligation().owner(), match.adequacy().obligation().method(),
                    match.adequacy().status().name(), match.adequacy().reason()))).toList();
            long survived = items.stream().filter(i -> isUnverified(i.evidence().status())).count();
            long excepted = items.stream().filter(i -> i.exceptionReason() != null).count();
            boolean failed = policy.maxSurvivedMutations() != null
                    && survived - excepted > policy.maxSurvivedMutations();
            var gate = new MutationGate(policy.maxSurvivedMutations(), survived, excepted, survived - excepted,
                    failed, policy.mutationExceptions().stream()
                    .filter(e -> items.stream().noneMatch(i -> i.exceptionReason() != null
                            && i.fingerprint().equals(e.fingerprint())))
                    .map(com.example.coderiskanalyzer.production.config.AnalyzerConfig.MutationException::fingerprint).toList());
            return new Response(render(new Report(execution.outcome().name(), items,
                    failed ? "Surviving mutations exceed fail.max-survived-mutations; review possible equivalent mutants before adding exceptions." : "",
                    targetClasses, staticAdequacy, gate)), failed ? 1 : 0);
        } catch (IOException | RuntimeException exception) {
            return error("Mutation verification setup failed: " + exception.getClass().getSimpleName(), 2);
        }
    }

    /** Statuses where PIT could not show that a test detects the mutation. */
    private static boolean isUnverified(MutationStatus status) {
        return status == MutationStatus.SURVIVED || status == MutationStatus.NO_COVERAGE
                || status == MutationStatus.RUN_ERROR || status == MutationStatus.MEMORY_ERROR
                || status == MutationStatus.UNKNOWN;
    }

    private String unmatchedNote(Path root, String filesFile, List<SourceFile> all) throws IOException {
        Path list = Path.of(filesFile);
        list = (list.isAbsolute() ? list : root.resolve(list)).toAbsolutePath().normalize();
        Set<Path> known = new LinkedHashSet<>();
        all.forEach(source -> known.add(source.path()));
        List<String> missing = new ArrayList<>();
        for (String line : Files.readAllLines(list)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#") || !value.endsWith(".java")) continue;
            Path path = Path.of(value);
            path = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
            if (!known.contains(path)) missing.add(value);
        }
        return missing.isEmpty() ? "" : " Listed Java files not found among discovered sources: " + String.join(", ", missing) + ".";
    }

    private List<SourceFile> readSeeds(Path root, String filesFile, List<SourceFile> all) throws IOException {
        if (filesFile == null || filesFile.isBlank()) return all;
        Path list = Path.of(filesFile);
        list = (list.isAbsolute() ? list : root.resolve(list)).toAbsolutePath().normalize();
        if (!list.startsWith(root) || hasSymlinkSegment(root, list) || !Files.isRegularFile(list))
            throw new IOException("files file must be a regular file inside the project");
        Set<Path> requested = new LinkedHashSet<>();
        for (String line : Files.readAllLines(list)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            Path path = Path.of(value);
            path = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
            if (!path.startsWith(root)) throw new IOException("changed file path escapes the project root");
            requested.add(path);
        }
        return all.stream().filter(source -> requested.contains(source.path())).toList();
    }

    private boolean hasSymlinkSegment(Path root, Path path) {
        Path current = root;
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, List<String>> resolveTargets(Path root, List<SourceFile> seeds, List<ParsedSource> parsed) {
        java.util.Map<String, Set<String>> targets = new java.util.TreeMap<>();
        Set<Path> seedPaths = seeds.stream().map(SourceFile::path).collect(java.util.stream.Collectors.toSet());
        for (ParsedSource source : parsed) {
            if (!seedPaths.contains(source.sourceFile().path()) || source.compilationUnit() == null) continue;
            if (!isTest(source.sourceFile().path())) {
                source.compilationUnit().findAll(com.github.javaparser.ast.body.TypeDeclaration.class)
                        .forEach(node -> ((com.github.javaparser.ast.body.TypeDeclaration<?>) node).getFullyQualifiedName()
                                .ifPresent(name -> addTarget(targets, gradleProjectPath(root, source.sourceFile().path()), name)));
            } else {
                source.compilationUnit().findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
                    try {
                        call.resolve().toAst(com.github.javaparser.ast.body.MethodDeclaration.class)
                                .filter(method -> method.findCompilationUnit().flatMap(unit -> unit.getStorage())
                                        .map(storage -> !isTest(storage.getPath())).orElse(false))
                                .ifPresent(method -> {
                                    Path productionFile = method.findCompilationUnit().flatMap(unit -> unit.getStorage())
                                            .map(com.github.javaparser.ast.CompilationUnit.Storage::getPath).orElse(root);
                                    method.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                                            .ifPresent(type -> ((com.github.javaparser.ast.body.TypeDeclaration<?>) type)
                                                    .getFullyQualifiedName().ifPresent(name -> addTarget(targets,
                                                            gradleProjectPath(root, productionFile), name)));
                                });
                    } catch (RuntimeException ignored) { }
                });
            }
        }
        return targets.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                java.util.Map.Entry::getKey, entry -> entry.getValue().stream().sorted().toList()));
    }

    private void addTarget(java.util.Map<String, Set<String>> targets, String projectPath, String className) {
        targets.computeIfAbsent(projectPath, ignored -> new java.util.TreeSet<>()).add(className);
    }

    private String gradleProjectPath(Path root, Path source) {
        Path current = source.toAbsolutePath().normalize().getParent();
        while (current != null && current.startsWith(root)) {
            if (Files.isRegularFile(current.resolve("build.gradle")) || Files.isRegularFile(current.resolve("build.gradle.kts"))) {
                if (current.equals(root)) return ":";
                return ":" + root.relativize(current).toString().replace('\\', ':').replace('/', ':');
            }
            if (current.equals(root)) break;
            current = current.getParent();
        }
        return ":";
    }

    private boolean isTest(Path path) { return path.toString().replace('\\', '/').contains("/src/test/"); }

    private String render(Report report) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report); }
        catch (JsonProcessingException exception) { return "{\"outcome\":\"MUTATION_EXECUTION_FAILED\",\"diagnostic\":\"Could not render report\"}"; }
    }

    private static StaticAdequacy toStaticAdequacy(TestAdequacyResult result) {
        TestObligation obligation = result.obligation();
        return new StaticAdequacy(obligation.id(), obligation.type(), obligation.condition(), obligation.owner(),
                obligation.method(), result.status().name(), result.reason(), obligation.location().startLine());
    }

    private Response error(String message, int exit) { return new Response(render(new Report("ERROR", List.of(), message,
            List.of(), List.of())), exit); }

    public record Response(String output, int exitCode) {}
    private record Report(String outcome, List<MutationReportItem> mutations, String diagnostic,
                          List<String> targetClasses, List<StaticAdequacy> staticAdequacy, MutationGate gate) {
        Report(String outcome, List<MutationReportItem> mutations, String diagnostic,
               List<String> targetClasses, List<StaticAdequacy> staticAdequacy) {
            this(outcome, mutations, diagnostic, targetClasses, staticAdequacy, null);
        }
    }
    private record MutationGate(Integer maxSurvivedMutations, long survived, long excepted, long actionableSurvived,
                                boolean failed, List<String> unmatchedExceptions) {}
    private record StaticAdequacy(String id, String type, String condition, String owner, String method,
                                  String status, String reason, int line) {}
    private record MutationReportItem(MutationEvidence evidence, String fingerprint, String exceptionReason,
                                      String mappingStatus, Obligation obligation) {}
    private record Obligation(String id, String type, String owner, String method, String staticStatus, String staticReason) {}
}
