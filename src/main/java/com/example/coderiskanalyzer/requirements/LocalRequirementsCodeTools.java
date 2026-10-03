package com.example.coderiskanalyzer.requirements;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.model.MethodInfo;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.source.SourceFile;
import com.example.coderiskanalyzer.production.rule.RuleEngine;
import com.example.coderiskanalyzer.production.rule.transaction.*;
import com.example.coderiskanalyzer.production.rule.concurrency.*;
import com.example.coderiskanalyzer.production.rule.idempotency.*;
import com.example.coderiskanalyzer.requirements.RequirementsWorkflow.*;

/** No discovery, project configuration loading, shell strings, or automatic source-root expansion. */
public final class LocalRequirementsCodeTools implements CodeTools {
    private final ObjectMapper mapper = new ObjectMapper();
    private final com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector sideEffects = new com.example.coderiskanalyzer.production.analysis.sideeffect.SideEffectDetector();
    private final RuleEngine engine = new RuleEngine(List.of(
            new TransactionalSelfInvocationRule(), new AsyncTransactionBoundaryRule(),
            new RemoteCallInsideTransactionRule(sideEffects), new MultipleWritesWithoutTransactionRule(sideEffects),
            new SwallowedExceptionInTransactionRule(sideEffects), new CheckThenActRaceRule(), new LostUpdateRiskRule(),
            new SharedMutableSingletonStateRule(), new UnsafeAsyncStateAccessRule(), new MessageConsumerSideEffectRule(sideEffects),
            new ExternalSideEffectWithoutIdempotencyKeyRule(sideEffects), new RetryableNonIdempotentOperationRule(sideEffects),
            new EventHandlerWithoutStableBusinessKeyRule(sideEffects)));

    @Override
    public void preflight(ObjectNode input, Scope scope, Phase phase, List<Action> actions, List<Change> changes) {
        requireCodeMode(input);
        project(scope);
        boolean comparison = actions.contains(Action.SCAN) || actions.contains(Action.CODE_COMPARE);
        if (comparison) for (JsonNode requirement : input.path("requirements"))
            for (JsonNode code : requirement.path("mappings").path("code")) approvedPath(scope, code.path("path").asText());
        if (actions.contains(Action.IMPLEMENT) || actions.contains(Action.WRITE_TESTS)) {
            Action write = actions.contains(Action.IMPLEMENT) ? Action.IMPLEMENT : Action.WRITE_TESTS;
            if (write == Action.IMPLEMENT && phase != Phase.IMPLEMENTATION || write == Action.WRITE_TESTS && phase != Phase.TEST_WRITING)
                throw new IllegalArgumentException("Write action does not match phase");
            validateChanges(write, scope, changes);
        }
        for (Action action : actions) if (action == Action.TEST || action == Action.BUILD) {
            requireApproval(scope, action);
            Path wrapper = project(scope).resolve("gradlew");
            if (!Files.isRegularFile(wrapper, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(wrapper))
                throw new IllegalArgumentException("Verification requires an executable project-local gradlew");
        }
    }

    @Override
    public ObjectNode compare(ObjectNode input, Scope scope) {
        requireCodeMode(input);
        Path root = project(scope);
        Map<Path, String> requested = new java.util.LinkedHashMap<>();
        for (JsonNode requirement : input.path("requirements")) for (JsonNode code : requirement.path("mappings").path("code")) {
            String relative = code.path("path").asText();
            requested.put(approvedPath(scope, relative), relative);
        }
        var files = requested.keySet().stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .map(path -> new SourceFile(root, path)).toList();
        // Empty solver roots prevent source resolution from reading unrequested project files.
        var parsed = new JavaSourceParser().parse(files, List.of());
        var model = new ProgramModelBuilder().build(parsed.stream().filter(source -> source.parsedSuccessfully()).toList());
        var analysis = engine.analyzeDetailed(new AnalysisContext(model, parsed.stream().filter(source -> source.parsedSuccessfully()).toList()), Map.of());
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "PARTIAL").put("declaredCodeRevision", input.path("codeRevision").asText())
                .put("limitations", "Mapped files only; dependencies, proxy configuration and actual DB behavior are not proven. No finding does not establish safety.");
        var hashes = result.putArray("analyzedFiles");
        for (var file : files) hashes.addObject().put("path", requested.get(file.path())).put("sha256", hash(file.path()));
        var diagnostics = result.putArray("diagnostics");
        parsed.stream().filter(source -> !source.parsedSuccessfully()).forEach(source ->
                diagnostics.addObject().put("path", requested.get(source.sourceFile().path())).put("status", "PARSE_FAILED"));
        analysis.diagnostics().forEach(diagnostic -> diagnostics.addObject().put("code", diagnostic.code()).put("message", diagnostic.message()));
        ObjectNode requirements = result.putObject("requirements");
        for (var entry : input.path("requirements").properties()) {
            ObjectNode review = requirements.putObject(entry.getKey());
            var unresolved = review.putArray("unresolvedMappings");
            List<MethodInfo> matched = new ArrayList<>();
            for (JsonNode code : entry.getValue().path("mappings").path("code")) {
                Path path = approvedPath(scope, code.path("path").asText());
                String symbol = code.path("symbol").asText().replace(" ", "");
                var methods = model.classes().stream().filter(type -> type.location().file().equals(path))
                        .flatMap(type -> type.methods().stream().filter(method -> matches(type.fullyQualifiedName(), method, symbol))).toList();
                if (methods.size() == 1) matched.add(methods.getFirst());
                else unresolved.addObject().put("path", code.path("path").asText()).put("symbol", code.path("symbol").asText())
                        .put("reason", "Missing, unparsed or ambiguous method mapping");
            }
            for (var perspective : Map.of("transaction", "TX-", "concurrency", "CON-", "idempotency", "IDEM-").entrySet()) {
                ObjectNode item = review.putObject(perspective.getKey());
                item.set("expectations", entry.getValue().path(perspective.getKey()).path("expectations").deepCopy());
                var findings = item.putArray("findings");
                if (entry.getValue().path(perspective.getKey()).path("applicability").asText().equals("NOT_APPLICABLE")) {
                    item.put("status", "NOT_APPLICABLE");
                    continue;
                }
                for (var finding : analysis.findings()) {
                    if (!finding.ruleId().startsWith(perspective.getValue()) || matched.stream().noneMatch(method ->
                            method.location().file().equals(finding.location().file())
                                    && method.location().startLine() <= finding.location().startLine()
                                    && method.location().endLine() >= finding.location().startLine())) continue;
                    ObjectNode observed = findings.addObject();
                    observed.put("ruleId", finding.ruleId()).put("path", requested.get(finding.location().file()))
                            .put("line", finding.location().startLine()).put("message", finding.message())
                            .put("findingStatus", finding.status().name()).put("confidence", finding.confidence().name());
                    var evidence = observed.putArray("evidence");
                    finding.evidence().forEach(fact -> evidence.addObject().put("type", fact.type()).put("message", fact.message())
                            .put("line", fact.location().startLine()));
                }
                item.put("status", findings.isEmpty() ? "INSUFFICIENT_EVIDENCE" : "REVIEW_REQUIRED");
                item.put("reason", findings.isEmpty() ? "No relevant rule evidence linked; confirm implementation and execution behavior"
                        : "Static risk candidates linked to mapped methods; compare with the requirement and verify behavior");
                item.putArray("openQuestions").add("Does the actual implementation satisfy every expectation, including failure and concurrent execution?");
            }
        }
        return result;
    }

    private boolean matches(String owner, MethodInfo method, String symbol) {
        String full = owner + "#" + method.name();
        String simple = method.ownerClass() + "#" + method.name();
        String signature = "(" + String.join(",", method.parameterTypes()).replace(" ", "") + ")";
        return symbol.equals(full) || symbol.equals(simple) || symbol.equals(full + signature) || symbol.equals(simple + signature);
    }

    @Override
    public ObjectNode write(Action action, Scope scope, List<Change> changes) {
        requireApproval(scope, action);
        // Serialize cooperating workflow writers across threads and processes. Other editors do not share this advisory lock.
        Path lockPath;
        try {
            String rootHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(project(scope).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            lockPath = Path.of(System.getProperty("java.io.tmpdir"), "code-risk-requirements-" + rootHash + ".lock");
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        try (var channel = java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            java.nio.channels.FileLock lock;
            try { lock = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException exception) { throw new IllegalArgumentException("Project already has an active change workflow"); }
            if (lock == null) throw new IllegalArgumentException("Project already has an active change workflow");
            try (lock) { return applyChanges(action, scope, changes); }
        } catch (java.io.IOException exception) { throw new IllegalArgumentException("Could not acquire project change lock"); }
    }

    private ObjectNode applyChanges(Action action, Scope scope, List<Change> changes) {
        // Recheck every digest under the shared lock before the first write.
        validateChanges(action, scope, changes);
        ObjectNode result = mapper.createObjectNode().put("action", action.name()).put("status", "APPLIED");
        var applied = result.putArray("files");
        try {
            for (Change change : changes) {
                Path path = approvedPath(scope, change.path());
                verifyDigest(path, change.expectedSha256());
                Files.createDirectories(path.getParent());
                Path temporary = Files.createTempFile(path.getParent(), ".requirements-", ".tmp");
                try {
                    Files.writeString(temporary, change.content());
                    if (Files.exists(path)) Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path));
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                applied.addObject().put("path", change.path()).put("sha256", hash(path));
            }
        } catch (java.io.IOException | IllegalArgumentException exception) {
            // Individual replacements are atomic; a multi-file change is deliberately not claimed to be transactional.
            result.put("status", "FAILED").put("reason", "Change failed; inspect the recorded applied files before retrying");
        }
        return result;
    }

    @Override
    public ObjectNode verify(Action action, Scope scope) {
        if (action != Action.TEST && action != Action.BUILD) throw new IllegalArgumentException("Unsupported verification action");
        requireApproval(scope, action);
        Path root = project(scope);
        Path wrapper = root.resolve("gradlew");
        if (Files.isSymbolicLink(wrapper) || !Files.isExecutable(wrapper)) throw new IllegalArgumentException("Invalid Gradle wrapper");
        ObjectNode result = mapper.createObjectNode().put("action", action.name());
        Process process = null;
        try {
            process = new ProcessBuilder(wrapper.toString(), action == Action.TEST ? "test" : "build", "--no-daemon", "--rerun-tasks")
                    .directory(root.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            boolean done = process.waitFor(10, TimeUnit.MINUTES);
            if (!done) {
                stop(process);
                return result.put("status", "FAILED").put("exitCode", 124).put("reason", "Verification timed out");
            }
            return result.put("exitCode", process.exitValue()).put("status", process.exitValue() == 0 ? "PASSED" : "FAILED");
        } catch (java.io.IOException exception) {
            return result.put("status", "FAILED").put("exitCode", 2).put("reason", "Could not start project verification");
        } catch (InterruptedException exception) {
            if (process != null) stop(process);
            Thread.currentThread().interrupt();
            return result.put("status", "FAILED").put("exitCode", 130).put("reason", "Verification interrupted");
        }
    }

    private void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void validateChanges(Action action, Scope scope, List<Change> changes) {
        if (action != Action.IMPLEMENT && action != Action.WRITE_TESTS) throw new IllegalArgumentException("Unsupported write action");
        requireApproval(scope, action);
        if (changes.isEmpty() || changes.stream().map(Change::path).distinct().count() != changes.size())
            throw new IllegalArgumentException("Explicit distinct file replacements are required");
        for (Change change : changes) {
            Path path = approvedPath(scope, change.path());
            if (change.content() == null || change.expectedSha256() == null) throw new IllegalArgumentException("Missing replacement content or digest");
            if (action == Action.WRITE_TESTS && !change.path().startsWith("src/test/"))
                throw new IllegalArgumentException("Test-writing phase can only change src/test files");
            if (change.path().startsWith(".git/") || change.path().equals(".git")) throw new IllegalArgumentException("Git metadata changes are forbidden");
            verifyDigest(path, change.expectedSha256());
        }
    }

    private void verifyDigest(Path path, String expected) {
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (expected.equals("ABSENT") ? exists : !exists || !expected.matches("[a-f0-9]{64}") || !hash(path).equals(expected))
            throw new IllegalArgumentException("Change plan has stale or invalid source digest");
    }

    private void requireApproval(Scope scope, Action action) {
        if (!scope.approvedActions().contains(action)) throw new IllegalArgumentException("Action is outside approved scope");
    }

    private void requireCodeMode(ObjectNode input) {
        if (!Set.of("SPEC_CODE_REVIEW", "CODE_CHANGE").contains(input.path("mode").asText()))
            throw new IllegalArgumentException("Code analysis forbidden in this mode");
    }

    private Path project(Scope scope) {
        if (scope == null || scope.project() == null) throw new IllegalArgumentException("Missing explicit project scope");
        try {
            Path root = scope.project().toRealPath();
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Project must be a directory");
            return root;
        } catch (java.io.IOException exception) { throw new IllegalArgumentException("Project is unavailable"); }
    }

    private Path approvedPath(Scope scope, String relative) {
        Path path;
        try { path = Path.of(relative); } catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid scoped path"); }
        if (path.isAbsolute() || relative.isBlank() || relative.contains("\\") || !path.normalize().equals(path)
                || path.startsWith("..") || !scope.approvedFiles().contains(relative))
            throw new IllegalArgumentException("Path is outside the approved file scope");
        Path root = project(scope);
        Path target = root.resolve(path).normalize();
        if (target.equals(root) || !target.startsWith(root)) throw new IllegalArgumentException("Path escapes the project");
        for (Path current = target; !current.equals(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symlinked code paths are forbidden");
        return target;
    }

    private String hash(Path path) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.io.IOException | java.security.NoSuchAlgorithmException exception) { throw new IllegalArgumentException("Could not hash scoped file"); }
    }
}
