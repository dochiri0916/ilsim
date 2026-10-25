package com.example.coderiskanalyzer.production.report;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.example.coderiskanalyzer.production.config.AnalyzerConfig;
import com.example.coderiskanalyzer.production.parser.ParsedSource;
import com.example.coderiskanalyzer.testanalysis.CoverageStatus;
import com.example.coderiskanalyzer.testanalysis.TestAdequacyResult;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

public record AnalysisCompleteness(int filesDiscovered, int filesSelected, int filesParsedAsContext,
        int methodCalls, int unresolvedMethodCalls, double unresolvedCallRatio,
        int testObligations, int unknownTestObligations, double unknownRatio,
        Map<String, Integer> limitedPatterns, List<String> configuredSourceRoots,
        List<String> configuredExcludes, List<String> disabledRules, List<String> limitations) {
    public AnalysisCompleteness {
        limitedPatterns = java.util.Collections.unmodifiableMap(new TreeMap<>(limitedPatterns));
        configuredSourceRoots = List.copyOf(configuredSourceRoots);
        configuredExcludes = List.copyOf(configuredExcludes);
        disabledRules = List.copyOf(disabledRules);
        limitations = List.copyOf(limitations);
    }
    public static AnalysisCompleteness empty() {
        return new AnalysisCompleteness(0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(),
                List.of(), List.of(), List.of(), List.of());
    }
    public static AnalysisCompleteness measure(List<ParsedSource> production, List<TestAdequacyResult> adequacy,
            int discovered, int selected, int context, AnalyzerConfig config, boolean changed) {
        int calls = 0, unresolved = 0;
        Map<String, Integer> patterns = new TreeMap<>();
        for (var source : production) {
            var unit = source.compilationUnit();
            if (unit == null) continue;
            for (var call : unit.findAll(MethodCallExpr.class)) {
                calls++;
                try { call.resolve(); } catch (RuntimeException exception) { unresolved++; }
            }
            patterns.merge("LOOP", unit.findAll(ForStmt.class).size() + unit.findAll(ForEachStmt.class).size()
                    + unit.findAll(WhileStmt.class).size() + unit.findAll(DoStmt.class).size(), Integer::sum);
            patterns.merge("TRY", unit.findAll(TryStmt.class).size(), Integer::sum);
            patterns.merge("SWITCH", unit.findAll(SwitchStmt.class).size()
                    + unit.findAll(com.github.javaparser.ast.expr.SwitchExpr.class).size(), Integer::sum);
            patterns.merge("SYNCHRONIZED", unit.findAll(SynchronizedStmt.class).size(), Integer::sum);
        }
        int unknown = (int) adequacy.stream().filter(r -> r.status() == CoverageStatus.UNKNOWN).count();
        return new AnalysisCompleteness(discovered, selected, context, calls, unresolved,
                calls == 0 ? 0 : (double) unresolved / calls, adequacy.size(), unknown,
                adequacy.isEmpty() ? 0 : (double) unknown / adequacy.size(), patterns,
                config.sourceRoots(), config.excludes(), config.rules().entrySet().stream()
                        .filter(e -> !e.getValue().enabled()).map(Map.Entry::getKey).sorted().toList(),
                List.of("Call metrics cover selected production ASTs; constructors and context/test calls are excluded.",
                        "Limited patterns are occurrences where security protection flow may remain unproven, not skipped files.",
                        "Discovery excludes build/target/out/generated/generated-sources/node_modules/.git/.gradle and symlinks; unreadable entries may be omitted.",
                        "Configured roots and excludes bound discovery; files outside it are not counted.",
                        changed ? "Changed-files mode: risk entry points are selected production files; SQL/command taint follows reachable project helpers."
                                : "Full scan of discovered production sources.",
                        "Static analysis does not prove runtime transactions, concurrency, idempotency or partial-failure safety."));
    }
}
