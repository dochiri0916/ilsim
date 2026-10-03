package com.example.coderiskanalyzer.testanalysis;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.example.coderiskanalyzer.production.finding.Evidence;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Imports execution artifacts; DB use and assertion sufficiency remain explicit reviewer attestations. */
public class ExecutionEvidenceLinker {
    public List<TestAdequacyResult> link(Path root, String manifestPath, List<TestAdequacyResult> results,
                                         List<ParsedSource> sources) throws IOException {
        JsonNode manifest = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(Files.readAllBytes(projectFile(root, manifestPath)));
        if (manifest == null || !manifest.path("version").isIntegralNumber() || manifest.path("version").asInt(-1) != 1 || !manifest.path("entries").isArray())
            throw new IOException("execution manifest requires version 1 and entries array");
        Map<String, JsonNode> entries = new HashMap<>();
        for (JsonNode entry : manifest.path("entries")) {
            String id = text(entry, "obligationId");
            if (entries.putIfAbsent(id, entry) != null) throw new IOException("duplicate execution obligation: " + id);
        }
        Set<String> known = new HashSet<>();
        results.forEach(result -> known.add(result.obligation().id()));
        if (!known.containsAll(entries.keySet())) throw new IOException("execution manifest references an obligation outside the current scan");
        List<TestAdequacyResult> linked = new ArrayList<>();
        for (TestAdequacyResult result : results) {
            JsonNode entry = entries.get(result.obligation().id());
            linked.add(entry == null ? result : linkEntry(root, entry, result, sources));
        }
        return List.copyOf(linked);
    }

    private TestAdequacyResult linkEntry(Path root, JsonNode entry, TestAdequacyResult result,
                                         List<ParsedSource> sources) throws IOException {
        TestObligation obligation = result.obligation();
        String test = text(entry, "test");
        if (!result.matchingTests().contains(test)) throw new IOException("execution test is not statically linked: " + test);
        String scenario = text(entry, "scenario");
        if (!Set.of("ROLLBACK", "CONCURRENCY", "DUPLICATE_MESSAGE").contains(scenario))
            throw new IOException("unsupported DB test scenario");
        String database = text(entry, "database");
        String dbEvidence = text(entry, "databaseEvidence");
        byte[] source = checked(root, root.relativize(obligation.location().file()).toString(), text(entry, "sourceSha256"));
        Path testFile = testFile(test, sources);
        checked(root, root.relativize(testFile).toString(), text(entry, "testSourceSha256"));
        Document junit = xml(checked(root, text(entry, "junit"), text(entry, "junitSha256")));
        Document jacoco = xml(checked(root, text(entry, "jacoco"), text(entry, "jacocoSha256")));
        if (!junit.getDocumentElement().getTagName().equals("testsuite")
                && !junit.getDocumentElement().getTagName().equals("testsuites")) throw new IOException("expected JUnit XML");
        if (!jacoco.getDocumentElement().getTagName().equals("report")) throw new IOException("expected JaCoCo XML");
        boolean passed = testPassed(junit, test);
        boolean covered = methodCovered(jacoco, obligation);
        JsonNode review = entry.path("assertionReview");
        boolean reviewed = !review.isMissingNode() && !review.isNull();
        String reviewer = "";
        String reason = "";
        if (reviewed) {
            reviewer = text(review, "reviewer");
            reason = text(review, "reason");
            if (!text(review, "requiredEvidence").equals(obligation.requiredEvidence()))
                throw new IOException("assertion review must match the required evidence exactly");
        }
        boolean accepted = result.status() == CoverageStatus.PARTIAL && passed && covered && reviewed
                && scenarioMatches(obligation, scenario);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("test", test);
        attributes.put("testPassed", passed);
        attributes.put("methodLinesCovered", covered);
        attributes.put("coverageScope", "aggregate executable source lines in obligation method; not per-test or branch coverage");
        attributes.put("scenario", scenario);
        attributes.put("database", database);
        attributes.put("databaseEvidence", dbEvidence);
        attributes.put("databaseVerifiedByAnalyzer", false);
        attributes.put("assertionReviewed", reviewed);
        attributes.put("reviewer", reviewer);
        attributes.put("assertionReason", reason);
        attributes.put("staticStatus", result.status().name());
        attributes.put("acceptedForObligation", accepted);
        attributes.put("junit", text(entry, "junit"));
        attributes.put("jacoco", text(entry, "jacoco"));
        attributes.put("sourceSha256", sha256(source));
        attributes.put("testSourceSha256", text(entry, "testSourceSha256"));
        attributes.put("junitSha256", text(entry, "junitSha256"));
        attributes.put("jacocoSha256", text(entry, "jacocoSha256"));
        Evidence evidence = new Evidence("EXECUTION_TEST", "Imported execution artifacts and explicit DB/assertion review.",
                obligation.location(), attributes);
        List<String> cases = new ArrayList<>(result.coveredCases());
        if (passed) cases.add("JUnit test passed: " + test);
        if (covered) cases.add("aggregate method source lines covered by JaCoCo");
        if (accepted) cases.add(obligation.requiredEvidence());
        return new TestAdequacyResult(obligation, accepted ? CoverageStatus.COVERED : result.status(),
                result.matchingTests(), cases, accepted ? List.of() : result.missingCases(),
                accepted ? "DB scenario execution and aggregate line coverage were linked; assertion sufficiency was explicitly reviewed."
                        : result.reason(), List.of(evidence));
    }

    private boolean scenarioMatches(TestObligation obligation, String scenario) {
        if (obligation.type().equals("TRANSACTION_ROLLBACK")) return scenario.equals("ROLLBACK");
        if (!obligation.type().equals("RISK")) return false;
        return switch (obligation.riskId() == null ? "" : obligation.riskId()) {
            case "CON-001", "CON-002" -> scenario.equals("CONCURRENCY");
            case "IDEM-001" -> scenario.equals("DUPLICATE_MESSAGE");
            default -> false;
        };
    }

    private Path testFile(String test, List<ParsedSource> sources) throws IOException {
        int separator = test.lastIndexOf('#');
        if (separator <= 0) throw new IOException("test must be qualified.Class#method");
        String owner = test.substring(0, separator);
        List<Path> files = sources.stream().filter(s -> s.compilationUnit() != null)
                .filter(s -> s.sourceFile().path().toString().replace('\\', '/').contains("/src/test/"))
                .filter(s -> s.compilationUnit().findAll(com.github.javaparser.ast.body.TypeDeclaration.class)
                        .stream().anyMatch(t -> t.getFullyQualifiedName().orElse("").equals(owner)))
                .map(s -> s.sourceFile().path()).distinct().toList();
        if (files.size() != 1) throw new IOException("execution test source is ambiguous or absent");
        return files.getFirst();
    }

    private boolean testPassed(Document document, String test) {
        String owner = test.substring(0, test.lastIndexOf('#'));
        String method = test.substring(test.lastIndexOf('#') + 1);
        var cases = document.getElementsByTagName("testcase");
        int matches = 0;
        for (int i = 0; i < cases.getLength(); i++) {
            Element element = (Element) cases.item(i);
            if (!element.getAttribute("classname").equals(owner) || !(element.getAttribute("name").equals(method)
                    || element.getAttribute("name").equals(method + "()"))) continue;
            matches++;
            if (element.getElementsByTagName("failure").getLength() > 0
                    || element.getElementsByTagName("error").getLength() > 0
                    || element.getElementsByTagName("skipped").getLength() > 0) return false;
        }
        // A single ordinary JUnit invocation is supported; duplicates require separate review.
        return matches == 1;
    }

    private boolean methodCovered(Document document, TestObligation obligation) throws IOException {
        Path file = obligation.location().file();
        String owner = obligation.owner();
        String pkg = owner.contains(".") ? owner.substring(0, owner.lastIndexOf('.')).replace('.', '/') : "";
        var packages = document.getElementsByTagName("package");
        int matchingFiles = 0;
        boolean covered = false;
        for (int i = 0; i < packages.getLength(); i++) {
            Element element = (Element) packages.item(i);
            if (!element.getAttribute("name").equals(pkg)) continue;
            var files = element.getElementsByTagName("sourcefile");
            for (int j = 0; j < files.getLength(); j++) {
                Element source = (Element) files.item(j);
                if (!source.getAttribute("name").equals(file.getFileName().toString())) continue;
                matchingFiles++;
                int executable = 0;
                boolean complete = true;
                var lines = source.getElementsByTagName("line");
                Set<Integer> seen = new HashSet<>();
                for (int k = 0; k < lines.getLength(); k++) {
                    Element line = (Element) lines.item(k);
                    int nr = number(line, "nr");
                    if (!seen.add(nr)) throw new IOException("duplicate JaCoCo source line");
                    if (nr < obligation.location().startLine() || nr > obligation.location().endLine()) continue;
                    int missed = number(line, "mi");
                    int hit = number(line, "ci");
                    if (missed > 0 || hit > 0) executable++;
                    if (missed > 0) complete = false;
                }
                covered = executable > 0 && complete;
            }
        }
        return matchingFiles == 1 && covered;
    }

    private int number(Element element, String attribute) throws IOException {
        try {
            int value = Integer.parseInt(element.getAttribute(attribute));
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) { throw new IOException("invalid JaCoCo line counter"); }
    }

    private byte[] checked(Path root, String path, String digest) throws IOException {
        if (!digest.matches("[a-f0-9]{64}")) throw new IOException("expected lowercase SHA-256");
        byte[] bytes = Files.readAllBytes(projectFile(root, path));
        if (!sha256(bytes).equals(digest)) throw new IOException("execution artifact/source hash mismatch: " + path);
        return bytes;
    }

    private Path projectFile(Path root, String value) throws IOException {
        Path relative = Path.of(value);
        Path file = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !file.startsWith(root)) throw new IOException("execution path must be project-relative");
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("execution path may not traverse symbolic links");
        }
        if (!Files.isRegularFile(file)) throw new IOException("execution input is not a regular file: " + value);
        return file;
    }

    private String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IOException("execution manifest requires " + field);
        return value.asText();
    }

    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private Document xml(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            Document document = builder.parse(new ByteArrayInputStream(bytes));
            if (document.getDoctype() != null && document.getDoctype().getInternalSubset() != null
                    && !document.getDoctype().getInternalSubset().isBlank()) throw new IOException("internal XML declarations are not supported");
            return document;
        } catch (IOException exception) { throw exception; }
        catch (Exception exception) { throw new IOException("invalid execution XML", exception); }
    }
}
