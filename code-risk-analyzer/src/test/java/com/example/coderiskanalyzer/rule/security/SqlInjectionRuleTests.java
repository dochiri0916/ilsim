package com.example.coderiskanalyzer.rule.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.analysis.dataflow.DataFlowAnalyzer;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.security.SqlInjectionRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlInjectionRuleTests {

    @TempDir
    Path tempDir;

    @Test
    void reportsTaintedConcatenatedSql() throws Exception {
        assertThat(analyze("@RequestParam String name) { String sql = \"select * from users where name='\" + name; "
                + "jdbcTemplate.query(sql, mapper); }"))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("SEC-001");
                    assertThat(finding.severity().name()).isEqualTo("CRITICAL");
                });
    }

    @Test
    void ignoresPreparedStatementPlaceholderAndUntrustedUnrelatedValue() throws Exception {
        assertThat(analyze("@RequestParam String name) { jdbcTemplate.query(\"select * from users where name = ?\", mapper, name); }"))
                .isEmpty();
        assertThat(analyze("String name) { jdbcTemplate.query(\"select * from users\", mapper); }"))
                .isEmpty();
    }

    @Test
    void ignoresNumericTypedInputs() throws Exception {
        assertThat(analyze("@PathVariable Long id) { jdbcTemplate.query(\"select * from t where id=\" + id, mapper); }"))
                .isEmpty();
        assertThat(analyze("@RequestParam String raw) { long id = Long.parseLong(raw); "
                + "jdbcTemplate.query(\"select * from t where id=\" + id, mapper); }")).isEmpty();
    }

    @Test
    void treatsServletParameterAsAnHttpTaintSource() throws Exception {
        assertThat(analyze("HttpServletRequest request) { jdbcTemplate.query("
                + "\"select * from users where name='\" + request.getParameter(\"name\"), mapper); }"))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("SEC-001"));
    }

    @Test
    void detectsJdbcApiRegardlessOfVariableNameAndRejectsResolvedUnrelatedApis() throws Exception {
        assertThat(analyzeSource("import java.sql.*; class Controller { void users(@RequestParam String name, Statement stmt) "
                + "throws SQLException { stmt.executeQuery(\"select * from users where name='\" + name); } }"))
                .singleElement().satisfies(finding -> assertThat(finding.ruleId()).isEqualTo("SEC-001"));
        assertThat(analyzeSource("class Statement { void executeQuery(String text) {} } class Controller { "
                + "void users(@RequestParam String name, Statement statement) { statement.executeQuery(name); } }"))
                .isEmpty();
    }

    @Test
    void followsTaintedArgumentsThroughHelpersAndTerminatesRecursiveCalls() throws Exception {
        assertThat(analyzeSource("import java.sql.*; class Controller { Statement stmt; "
                + "void users(@RequestParam String name) throws SQLException { query(name); } "
                + "private void query(String value) throws SQLException { if (value.isEmpty()) query(value); "
                + "stmt.executeQuery(\"select * from users where name='\" + value); } }"))
                .singleElement().satisfies(finding -> assertThat(finding.location().file().getFileName().toString()).isEqualTo("Controller.java"));
        assertThat(analyzeSource("import java.sql.*; class Controller { Connection conn; "
                + "void users(@RequestParam String name) throws SQLException { query(name); } "
                + "private void query(String value) throws SQLException { PreparedStatement stmt = conn.prepareStatement("
                + "\"select * from users where name = ?\"); stmt.setString(1, value); stmt.executeQuery(); } }"))
                .isEmpty();
        assertThat(analyzeSource("import java.sql.*; class Controller { Statement stmt; "
                + "void users(@RequestParam String name) throws SQLException { query(\"safe\", name); } "
                + "private void query(String... values) throws SQLException { stmt.executeQuery(values[1]); } }"))
                .hasSize(1);
    }

    @Test
    void distinguishesOverloadedAndUnrelatedHelpers() throws Exception {
        assertThat(analyzeSource("import java.sql.*; class Controller { Statement stmt; "
                + "void users(@RequestParam String name) { query(name); } private void query(String value) {} "
                + "private void query(long value) throws SQLException { stmt.executeQuery(\"select \" + value); } }"))
                .isEmpty();
        assertThat(analyzeSource("import java.sql.*; class Controller { Statement stmt; "
                + "void users(@RequestParam String name) { other.query(name); } "
                + "private void query(String value) throws SQLException { stmt.executeQuery(value); } }"))
                .isEmpty();
    }

    @Test
    void unresolvedHelpersStayBoundToTheirDeclaringTypeWhenClassBodiesMatch() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        java.util.ArrayList<SourceFile> files = new java.util.ArrayList<>();
        for (String name : List.of("first", "second")) {
            Path file = root.resolve(name + "/Controller.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + name + "; import java.sql.*; class Controller { Statement stmt; "
                    + "void users(@RequestBody Missing payload) throws SQLException { query(payload); } "
                    + "private void query(Missing value) throws SQLException { stmt.executeQuery(value.toString()); } }");
            files.add(new SourceFile(root, file));
        }
        var sources = new JavaSourceParser().parse(files);
        var findings = new SqlInjectionRule(new DataFlowAnalyzer()).analyze(new AnalysisContext(new ProgramModelBuilder().build(sources), sources));
        assertThat(findings).hasSize(2);
        assertThat(findings).extracting(finding -> finding.location().file().getParent().getFileName().toString())
                .containsExactlyInAnyOrder("first", "second");
    }

    private List<Finding> analyze(String method) throws Exception {
        return analyzeSource("class Controller { void users(" + method + " }");
    }

    private List<Finding> analyzeSource(String code) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Controller.java");
        Files.writeString(file, code);
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
        return new SqlInjectionRule(new DataFlowAnalyzer()).analyze(context);
    }
}
