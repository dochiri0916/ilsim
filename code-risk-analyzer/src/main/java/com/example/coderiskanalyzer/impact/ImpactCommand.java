package com.example.coderiskanalyzer.impact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.example.coderiskanalyzer.production.analysis.callgraph.CallGraph;
import com.example.coderiskanalyzer.production.analysis.callgraph.CallGraphBuilder;
import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.config.AnalyzerConfigLoader;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.source.SourceFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.springframework.stereotype.Component;

/**
 * Regression scope for a change: which project methods and tests reach the changed files through the call graph
 * (transitive callers), and a snapshot of the declared contracts (public API, transaction/security/async annotations,
 * entity constraints) that can be compared with the snapshot taken before the change. Static evidence only: it
 * selects what to review and run, it does not prove behaviour is unchanged.
 */
@Component
public class ImpactCommand {

    public static final int DEFAULT_DEPTH = 3;
    private static final Set<String> BEHAVIOUR_ANNOTATIONS = Set.of("Transactional", "PreAuthorize", "PostAuthorize",
            "Secured", "RolesAllowed", "Async", "Retryable", "Recover", "Cacheable", "CacheEvict", "CachePut",
            "Scheduled", "KafkaListener", "RabbitListener", "JmsListener", "EventListener", "TransactionalEventListener",
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping",
            "Validated", "Valid", "Lock", "Modifying", "Query");
    private static final Set<String> TYPE_ANNOTATIONS = Set.of("Transactional", "PreAuthorize", "Secured", "RolesAllowed",
            "RestController", "Controller", "RequestMapping", "Service", "Component", "Repository", "Entity", "Table",
            "Validated", "Async", "Filter", "FilterDef", "Where", "SQLRestriction");
    private static final Set<String> CONSTRAINT_ANNOTATIONS = Set.of("Column", "JoinColumn", "Id", "Version",
            "GeneratedValue", "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max", "Positive", "PositiveOrZero",
            "Pattern", "Email", "ManyToOne", "OneToMany", "OneToOne", "ManyToMany", "Enumerated", "Lob", "Embedded",
            "EmbeddedId", "TenantId", "Convert");

    public record Response(String output, int exitCode) {}

    private final SourceDiscoveryService discovery;
    private final JavaSourceParser parser;
    private final AnalyzerConfigLoader configLoader;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public ImpactCommand(SourceDiscoveryService discovery, JavaSourceParser parser, AnalyzerConfigLoader configLoader) {
        this.discovery = discovery;
        this.parser = parser;
        this.configLoader = configLoader;
    }

    public Response run(String rawProject, String filesFile, int depth, String compare, String output) {
        Path project = Path.of(rawProject).toAbsolutePath().normalize();
        if (!Files.isDirectory(project)) return new Response("Error: project path is not a directory", 2);
        if (depth < 0 || depth > 10) return new Response("Error: --depth must be between 0 and 10", 2);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            AnalyzerConfig config = configLoader.load(project);
            List<SourceFile> files = new ArrayList<>(discovery.discover(project, false, config.sourceRoots(), config.excludes()));
            discovery.discover(project, true, List.of(), config.excludes()).stream().filter(file -> isTest(file.path()))
                    .filter(file -> files.stream().noneMatch(known -> known.path().equals(file.path()))).forEach(files::add);
            List<Path> roots = files.stream().map(SourceFile::root).distinct().toList();
            List<ParsedSource> parsed = parser.parse(files, roots, config.classpath().stream().map(project::resolve).toList());
            List<String> unparsed = parsed.stream().filter(source -> source.compilationUnit() == null)
                    .map(source -> relative(project, source.sourceFile().path())).sorted().toList();
            List<ParsedSource> usable = parsed.stream().filter(source -> source.compilationUnit() != null).toList();
            Map<String, Map<String, Object>> contracts = contracts(project, usable.stream()
                    .filter(source -> !isTest(source.sourceFile().path())).toList());
            result.put("version", 1);
            result.put("contracts", contracts);
            result.put("unparsedFiles", unparsed);
            if (filesFile != null && !filesFile.isBlank()) {
                result.put("impact", impact(project, usable, changedFiles(project, filesFile), depth));
            }
            if (compare != null && !compare.isBlank()) {
                Path before = readable(project, compare);
                @SuppressWarnings("unchecked")
                Map<String, Object> previous = mapper.readValue(before.toFile(), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> previousContracts = (Map<String, Map<String, Object>>) previous.get("contracts");
                if (previousContracts == null) return new Response("Error: compare file has no contracts", 2);
                result.put("changes", diff(previousContracts, contracts));
            }
        } catch (IOException | RuntimeException exception) {
            return new Response("Error: " + exception.getMessage(), exception instanceof IOException
                    || exception instanceof IllegalArgumentException ? 2 : 3);
        }
        try {
            String json = mapper.writeValueAsString(result);
            if (output == null || output.isBlank()) return new Response(json, 0);
            Path target = inside(project, output, "output");
            if (target.getFileName().toString().endsWith(".java")) return new Response("Error: output must not be Java source", 2);
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".impact-", ".json");
            Files.writeString(temporary, json);
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return new Response(summary(result) + "\nReport written to " + target, 0);
        } catch (IOException | IllegalArgumentException exception) {
            return new Response("Error: could not write output: " + exception.getMessage(), 2);
        }
    }

    // ---- contracts ----

    Map<String, Map<String, Object>> contracts(Path project, List<ParsedSource> sources) {
        Map<String, Map<String, Object>> contracts = new TreeMap<>();
        for (ParsedSource source : sources) {
            String packageName = source.compilationUnit().getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
            String file = relative(project, source.sourceFile().path());
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                String owner = packageName.isBlank() ? type.getNameAsString() : packageName + "." + type.getNameAsString();
                boolean entity = has(type, Set.of("Entity", "Embeddable", "MappedSuperclass"));
                boolean interfaceType = type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface();
                List<String> typeAnnotations = annotations(type, TYPE_ANNOTATIONS);
                if (!typeAnnotations.isEmpty()) contracts.put("TYPE " + owner, entry("type", file, Map.of("annotations", typeAnnotations)));
                for (MethodDeclaration method : type.getMethods()) {
                    boolean visible = interfaceType || method.hasModifier(Modifier.Keyword.PUBLIC)
                            || method.hasModifier(Modifier.Keyword.PROTECTED);
                    List<String> behaviour = annotations(method, BEHAVIOUR_ANNOTATIONS);
                    if (!visible && behaviour.isEmpty()) continue;
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("signature", method.getTypeAsString() + " " + method.getNameAsString() + "("
                            + String.join(", ", method.getParameters().stream().map(p -> p.getTypeAsString()).toList()) + ")"
                            + (method.getThrownExceptions().isEmpty() ? "" : " throws " + String.join(", ",
                            method.getThrownExceptions().stream().map(Object::toString).toList())));
                    details.put("visibility", visible ? "public" : "internal");
                    details.put("annotations", behaviour);
                    contracts.put("METHOD " + CallGraphBuilder.methodId(owner, method), entry("method", file, details));
                }
                if (!entity) continue;
                for (FieldDeclaration field : type.getFields()) {
                    List<String> constraints = annotations(field, CONSTRAINT_ANNOTATIONS);
                    field.getVariables().forEach(variable -> contracts.put("FIELD " + owner + "." + variable.getNameAsString(),
                            entry("entityField", file, Map.of("type", variable.getTypeAsString(), "annotations", constraints))));
                }
            }
        }
        return contracts;
    }

    private static Map<String, Object> entry(String kind, String file, Map<String, Object> details) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("file", file);
        entry.putAll(details);
        return entry;
    }

    private static boolean has(NodeWithAnnotations<?> node, Set<String> names) {
        return node.getAnnotations().stream().anyMatch(annotation -> names.contains(annotation.getName().getIdentifier()));
    }

    private static List<String> annotations(NodeWithAnnotations<?> node, Set<String> names) {
        return node.getAnnotations().stream().filter(annotation -> names.contains(annotation.getName().getIdentifier()))
                .map(ImpactCommand::text).sorted().toList();
    }

    private static String text(AnnotationExpr annotation) {
        return annotation.toString().replaceAll("\\s+", " ").replace("@" + annotation.getNameAsString(),
                "@" + annotation.getName().getIdentifier());
    }

    /** Contract differences between two snapshots, with what each kind of change can break. */
    static List<Map<String, Object>> diff(Map<String, Map<String, Object>> before, Map<String, Map<String, Object>> after) {
        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Map<String, Object> old = before.get(key);
            Map<String, Object> now = after.get(key);
            if (old != null && now != null && old.equals(now)) continue;
            if (old != null && now != null) {
                Map<String, Object> oldWithoutFile = new LinkedHashMap<>(old);
                Map<String, Object> nowWithoutFile = new LinkedHashMap<>(now);
                oldWithoutFile.remove("file");
                nowWithoutFile.remove("file");
                if (oldWithoutFile.equals(nowWithoutFile)) continue;  // moved file only
            }
            String kind = String.valueOf((now != null ? now : old).get("kind"));
            String change;
            String effect;
            if (now == null) {
                change = kind.equals("entityField") ? "CONSTRAINT_REMOVED" : "API_REMOVED";
                effect = kind.equals("entityField") ? "Entity field removed: schema and stored data need a migration check."
                        : "Removed declaration: callers outside the changed files or other services may break.";
            } else if (old == null) {
                change = kind.equals("entityField") ? "CONSTRAINT_ADDED" : "API_ADDED";
                effect = kind.equals("entityField") ? "New entity field: existing rows and NOT NULL/UNIQUE need a migration check."
                        : "New declaration.";
            } else if (kind.equals("entityField")) {
                change = "CONSTRAINT_CHANGED";
                effect = "Column type or constraint changed: existing data and the schema may violate the new rule.";
            } else if (!String.valueOf(old.get("signature")).equals(String.valueOf(now.get("signature")))
                    || !String.valueOf(old.get("visibility")).equals(String.valueOf(now.get("visibility")))) {
                change = "SIGNATURE_CHANGED";
                effect = "Signature or visibility changed: callers and serialized contracts may break.";
            } else {
                change = "ANNOTATION_CHANGED";
                effect = "Transaction, security, async, retry, cache or mapping behaviour changed without a signature change.";
            }
            if (change.equals("API_ADDED") && kind.equals("method")) continue;  // additions break nothing
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("change", change);
            item.put("key", key);
            item.put("effect", effect);
            item.put("before", old);
            item.put("after", now);
            changes.add(item);
        }
        return changes;
    }

    // ---- impact ----

    private Map<String, Object> impact(Path project, List<ParsedSource> sources, Set<Path> changed, int depth) {
        CallGraph graph = new CallGraphBuilder().build(sources);
        Map<String, Path> declaredIn = new LinkedHashMap<>();
        for (ParsedSource source : sources) {
            String packageName = source.compilationUnit().getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
            for (TypeDeclaration<?> type : source.compilationUnit().findAll(TypeDeclaration.class)) {
                String owner = packageName.isBlank() ? type.getNameAsString() : packageName + "." + type.getNameAsString();
                for (MethodDeclaration method : type.getMethods()) {
                    declaredIn.putIfAbsent(CallGraphBuilder.methodId(owner, method), source.sourceFile().path());
                }
            }
        }
        Set<String> seeds = new TreeSet<>();
        declaredIn.forEach((id, path) -> { if (changed.contains(path)) seeds.add(id); });
        Map<String, Integer> distance = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>(seeds);
        seeds.forEach(seed -> distance.put(seed, 0));
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int next = distance.get(current) + 1;
            if (next > depth) continue;
            for (String caller : graph.incoming().getOrDefault(current, Set.of())) {
                if (distance.putIfAbsent(caller, next) == null) queue.addLast(caller);
            }
        }
        Map<String, Integer> callers = new TreeMap<>();
        Map<String, Integer> tests = new TreeMap<>();
        Set<String> callerFiles = new TreeSet<>();
        Set<String> testFiles = new TreeSet<>();
        distance.forEach((id, hops) -> {
            Path path = declaredIn.get(id);
            if (hops == 0 || path == null) return;
            if (isTest(path)) {
                tests.put(id, hops);
                testFiles.add(relative(project, path));
            } else {
                callers.put(id, hops);
                if (!changed.contains(path)) callerFiles.add(relative(project, path));
            }
        });
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("depth", depth);
        impact.put("changedFiles", changed.stream().map(path -> relative(project, path)).sorted().toList());
        impact.put("changedMethods", List.copyOf(seeds));
        impact.put("callers", callers);
        impact.put("callerFiles", List.copyOf(callerFiles));
        impact.put("tests", tests);
        impact.put("testFiles", List.copyOf(testFiles));
        impact.put("note", "Callers found through resolvable calls only; reflection, framework dispatch and other services are not seen.");
        return impact;
    }

    private Set<Path> changedFiles(Path project, String filesFile) throws IOException {
        Path list = readable(project, filesFile);
        Set<Path> paths = new LinkedHashSet<>();
        for (String line : Files.readAllLines(list)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            Path path = Path.of(value);
            path = (path.isAbsolute() ? path : project.resolve(path)).toAbsolutePath().normalize();
            if (!path.startsWith(project)) throw new IllegalArgumentException("changed file path escapes the project root");
            paths.add(path);
        }
        return paths;
    }

    // ---- helpers ----

    /** Inputs are only read, so they may live outside the project (a workflow session directory, for example). */
    private static Path readable(Path project, String value) throws IOException {
        Path path = Path.of(value);
        path = (path.isAbsolute() ? path : project.resolve(path)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IOException("not a readable file: " + value);
        return path;
    }

    private static Path inside(Path project, String value, String name) {
        Path path = Path.of(value);
        path = (path.isAbsolute() ? path : project.resolve(path)).toAbsolutePath().normalize();
        if (!path.startsWith(project)) throw new IllegalArgumentException(name + " must be inside the project");
        return path;
    }

    private static boolean isTest(Path path) {
        return path.toString().replace('\\', '/').contains("/src/test/");
    }

    private static String relative(Path project, Path path) {
        return project.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    @SuppressWarnings("unchecked")
    private static String summary(Map<String, Object> result) {
        StringBuilder text = new StringBuilder("Contracts: " + ((Map<String, Object>) result.get("contracts")).size());
        if (result.get("impact") instanceof Map<?, ?> impact) {
            text.append(", callers: ").append(((Map<String, Object>) impact.get("callers")).size())
                    .append(", tests reaching the change: ").append(((Map<String, Object>) impact.get("tests")).size());
        }
        if (result.get("changes") instanceof List<?> changes) text.append(", contract changes: ").append(changes.size());
        return text.toString();
    }
}
