package com.example.coderiskanalyzer.production.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.baseline.BaselineService;
import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.config.AnalyzerConfigException;
import com.example.coderiskanalyzer.production.config.AnalyzerConfigLoader;
import com.example.coderiskanalyzer.production.finding.Confidence;
import com.example.coderiskanalyzer.production.finding.Diagnostic;
import com.example.coderiskanalyzer.production.finding.DiagnosticSeverity;
import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.judge.CallLimitedSemanticJudge;
import com.example.coderiskanalyzer.production.judge.JudgeResult;
import com.example.coderiskanalyzer.production.judge.SemanticJudge;
import com.example.coderiskanalyzer.production.judge.jev.JevSemanticJudge;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.model.ProjectModel;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.report.Reporter;
import com.example.coderiskanalyzer.production.report.AnalysisCompleteness;
import com.example.coderiskanalyzer.production.report.ScanMetrics;
import com.example.coderiskanalyzer.production.source.SourceFile;
import com.example.coderiskanalyzer.production.rule.AnalysisResult;
import com.example.coderiskanalyzer.production.rule.RuleEngine;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryException;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.suppression.SuppressionMatcher;
import com.example.coderiskanalyzer.testanalysis.TestAdequacyAnalyzer;
import com.example.coderiskanalyzer.testanalysis.TestAdequacyFindings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/** Runs discovery, parsing, analysis, optional semantic review, and reporting for one target project. */
@Component
public class ScanCommand {

    private final SourceDiscoveryService sourceDiscoveryService;
    private final JavaSourceParser javaSourceParser;
    private final ProgramModelBuilder programModelBuilder;
    private final RuleEngine ruleEngine;
    private final List<Reporter> reporters;
    private final AnalyzerConfigLoader configLoader;
    private final SuppressionMatcher suppressionMatcher;
    private final BaselineService baselineService;
    private final TestAdequacyAnalyzer testAdequacyAnalyzer;
    private final TestAdequacyFindings testAdequacyFindings;

    @Autowired
    public ScanCommand(SourceDiscoveryService sourceDiscoveryService, JavaSourceParser javaSourceParser,
                       ProgramModelBuilder programModelBuilder, RuleEngine ruleEngine, List<Reporter> reporters,
                       AnalyzerConfigLoader configLoader, SuppressionMatcher suppressionMatcher,
                       BaselineService baselineService, TestAdequacyAnalyzer testAdequacyAnalyzer,
                       TestAdequacyFindings testAdequacyFindings) {
        this.sourceDiscoveryService = sourceDiscoveryService;
        this.javaSourceParser = javaSourceParser;
        this.programModelBuilder = programModelBuilder;
        this.ruleEngine = ruleEngine;
        this.reporters = List.copyOf(reporters);
        this.configLoader = configLoader;
        this.suppressionMatcher = suppressionMatcher;
        this.baselineService = baselineService;
        this.testAdequacyAnalyzer = testAdequacyAnalyzer;
        this.testAdequacyFindings = testAdequacyFindings;
    }

    public ScanCommand(SourceDiscoveryService sourceDiscoveryService, JavaSourceParser javaSourceParser,
                       ProgramModelBuilder programModelBuilder, RuleEngine ruleEngine, List<Reporter> reporters) {
        this(sourceDiscoveryService, javaSourceParser, programModelBuilder, ruleEngine, reporters,
                new AnalyzerConfigLoader(), new SuppressionMatcher(), new BaselineService(),
                new TestAdequacyAnalyzer(), new TestAdequacyFindings());
    }

    @Command(name = "scan", description = "Scan a Java project for code risks")
    public String scan(
            @Argument(index = 0, description = "Path to the target project") String rawPath,
            @Option(longName = "format", defaultValue = "", description = "Output format: console, json, or sarif") String format,
            @Option(longName = "output", defaultValue = "", description = "Write report to this file") String output,
            @Option(longName = "jev", defaultValue = "false", description = "Enable optional JEV semantic review") String jev,
            @Option(longName = "fail-on", defaultValue = "", description = "Fail when confirmed findings meet this severity (or off)") String failOn,
            @Option(longName = "baseline", defaultValue = "", description = "Ignore findings already in a baseline file") String baseline,
            @Option(longName = "files-file", defaultValue = "", description = "Read project-relative changed Java paths from this file") String filesFile,
            @Option(longName = "verbose", defaultValue = "false", description = "Print scan phase timings to stderr") String verbose,
            @Option(longName = "execution-evidence", defaultValue = "", description = "Import reviewed DB test evidence manifest") String executionEvidence) {
        return scanForCli(new ScanRequest(rawPath, format, output, jev, failOn, baseline, filesFile,
                Boolean.parseBoolean(verbose), executionEvidence)).output();
    }

    public String scan(String rawPath, String format, String output, String jev, String failOn,
                       String baseline, String filesFile, String verbose) {
        return scan(rawPath, format, output, jev, failOn, baseline, filesFile, verbose, "");
    }

    public String scan(String rawPath, String format, String output, String jev) {
        return scanForCli(new ScanRequest(rawPath, format, output, jev, "", "", "", false)).output();
    }

    public ScanResponse scanForCli(ScanRequest request) {
        Path target;
        try {
            target = Path.of(request.path()).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return error("Error: invalid project path", 2);
        }
        if (!Files.exists(target)) return error("Error: path does not exist: " + request.path(), 2);
        if (!Files.isDirectory(target)) return error("Error: path is not a directory: " + request.path(), 2);

        AnalyzerConfig config;
        try {
            config = configLoader.load(target);
        } catch (AnalyzerConfigException exception) {
            return new ScanResponse("Error: " + exception.getMessage(), 2,
                    List.of(new Diagnostic(DiagnosticSeverity.ERROR, "CONFIG-001", exception.getMessage(),
                            target.resolve(".code-risk.yml"))), emptyMetrics());
        }
        for (AnalyzerConfig.SuppressionConfig suppression : config.suppressions()) {
            if (!suppression.rule().equals("*") && !ruleEngine.hasRule(suppression.rule())
                    && !suppression.rule().equals("TEST-001")) {
                String message = "Suppression references unknown rule " + suppression.rule();
                return new ScanResponse("Error: " + message, 2,
                        List.of(new Diagnostic(DiagnosticSeverity.ERROR, "CONFIG-003", message,
                                target.resolve(".code-risk.yml"))), emptyMetrics());
            }
        }

        String format = blank(request.format()) ? config.reportFormat() : request.format();
        Reporter reporter = reporters.stream().filter(candidate -> candidate.format().equalsIgnoreCase(format))
                .findFirst().orElse(null);
        if (reporter == null) return error("Error: invalid output format: " + format, 2);
        if (!"true".equalsIgnoreCase(request.jev()) && !"false".equalsIgnoreCase(request.jev())) {
            return error("Error: --jev must be true or false", 2);
        }
        Severity threshold;
        try {
            threshold = parseFailOn(request.failOn(), config.failSeverity());
        } catch (IllegalArgumentException exception) {
            return error("Error: " + exception.getMessage(), 2);
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        List<Diagnostic> unmatchedChanged = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        Map<String, Long> timings = new LinkedHashMap<>();
        long started = System.nanoTime();
        List<SourceFile> sourceFiles;
        List<SourceFile> discoveredFiles;
        Set<Path> productionAdequacyScope = new java.util.LinkedHashSet<>();
        Set<Path> testAdequacyScope = new java.util.LinkedHashSet<>();
        List<Path> symbolSourceRoots;
        boolean changedFilesMode = !blank(request.filesFile());
        try {
            List<SourceFile> productionFiles = sourceDiscoveryService.discover(target, false, config.sourceRoots(), config.excludes(), unreadable)
                    .stream().filter(file -> !isTestSource(file.path())).toList();
            List<SourceFile> testFiles = sourceDiscoveryService.discover(target, true, List.of(), config.excludes(), unreadable)
                    .stream().filter(file -> isTestSource(file.path())).toList();
            discoveredFiles = new ArrayList<>(productionFiles);
            discoveredFiles.addAll(testFiles);
            discoveredFiles = discoveredFiles.stream().collect(java.util.stream.Collectors.toMap(
                    file -> file.path(), file -> file, (left, right) -> left, java.util.LinkedHashMap::new))
                    .values().stream().sorted(java.util.Comparator.comparing(file -> file.path().toString())).toList();
            symbolSourceRoots = discoveredFiles.stream().map(SourceFile::root).distinct().toList();
            Set<Path> changedPaths = readFilesFile(target, request.filesFile());
            sourceFiles = changedPaths == null ? discoveredFiles
                    : discoveredFiles.stream().filter(file -> changedPaths.contains(file.path())).toList();
            if (changedPaths != null) {
                Set<Path> discoveredPaths = discoveredFiles.stream().map(SourceFile::path)
                        .collect(java.util.stream.Collectors.toSet());
                changedPaths.stream().filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !discoveredPaths.contains(path)).sorted().forEach(path -> unmatchedChanged.add(
                                new Diagnostic(DiagnosticSeverity.WARNING, "CHANGED-001",
                                        "Changed file was not found among discovered Java sources and was not scanned.", path)));
            }
            sourceFiles.stream().filter(file -> !isTestSource(file.path())).map(SourceFile::path).forEach(productionAdequacyScope::add);
            sourceFiles.stream().filter(file -> isTestSource(file.path())).map(SourceFile::path).forEach(testAdequacyScope::add);
        } catch (IOException | RuntimeException exception) {
            return new ScanResponse("Error: " + safeMessage(exception), 2,
                    List.of(new Diagnostic(DiagnosticSeverity.ERROR, "SOURCE-001", safeMessage(exception), null)), emptyMetrics());
        }
        diagnostics.addAll(unmatchedChanged);
        unreadable.stream().distinct().sorted().forEach(path -> diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING,
                "SOURCE-002", "Path could not be read during source discovery and was not analyzed.", path)));
        timings.put("discovery", elapsedMillis(started));
        if (sourceFiles.isEmpty() && !changedFilesMode) return error("Error: no Java source found under configured source roots: " + target, 2);

        started = System.nanoTime();
        List<SourceFile> initialParseFiles = new ArrayList<>(sourceFiles);
        boolean hasProductionSeed = sourceFiles.stream().anyMatch(file -> !isTestSource(file.path()));
        if (!changedFilesMode || hasProductionSeed) {
            discoveredFiles.stream().filter(file -> isTestSource(file.path())).forEach(initialParseFiles::add);
            discoveredFiles.stream().filter(file -> isTestSource(file.path())).map(SourceFile::path).forEach(testAdequacyScope::add);
        }
        initialParseFiles = initialParseFiles.stream().collect(java.util.stream.Collectors.toMap(
                SourceFile::path, file -> file, (left, right) -> left, java.util.LinkedHashMap::new)).values().stream().toList();
        var parsedSources = new ArrayList<>(javaSourceParser.parse(initialParseFiles, symbolSourceRoots,
                config.classpath().stream().map(target::resolve).toList()));
        Set<Path> resolvedProductionPaths = new java.util.LinkedHashSet<>();
        for (var parsed : parsedSources) {
            if (parsed.compilationUnit() == null) continue;
            parsed.compilationUnit().findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
                try {
                    call.resolve().toAst(com.github.javaparser.ast.body.MethodDeclaration.class)
                            .flatMap(method -> method.findCompilationUnit())
                            .flatMap(unit -> unit.getStorage().map(storage -> storage.getPath().toAbsolutePath().normalize()))
                            .filter(path -> !isTestSource(path)).ifPresent(resolvedProductionPaths::add);
                } catch (RuntimeException ignored) {
                    // Unresolved calls are handled conservatively by TestAdequacyAnalyzer.
                }
            });
        }
        Map<Path, SourceFile> discoveredByPath = discoveredFiles.stream().collect(java.util.stream.Collectors.toMap(
                file -> file.path(), file -> file, (left, right) -> left));
        List<SourceFile> productionContext = resolvedProductionPaths.stream().filter(path -> !productionAdequacyScope.contains(path))
                .map(discoveredByPath::get).filter(java.util.Objects::nonNull).toList();
        if (!productionContext.isEmpty()) {
            parsedSources.addAll(javaSourceParser.parse(productionContext, symbolSourceRoots,
                    config.classpath().stream().map(target::resolve).toList()));
            if (!hasProductionSeed) productionAdequacyScope.addAll(productionContext.stream().map(SourceFile::path).toList());
        }
        // Keep reachable production helpers as context without widening the changed-file risk entry points.
        Set<Path> parsedPaths = parsedSources.stream().map(p -> p.sourceFile().path())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        while (hasProductionSeed) {
            Set<Path> helperPaths = new java.util.LinkedHashSet<>();
            for (var parsed : parsedSources) {
                if (isTestSource(parsed.sourceFile().path()) || parsed.compilationUnit() == null) continue;
                for (var call : parsed.compilationUnit().findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
                    try {
                        call.resolve().toAst(com.github.javaparser.ast.body.MethodDeclaration.class)
                                .flatMap(method -> method.findCompilationUnit())
                                .flatMap(unit -> unit.getStorage().map(storage -> storage.getPath().toAbsolutePath().normalize()))
                                .filter(path -> !parsedPaths.contains(path) && discoveredByPath.containsKey(path)
                                        && !isTestSource(path)).ifPresent(helperPaths::add);
                    } catch (RuntimeException ignored) {
                        // Unresolved calls remain visible in completeness metrics.
                    }
                }
            }
            if (helperPaths.isEmpty()) break;
            parsedPaths.addAll(helperPaths);
            parsedSources.addAll(javaSourceParser.parse(helperPaths.stream().map(discoveredByPath::get).toList(),
                    symbolSourceRoots, config.classpath().stream().map(target::resolve).toList()));
        }
        timings.put("parse", elapsedMillis(started));
        int parsedCount = 0;
        int failedCount = 0;
        for (var parsed : parsedSources) {
            if (parsed.errors().isEmpty()) parsedCount++;
            else {
                boolean failed = parsed.compilationUnit() == null;
                if (failed) failedCount++;
                diagnostics.add(new Diagnostic(failed ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                        "PARSE-001", failed ? "Could not parse Java source; this file was skipped."
                                : "Java source was parsed with recoverable errors.", parsed.sourceFile().path()));
            }
        }

        started = System.nanoTime();
        Set<Path> seedPaths = sourceFiles.stream().map(SourceFile::path).collect(java.util.stream.Collectors.toSet());
        var productionSources = parsedSources.stream().filter(parsed -> seedPaths.contains(parsed.sourceFile().path()))
                .filter(parsed -> !isTestSource(parsed.sourceFile().path())).toList();
        var projectModel = programModelBuilder.build(productionSources);
        AnalysisContext context = new AnalysisContext(projectModel, productionSources,
                new com.example.coderiskanalyzer.production.analysis.callgraph.CallGraphBuilder().build(productionSources),
                parsedSources.stream().filter(p -> !isTestSource(p.sourceFile().path())).toList());
        timings.put("model", elapsedMillis(started));

        started = System.nanoTime();
        AnalysisResult analysis = ruleEngine.analyzeDetailed(context, config.rules());
        diagnostics.addAll(analysis.diagnostics());
        List<Finding> allFindings = new ArrayList<>(analysis.findings());
        var adequacy = testAdequacyAnalyzer.analyze(parsedSources, analysis.findings(),
                productionAdequacyScope, testAdequacyScope);
        if (!blank(request.executionEvidence())) {
            try {
                adequacy = new com.example.coderiskanalyzer.testanalysis.ExecutionEvidenceLinker()
                        .link(target, request.executionEvidence(), adequacy, parsedSources);
            } catch (IOException | RuntimeException exception) {
                String message = "Could not use execution evidence: " + safeMessage(exception);
                return new ScanResponse("Error: " + message, 2,
                        append(diagnostics, new Diagnostic(DiagnosticSeverity.ERROR, "EXECUTION-001", message, null)),
                        emptyMetrics());
            }
        }
        var completeness = AnalysisCompleteness.measure(
                productionSources, adequacy, discoveredFiles.size(), sourceFiles.size(),
                (int) parsedSources.stream().filter(p -> !seedPaths.contains(p.sourceFile().path())).count(),
                config, changedFilesMode);
        allFindings.addAll(applyTestRuleSettings(
                testAdequacyFindings.convert(adequacy),
                config.rules().get("TEST-001")));
        List<Finding> findings = suppressionMatcher.apply(target, allFindings, config.suppressions());
        if (!blank(request.baseline())) {
            try {
                Path baselinePath = safeProjectPath(target, request.baseline(), "baseline");
                findings = baselineService.excludeExisting(target, baselinePath, findings);
            } catch (IOException | RuntimeException exception) {
                String message = "Could not use baseline: " + safeMessage(exception);
                return new ScanResponse("Error: " + message, 2,
                        append(diagnostics, new Diagnostic(DiagnosticSeverity.ERROR, "BASELINE-001", message, null)),
                        emptyMetrics());
            }
        }
        boolean jevFailed = false;
        if ("true".equalsIgnoreCase(request.jev())) {
            String apiKey = System.getenv("TYPESAFE_API_KEY");
            if (apiKey == null || apiKey.isBlank()) return error("Error: --jev requested but TYPESAFE_API_KEY is missing", 2);
            try (JevSemanticJudge jevJudge = new JevSemanticJudge(apiKey)) {
                Integer maxCalls = jevCallCap(config.ciPolicy().maxJevCalls(), System.getenv(JEV_CALL_CAP_ENV));
                SemanticJudge judge = maxCalls == null ? jevJudge : new CallLimitedSemanticJudge(jevJudge, maxCalls);
                findings = reviewCandidates(findings, judge, projectModel);
                if (judge instanceof CallLimitedSemanticJudge limited && limited.skipped() > 0)
                    diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "JEV-002",
                            "JEV call limit (jev.max-calls=" + maxCalls + ") reached; " + limited.skipped()
                                    + " review(s) skipped and static results retained.", null));
            } catch (RuntimeException exception) {
                jevFailed = true;
                diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "JEV-001",
                        "JEV review failed; static findings were retained.", null));
            }
        }
        timings.put("analysis", elapsedMillis(started));

        boolean completenessGateFailed = config.ciPolicy().maxUnknownRatio() != null
                && completeness.unknownRatio() > config.ciPolicy().maxUnknownRatio();
        boolean testGapGateFailed = config.ciPolicy().newTestGaps() && findings.stream()
                .filter(f -> f.ruleId().equals("TEST-001") && f.status() != FindingStatus.SUPPRESSED)
                .flatMap(f -> f.evidence().stream()).anyMatch(e -> e.type().equals("TEST_OBLIGATION")
                        && Set.of("MISSING", "PARTIAL").contains(e.attributes().get("coverageStatus")));
        if (completenessGateFailed) diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "CI-UNKNOWN",
                "UNKNOWN test-obligation ratio exceeds fail.max-unknown-ratio.", null));
        if (testGapGateFailed) diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "CI-TEST-GAPS",
                "New MISSING/PARTIAL test obligations remain after suppression and baseline.", null));
        boolean jevGateFailed = jevFailed && config.ciPolicy().onJevFailure();
        if (jevGateFailed) diagnostics.add(new Diagnostic(DiagnosticSeverity.WARNING, "CI-JEV",
                "JEV review failed and fail.on-jev-failure is enabled.", null));
        ScanMetrics metrics = metrics(parsedCount, failedCount, projectModel, timings, completeness);
        String report;
        try {
            timings.put("report", 0L);
            metrics = metrics(parsedCount, failedCount, projectModel, timings, completeness);
            long reportStart = System.nanoTime();
            report = reporter.report(target, sourceFiles.size(), findings, diagnostics, metrics, analysis.concurrencyAssessments());
            timings.put("report", elapsedMillis(reportStart));
            metrics = metrics(parsedCount, failedCount, projectModel, timings, completeness);
            report = reporter.report(target, sourceFiles.size(), findings, diagnostics, metrics, analysis.concurrencyAssessments());
        } catch (RuntimeException exception) {
            return new ScanResponse("Error: could not render report", 3,
                    append(diagnostics, new Diagnostic(DiagnosticSeverity.ERROR, "REPORT-001",
                            "Report rendering failed with " + exception.getClass().getSimpleName(), null)), metrics);
        }

        if (!blank(request.output())) {
            try {
                Path outputPath = safeProjectPath(target, request.output(), "output");
                if (outputPath.equals(target) || Files.isDirectory(outputPath)
                        || outputPath.getFileName().toString().endsWith(".java")) {
                    throw new IOException("output must be a report file inside the project and must not target Java source");
                }
                writeAtomically(outputPath, report);
                report = "Report written to " + outputPath;
            } catch (IOException | RuntimeException exception) {
                return new ScanResponse("Error: could not write output file: " + safeMessage(exception), 2,
                        append(diagnostics, new Diagnostic(DiagnosticSeverity.ERROR, "OUTPUT-001",
                                safeMessage(exception), null)), metrics);
            }
        }

        if (request.verbose()) System.err.println("Code Risk Analyzer phase timings (ms): " + timings);
        boolean ruleFailure = diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR
                && (diagnostic.code().startsWith("RULE-") || diagnostic.code().startsWith("PARSE-")));
        boolean gateFailed = threshold != null && findings.stream().anyMatch(finding -> (finding.status() == FindingStatus.CONFIRMED
                || config.ciPolicy().includeReview() && finding.status() == FindingStatus.REVIEW)
                && severityAtLeast(finding.severity(), threshold)
                && confidenceAtLeast(finding.confidence(), config.failConfidence()));
        return new ScanResponse(report, ruleFailure ? 3 : (gateFailed || completenessGateFailed || testGapGateFailed || jevGateFailed) ? 1 : 0, diagnostics, metrics);
    }

    public void createBaseline(String rawPath, String baselineFile) throws IOException {
        Path root = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Project path is not a directory");
        AnalyzerConfig config = configLoader.load(root);
        if (config.suppressions().stream().anyMatch(suppression -> !suppression.rule().equals("*")
                && !ruleEngine.hasRule(suppression.rule()) && !suppression.rule().equals("TEST-001")))
            throw new IOException("Configuration has a suppression for an unknown rule");
        List<SourceFile> sources = new ArrayList<>(sourceDiscoveryService.discover(root, false, config.sourceRoots(), config.excludes())
                .stream().filter(file -> !isTestSource(file.path())).toList());
        sources.addAll(sourceDiscoveryService.discover(root, true, List.of(), config.excludes()).stream()
                .filter(file -> isTestSource(file.path())).toList());
        if (sources.isEmpty()) throw new IOException("No Java source found; baseline was not written");
        var parsed = javaSourceParser.parse(sources, sources.stream().map(SourceFile::root).distinct().toList(),
                config.classpath().stream().map(root::resolve).toList());
        if (parsed.stream().anyMatch(source -> !source.errors().isEmpty() || source.compilationUnit() == null)) {
            throw new IOException("Source parse failed; baseline was not written");
        }
        var production = parsed.stream().filter(source -> !isTestSource(source.sourceFile().path())).toList();
        var model = programModelBuilder.build(production);
        AnalysisResult analysis = ruleEngine.analyzeDetailed(new AnalysisContext(model, production), config.rules());
        if (analysis.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)) {
            throw new IOException("Rule analysis failed; baseline was not written");
        }
        List<Finding> findings = new ArrayList<>(analysis.findings());
        findings.addAll(applyTestRuleSettings(
                testAdequacyFindings.convert(testAdequacyAnalyzer.analyze(parsed, analysis.findings())),
                config.rules().get("TEST-001")));
        findings = suppressionMatcher.apply(root, findings, config.suppressions());
        Path output = safeProjectPath(root, baselineFile, "baseline output");
        if (output.getFileName().toString().endsWith(".java") || output.equals(root.resolve(".code-risk.yml"))) {
            throw new IOException("baseline output must not overwrite source or analyzer configuration");
        }
        baselineService.create(root, output, findings);
    }

    public List<Finding> reviewCandidates(List<Finding> findings, SemanticJudge judge) {
        return reviewCandidates(findings, judge, new ProjectModel(List.of()));
    }

    /** Caller's JEV call cap (e.g. the ai command) for projects whose .code-risk.yml sets no jev.max-calls. */
    public static final String JEV_CALL_CAP_ENV = "CODE_RISK_JEV_MAX_CALLS";

    /** jev.max-calls wins; otherwise a valid non-negative integer from the environment; otherwise no cap. */
    public static Integer jevCallCap(Integer configured, String environment) {
        if (configured != null) return configured;
        if (environment == null || environment.isBlank()) return null;
        try {
            int value = Integer.parseInt(environment.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Longest method source sent to JEV; longer bodies are cut so one finding cannot dominate the request. */
    static final int MAX_JEV_SOURCE_CHARS = 6000;

    /**
     * Sends each reviewable finding with the source of the method it points at, so JEV can judge what the static
     * rule could not (protections on the call path, business meaning). Files with a hardcoded-secret finding are
     * never sent. The answer is attached as evidence; only IDEM-001 keeps its existing confirmation path.
     */
    public List<Finding> reviewCandidates(List<Finding> findings, SemanticJudge judge, ProjectModel model) {
        Set<Path> secretFiles = findings.stream().filter(finding -> finding.ruleId().equals("SEC-005"))
                .map(finding -> finding.location() == null ? null : finding.location().file())
                .filter(java.util.Objects::nonNull).map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());
        List<Finding> reviewed = new ArrayList<>();
        for (var finding : findings) {
            if (finding.status() == FindingStatus.SUPPRESSED) {
                reviewed.add(finding);
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("evidence", finding.evidence().stream().map(item -> Map.of(
                    "type", item.type(), "message", item.message(), "attributes", item.attributes())).toList());
            var assessment = finding.concurrencyAssessment();
            if (assessment != null) evidence.put("concurrency", Map.of("outcome", assessment.outcome().name(),
                    "evidencePath", assessment.evidencePath(), "assumptions", assessment.assumptions(),
                    "missingEvidence", assessment.missingEvidence(), "safeguards", assessment.safeguards()));
            enclosingMethodSource(finding, model, secretFiles).ifPresent(source -> evidence.put("source", source));
            if (isUncertainTestGap(finding)) {
                reviewed.add(withTestReview(finding, judge.reviewTestAdequacy(finding, evidence)));
                continue;
            }
            if (RISK_REVIEW_RULES.contains(finding.ruleId())) {
                reviewed.add(withRiskReview(finding, judge.reviewRisk(finding, evidence)));
                continue;
            }
            if (!finding.ruleId().equals("IDEM-001")) {
                reviewed.add(finding);
                continue;
            }
            JudgeResult result = judge.judge(finding, evidence);
            if (result.reviewed() && result.probability() >= 0.9) {
                reviewed.add(new Finding(finding.ruleId(), finding.category(), finding.severity(), Confidence.HIGH,
                        finding.title(), finding.message(), finding.location(), finding.evidence(), FindingStatus.CONFIRMED,
                        finding.concurrencyAssessment()));
            } else reviewed.add(finding);
        }
        return List.copyOf(reviewed);
    }

    /** Rules whose JEV opinion is attached as evidence only; status, severity and confidence stay static. SEC-005 is never sent. */
    public static final Set<String> RISK_REVIEW_RULES = Set.of("IDEM-002", "IDEM-003", "IDEM-004", "CON-001", "CON-002",
            "CON-003", "CON-004", "CON-005", "TX-001", "TX-002", "TX-003", "TX-004", "TX-005",
            "SEC-001", "SEC-002", "SEC-003", "SEC-004", "SEC-006", "SEC-007", "REL-001");

    private java.util.Optional<Map<String, Object>> enclosingMethodSource(Finding finding, ProjectModel model, Set<Path> secretFiles) {
        if (finding.location() == null || finding.location().file() == null) return java.util.Optional.empty();
        Path file = finding.location().file().toAbsolutePath().normalize();
        if (secretFiles.contains(file)) return java.util.Optional.empty();
        int line = finding.location().startLine();
        return model.classes().stream().flatMap(type -> type.methods().stream())
                .filter(method -> method.location() != null && method.location().file() != null
                        && method.location().file().toAbsolutePath().normalize().equals(file)
                        && method.location().startLine() <= line && line <= method.location().endLine()
                        && !method.body().isEmpty())
                .min(java.util.Comparator.comparingInt(method -> method.location().endLine() - method.location().startLine()))
                .map(method -> {
                    String body = method.body();
                    boolean truncated = body.length() > MAX_JEV_SOURCE_CHARS;
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("method", method.ownerClass() + "#" + method.name());
                    source.put("annotations", method.annotations().stream().sorted().toList());
                    source.put("body", truncated ? body.substring(0, MAX_JEV_SOURCE_CHARS) : body);
                    source.put("truncated", truncated);
                    return source;
                });
    }

    private Finding withRiskReview(Finding finding, JudgeResult result) {
        if (!result.reviewed()) return finding;
        var attributes = new java.util.LinkedHashMap<String, Object>();
        attributes.put("riskProbability", result.probability());
        attributes.put("staticStatus", finding.status().name());
        attributes.put("unverified", "Advisory only: the probability is not proof and the static status is unchanged.");
        var evidence = new ArrayList<>(finding.evidence());
        evidence.add(new Evidence("JEV_RISK_REVIEW", result.explanation(), finding.location(), attributes));
        return new Finding(finding.ruleId(), finding.category(), finding.severity(), finding.confidence(),
                finding.title(), finding.message(), finding.location(), List.copyOf(evidence), finding.status(), finding.concurrencyAssessment());
    }

    private boolean isUncertainTestGap(Finding finding) {
        return finding.ruleId().equals("TEST-001") && finding.evidence().stream().anyMatch(e ->
                e.type().equals("TEST_OBLIGATION")
                        && Set.of("PARTIAL", "UNKNOWN").contains(e.attributes().get("coverageStatus")));
    }

    /** Adds the JEV opinion as evidence only; the static status, severity and confidence are preserved. */
    private Finding withTestReview(Finding finding, com.example.coderiskanalyzer.production.judge.TestAdequacyReview review) {
        if (!review.reviewed()) return finding;
        var obligation = finding.evidence().stream().filter(e -> e.type().equals("TEST_OBLIGATION")).findFirst().orElseThrow();
        var attributes = new java.util.LinkedHashMap<String, Object>();
        attributes.put("adequateProbability", review.adequateProbability());
        attributes.put("staticStatus", obligation.attributes().get("coverageStatus"));
        attributes.put("requiredEvidence", obligation.attributes().getOrDefault("missingCases", java.util.List.of()));
        attributes.put("unverified", "Advisory only: assertion semantics are not proven and the static status is unchanged.");
        var evidence = new ArrayList<>(finding.evidence());
        evidence.add(new Evidence("JEV_TEST_REVIEW", review.explanation(), finding.location(), attributes));
        return new Finding(finding.ruleId(), finding.category(), finding.severity(), finding.confidence(),
                finding.title(), finding.message(), finding.location(), List.copyOf(evidence), finding.status(), finding.concurrencyAssessment());
    }

    private Set<Path> readFilesFile(Path root, String fileList) throws IOException {
        if (blank(fileList)) return null;
        Path listPath = safeProjectPath(root, fileList, "files file");
        if (!Files.isRegularFile(listPath) || Files.isSymbolicLink(listPath)) throw new IOException("files file is not a regular file inside the project");
        Set<Path> paths = new java.util.HashSet<>();
        for (String line : Files.readAllLines(listPath)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                path = path.toAbsolutePath().normalize();
                if (!path.startsWith(root)) throw new IOException("changed file path escapes the project root");
            } else {
                path = root.resolve(path).normalize();
                if (!path.startsWith(root)) throw new IOException("changed file path escapes the project root");
            }
            paths.add(path);
        }
        return Set.copyOf(paths);
    }

    private Path safeProjectPath(Path root, String value, String description) throws IOException {
        Path path = Path.of(value);
        Path normalized = path.isAbsolute() ? path.toAbsolutePath().normalize() : root.resolve(path).normalize();
        if (!normalized.startsWith(root)) throw new IOException(description + " path must stay inside the project root");
        Path current = root;
        for (Path part : root.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException(description + " path may not traverse symbolic links");
        }
        return normalized;
    }

    private void writeAtomically(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("output parent directory is missing");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".code-risk-report-", ".tmp");
        try {
            Files.writeString(temporary, content);
            try {
                Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Severity parseFailOn(String value, Severity fallback) {
        if (blank(value)) return fallback;
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("none")) return null;
        try { return Severity.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("--fail-on must be a severity or off"); }
    }

    private boolean severityAtLeast(Severity severity, Severity threshold) {
        return severity.ordinal() >= threshold.ordinal();
    }

    private boolean confidenceAtLeast(Confidence confidence, Confidence threshold) {
        return confidence.ordinal() >= threshold.ordinal();
    }

    private ScanMetrics metrics(int parsedCount, int failedCount, ProjectModel model,
                                Map<String, Long> timings, AnalysisCompleteness completeness) {
        return new ScanMetrics(parsedCount, failedCount, model.classes().size(),
                model.classes().stream().mapToInt(type -> type.methods().size()).sum(), timings, completeness);
    }

    private List<Diagnostic> append(List<Diagnostic> diagnostics, Diagnostic diagnostic) {
        List<Diagnostic> result = new ArrayList<>(diagnostics);
        result.add(diagnostic);
        return List.copyOf(result);
    }

    private ScanResponse error(String message, int exitCode) {
        return new ScanResponse(message, exitCode, List.of(), emptyMetrics());
    }

    private ScanMetrics emptyMetrics() { return new ScanMetrics(0, 0, 0, 0, Map.of()); }
    private long elapsedMillis(long start) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean isTestSource(Path path) {
        return path.toString().replace('\\', '/').contains("/src/test/");
    }

    private List<Finding> applyTestRuleSettings(List<Finding> findings, AnalyzerConfig.RuleSettings settings) {
        if (settings == null) return findings;
        if (!settings.enabled()) return List.of();
        if (settings.severity() == null) return findings;
        return findings.stream().map(finding -> new Finding(finding.ruleId(), finding.category(), settings.severity(),
                finding.confidence(), finding.title(), finding.message(), finding.location(), finding.evidence(), finding.status(),
                finding.concurrencyAssessment())).toList();
    }

    private String safeMessage(Exception exception) {
        if (exception instanceof AnalyzerConfigException || exception instanceof SourceDiscoveryException
                || exception instanceof IOException || exception instanceof IllegalArgumentException) {
            return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
        return "analysis failed with " + exception.getClass().getSimpleName();
    }
}
