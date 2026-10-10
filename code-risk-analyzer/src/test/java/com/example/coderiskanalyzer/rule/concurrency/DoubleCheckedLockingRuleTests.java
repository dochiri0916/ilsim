package com.example.coderiskanalyzer.rule.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.baseline.FindingFingerprint;
import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.finding.FindingStatus;
import com.example.coderiskanalyzer.production.finding.Severity;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.rule.RuleEngine;
import com.example.coderiskanalyzer.production.rule.concurrency.DoubleCheckedLockingRule;
import com.example.coderiskanalyzer.production.rule.concurrency.spec.ConcurrencyOutcome;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoubleCheckedLockingRuleTests {
    @TempDir Path tempDir;
    private final DoubleCheckedLockingRule rule = new DoubleCheckedLockingRule();

    @Test
    void detectsDirectAndHelperPublicationOfTheSameNonVolatileField() throws Exception {
        var direct = rule.analyze(context(singleton("", "instance = new Value();")));
        assertThat(direct).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("CON-005");
            assertThat(finding.status()).isEqualTo(FindingStatus.CONFIRMED);
            assertThat(finding.concurrencyAssessment().outcome()).isEqualTo(ConcurrencyOutcome.DEFINITE_PATTERN);
            assertThat(finding.evidence()).extracting("type").contains("DCL_PATTERN", "DCL_FIELD");
        });
        var helper = rule.analyze(context("""
                class Service {
                    Object instance;
                    Object get() { return initialize(); }
                    Object initialize() {
                        if (null == (this.instance)) { synchronized (this) {
                            if ((instance) == null) this.instance = create();
                        } }
                        return instance;
                    }
                    Object create() { return new Object(); }
                }
                """));
        assertThat(helper).singleElement().satisfies(finding ->
                assertThat(finding.concurrencyAssessment().declaration()).contains("Service#initialize()"));
    }

    @Test
    void recordsVolatileConstructorPublicationAsProofWithoutWarning() throws Exception {
        var result = new RuleEngine(List.of(rule)).analyzeDetailed(
                context(singleton("volatile", "instance = new Value();")), Map.of());
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.findings()).isEmpty();
        assertThat(result.concurrencyAssessments()).singleElement().satisfies(assessment -> {
            assertThat(assessment.outcome()).isEqualTo(ConcurrencyOutcome.SAFE_BY_PROOF);
            assertThat(assessment.safeguards()).hasSize(3);
            assertThat(assessment.missingEvidence()).isEmpty();
        });
        assertThat(rule.analyze(context(singleton("volatile", "instance = new Value();")))).isEmpty();
    }

    @Test
    void detectsStateInitializationAfterVolatilePublication() throws Exception {
        var findings = rule.analyze(context(singleton("volatile", "instance = new Value(); instance.value = 42;")));
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.concurrencyAssessment().outcome()).isEqualTo(ConcurrencyOutcome.DEFINITE_PATTERN);
            assertThat(finding.evidence()).extracting("type").contains("DCL_POST_PUBLICATION_WRITE");
        });
    }

    @Test
    void treatsFactoriesAndAdditionalInitializationAsUnknown() throws Exception {
        for (String publication : List.of("instance = factory();", "instance = new Value(); instance.init();",
                "instance = new Value() {};")) {
            assertThat(rule.analyze(context(singleton("volatile", publication)))).singleElement().satisfies(finding -> {
                assertThat(finding.status()).isEqualTo(FindingStatus.REVIEW);
                assertThat(finding.concurrencyAssessment().outcome()).isEqualTo(ConcurrencyOutcome.UNKNOWN);
                assertThat(finding.concurrencyAssessment().missingEvidence()).isNotEmpty();
            });
        }
    }

    @Test
    void unresolvedForeignOrInheritedFieldIsUnknownRatherThanSafe() throws Exception {
        var foreign = rule.analyze(context("""
                class Service { External holder; Object get() {
                    if (holder.instance == null) synchronized (Service.class) {
                        if (holder.instance == null) holder.instance = new Object();
                    }
                    return holder.instance;
                } }
                """));
        assertThat(foreign).singleElement().satisfies(finding -> {
            assertThat(finding.concurrencyAssessment().outcome()).isEqualTo(ConcurrencyOutcome.UNKNOWN);
            assertThat(finding.concurrencyAssessment().missingEvidence()).anyMatch(item -> item.contains("declaration"));
        });
        assertThat(rule.assess(context("""
                class Service extends External { Object get() {
                    if (instance == null) synchronized (Service.class) {
                        if (instance == null) instance = new Object();
                    }
                    return instance;
                } }
                """))).singleElement().satisfies(assessment ->
                assertThat(assessment.outcome()).isEqualTo(ConcurrencyOutcome.UNKNOWN));
    }

    @Test
    void requiresAStableMonitorWithTheCorrectInstanceScopeForProof() throws Exception {
        String safe = singleton("volatile", "instance = new Value();");
        for (String code : List.of(safe.replace("Service.class", "new Object()"),
                safe.replace("static Value", "Value").replace("static Value get", "Value get")
                        .replace("Service.class", "monitor()"))) {
            assertThat(rule.assess(context(code))).singleElement().satisfies(assessment ->
                    assertThat(assessment.outcome()).isEqualTo(ConcurrencyOutcome.UNKNOWN));
        }
        String staticFieldWithInstanceLock = """
                class Service { static volatile Object instance; Object get() {
                    if (instance == null) synchronized (this) {
                        if (instance == null) instance = new Object();
                    }
                    return instance;
                } }
                """;
        assertThat(rule.assess(context(staticFieldWithInstanceLock))).singleElement().satisfies(assessment ->
                assertThat(assessment.outcome()).isEqualTo(ConcurrencyOutcome.UNKNOWN));
        assertThat(rule.assess(context(staticFieldWithInstanceLock.replace("static volatile", "volatile"))))
                .singleElement().satisfies(assessment ->
                        assertThat(assessment.outcome()).isEqualTo(ConcurrencyOutcome.SAFE_BY_PROOF));
    }

    @Test
    void ignoresLocalsDifferentFieldsAndHolderInitialization() throws Exception {
        for (String code : List.of(
                "class Service { Object instance; Object get(Object instance) { if (instance == null) synchronized(this) {"
                        + " if (instance == null) instance = new Object(); } return instance; } }",
                "class Service { Object get() { Object instance = null; if (instance == null) synchronized(this) {"
                        + " if (instance == null) instance = new Object(); } return instance; } }",
                "class Service { Object first, second; Object get() { if (first == null) synchronized(this) {"
                        + " if (second == null) first = new Object(); } return first; } }",
                "class Service { Object first, second; Object get() { if (first == null) synchronized(this) {"
                        + " if (first == null) second = new Object(); } return first; } }",
                "class Service { static class Holder { static final Object INSTANCE = new Object(); }"
                        + " Object get() { return Holder.INSTANCE; } }",
                singleton("", "instance = new Value();").replace("static Value get()", "static synchronized Value get()"))) {
            assertThat(rule.assess(context(code))).as(code).isEmpty();
        }
    }

    @Test
    void doesNotGrantProofWhenInitializationContinuesOutsideTheInnerBranch() throws Exception {
        String code = singleton("volatile", "instance = new Value();")
                .replace("if (instance == null) { instance = new Value(); }",
                        "if (instance == null) { instance = new Value(); } instance.init();");
        assertThat(rule.assess(context(code))).singleElement().satisfies(assessment ->
                assertThat(assessment.outcome()).isEqualTo(ConcurrencyOutcome.UNKNOWN));
    }

    @Test
    void constructorEarlyPublicationOrUnknownInitializationCannotBecomeProof() throws Exception {
        for (String declaration : List.of(
                "class Value { Value() { Service.instance = this; init(); } void init() {} }",
                "class Value { Value() { External.initialize(this); } }",
                "class Value extends External {}",
                "class Value { Object value = External.load(); }")) {
            String code = singleton("volatile", "instance = new Value();")
                    .replace("class Value { int value; void init() {} }", declaration);
            assertThat(rule.assess(context(code))).singleElement().satisfies(assessment ->
                    assertThat(assessment.outcome()).isEqualTo(ConcurrencyOutcome.UNKNOWN));
        }
    }

    @Test
    void synchronizedInstanceMethodCannotHideUnsafeStaticPublication() throws Exception {
        String code = singleton("", "instance = new Value();").replace("static Value get()", "synchronized Value get()");
        assertThat(rule.analyze(context(code))).singleElement().satisfies(finding ->
                assertThat(finding.concurrencyAssessment().outcome()).isEqualTo(ConcurrencyOutcome.DEFINITE_PATTERN));
    }

    @Test
    void stableIdentitySurvivesLineAndCommentChangesAndSeparatesOverloadsAndPublicationPaths() throws Exception {
        String code = singleton("", "instance = new Value();");
        var before = rule.analyze(context(code)).getFirst();
        var after = rule.analyze(context("\n\n// new header\n" + code.replace("instance == null", "/* comment */ instance == null"))).getFirst();
        assertThat(FindingFingerprint.of(tempDir, after)).isNotEqualTo(FindingFingerprint.of(tempDir, before));
        assertThat(FindingFingerprint.stableOf(tempDir, after)).isEqualTo(FindingFingerprint.stableOf(tempDir, before));
        var overload = rule.analyze(context(code.replace("get()", "get(int ignored)"))).getFirst();
        assertThat(FindingFingerprint.stableOf(tempDir, overload)).isNotEqualTo(FindingFingerprint.stableOf(tempDir, before));
        var changed = rule.analyze(context(code.replace("instance = new Value();", "instance = factory();"))).getFirst();
        assertThat(FindingFingerprint.stableOf(tempDir, changed)).isNotEqualTo(FindingFingerprint.stableOf(tempDir, before));
    }

    @Test
    void ruleSettingsPreserveAssessmentAndDisabledRulesProduceNoProofs() throws Exception {
        var context = context(singleton("", "instance = new Value();"));
        var engine = new RuleEngine(List.of(rule));
        var enabled = engine.analyzeDetailed(context, Map.of("CON-005", new AnalyzerConfig.RuleSettings(true, Severity.CRITICAL)));
        assertThat(enabled.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(finding.concurrencyAssessment()).isEqualTo(enabled.concurrencyAssessments().getFirst());
        });
        var disabled = engine.analyzeDetailed(context, Map.of("CON-005", new AnalyzerConfig.RuleSettings(false, null)));
        assertThat(disabled.findings()).isEmpty();
        assertThat(disabled.concurrencyAssessments()).isEmpty();
    }

    @Test
    void distinctFieldsOnTheSameLineAreNotMerged() throws Exception {
        var context = context("class Service { Object first, second; Object get() {"
                + " if (first == null) synchronized(this) { if (first == null) first = new Object(); }"
                + " if (second == null) synchronized(this) { if (second == null) second = new Object(); } return first; } }");
        var result = new RuleEngine(List.of(rule)).analyzeDetailed(context, Map.of());
        assertThat(result.findings()).hasSize(2);
        assertThat(result.findings().stream().map(finding -> FindingFingerprint.stableOf(tempDir, finding)).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void volatilePublicationInitializesOnceUnderConcurrentCallers() throws Exception {
        class Value { final int value = 42; }
        class Lazy {
            volatile Value instance;
            final AtomicInteger constructions = new AtomicInteger();
            Value get() {
                if (instance == null) synchronized (this) {
                    if (instance == null) { constructions.incrementAndGet(); instance = new Value(); }
                }
                return instance;
            }
        }
        Lazy lazy = new Lazy();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                return lazy.get();
            })).toList();
            try { assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            Value first = tasks.getFirst().get(5, TimeUnit.SECONDS);
            for (var task : tasks) {
                assertThat(task.get(5, TimeUnit.SECONDS)).isSameAs(first);
                assertThat(task.get(5, TimeUnit.SECONDS).value).isEqualTo(42);
            }
            assertThat(lazy.constructions).hasValue(1);
        }
    }

    private String singleton(String modifier, String publication) {
        return """
                class Value { int value; void init() {} }
                class Service {
                    private static %s Value instance;
                    static Value get() {
                        if (instance == null) { synchronized (Service.class) {
                            if (instance == null) { %s }
                        } }
                        return instance;
                    }
                    static Value factory() { return new Value(); }
                }
                """.formatted(modifier, publication);
    }

    private AnalysisContext context(String code) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Service.java");
        Files.writeString(file, code);
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        assertThat(sources.getFirst().errors()).isEmpty();
        return new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
    }
}
