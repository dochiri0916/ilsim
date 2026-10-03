package com.example.coderiskanalyzer.rule.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.rule.concurrency.CheckThenActRaceRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckThenActRaceRuleTests {

    @TempDir
    Path tempDir;

    @Test
    void reportsCheckFollowedByWriteOnSameKey() throws Exception {
        assertThat(analyze("void create(String key) { if (!repo.existsByKey(key)) { repo.save(key); } }"))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("CON-001");
                    assertThat(finding.status().name()).isEqualTo("REVIEW");
                });
    }

    @Test
    void ignoresCheckWithoutMutationAndUnrelatedKey() throws Exception {
        assertThat(analyze("void read(String key, String other) { if (repo.existsByKey(key)) { log(key); } "
                + "if (!repo.existsByKey(key)) { repo.save(other); } }"))
                .isEmpty();
    }

    @Test
    void jvmLocksDoNotSuppressDatabaseRaces() throws Exception {
        assertThat(analyze("synchronized void create(String key) { if (!repo.existsByKey(key)) { repo.save(key); } }"))
                .singleElement().satisfies(finding -> assertThat(finding.evidence())
                        .anyMatch(evidence -> evidence.type().equals("JVM_LOCAL_LOCK")));
        assertThat(analyze("void create(String key) { synchronized (this) { if (!repo.existsByKey(key)) { repo.save(key); } } }"))
                .hasSize(1);
    }

    @Test
    void unrelatedEntityAndUnresolvedRepositoryDoNotSuppressFindings() throws Exception {
        assertThat(analyzeSource("@Entity class Other { @Column(unique=true) String email; } "
                + "class Service { void create(String email) { if (!repo.existsByEmail(email)) { repo.save(email); } } }"))
                .hasSize(1);
        assertThat(analyzeRepository("@Entity class Account { String email; } "
                + "@Entity class Other { @Column(unique=true) String email; }", "Email", "email"))
                .hasSize(1);
    }

    @Test
    void usesCheckedPropertyRatherThanArgumentVariableName() throws Exception {
        assertThat(analyzeRepository("@Entity class Account { @Column(unique=true) String email; }", "Email", "input"))
                .isEmpty();
        assertThat(analyzeRepository("@Entity class Account { @Column(unique=true) String input; String email; }", "Email", "input"))
                .hasSize(1);
    }

    @Test
    void compositeUniqueRequiresEveryConstraintColumn() throws Exception {
        String entity = "@Entity @Table(uniqueConstraints=@UniqueConstraint(columnNames={\"tenant_id\",\"email_address\"})) "
                + "class Account { @Column(name=\"tenant_id\") String tenantId; @Column(name=\"email_address\") String email; }";
        assertThat(analyzeRepository(entity, "TenantIdAndEmail", "tenantId, email")).isEmpty();
        assertThat(analyzeRepository(entity, "Email", "email")).hasSize(1);
        assertThat(analyzeRepository(entity.replace("email_address\"}", "email\"}"), "TenantIdAndEmail", "tenantId, email"))
                .hasSize(1);
    }

    @Test
    void constraintNameAndSubstringAreNotColumnMatches() throws Exception {
        assertThat(analyzeRepository("@Entity @Table(uniqueConstraints=@UniqueConstraint(name=\"email_unique\", columnNames=\"id\")) "
                + "class Account { String email; String id; }", "Email", "email")).hasSize(1);
        assertThat(analyzeRepository("@Entity class Account { @Column(unique=true) String emailAddress; String email; }", "Email", "email"))
                .hasSize(1);
    }

    @Test
    void primaryKeysAreGroupedForCompositeIds() throws Exception {
        assertThat(analyzeRepository("@Entity class Account { @Id String email; }", "Email", "email")).isEmpty();
        String entity = "@Entity class Account { @Id String tenantId; @Id String email; }";
        assertThat(analyzeRepository(entity, "Email", "email")).hasSize(1);
        assertThat(analyzeRepository(entity, "TenantIdAndEmail", "tenantId, email")).isEmpty();
    }

    @Test
    void checksBothReadAndWriteRepositories() throws Exception {
        assertThat(analyzeSource("import org.springframework.data.jpa.repository.JpaRepository; "
                + "@Entity class Account { @Column(unique=true) String email; } "
                + "@Entity class Other { String email; } "
                + "interface AccountRepository extends JpaRepository<Account, Long> {} "
                + "interface OtherRepository extends JpaRepository<Other, Long> {} "
                + "class Service { AccountRepository repo; OtherRepository other; "
                + "void create(String email) { if (!repo.existsByEmail(email)) { other.save(email); } } }"))
                .hasSize(1);
    }

    @Test
    void resolvesInheritedRepositoryEntity() throws Exception {
        assertThat(analyzeSource("import org.springframework.data.jpa.repository.JpaRepository; "
                + "@Entity class Account { @Column(unique=true) String email; } "
                + "interface BaseRepository<T> extends JpaRepository<T, Long> {} "
                + "interface AccountRepository extends BaseRepository<Account> {} "
                + "class Service { AccountRepository repo; void create(String email) { "
                + "if (!repo.existsByEmail(email)) { repo.save(email); } } }"))
                .isEmpty();
    }

    @Test
    void unrelatedAtomicCallAndLockTextDoNotSuppressDatabaseRace() throws Exception {
        assertThat(analyze("void create(String key) { String mode = \"PESSIMISTIC_WRITE FOR UPDATE\"; "
                + "if (!repo.existsByKey(key)) { cache.putIfAbsent(key, key); repo.save(key); } }"))
                .hasSize(1);
    }

    @Test
    void uniqueConstraintDoesNotProtectReadThenUpdate() throws Exception {
        assertThat(analyzeSource("import org.springframework.data.jpa.repository.JpaRepository; "
                + "@Entity class Account { @Column(unique=true) String email; } "
                + "interface AccountRepository extends JpaRepository<Account, Long> {} "
                + "class Service { AccountRepository repo; void change(String email) { "
                + "if (repo.existsByEmail(email)) { repo.update(email); } } }"))
                .hasSize(1);
    }

    @Test
    void supportsGenericRepositoryFieldsAndFullyQualifiedBaseTypes() throws Exception {
        assertThat(analyzeSource("@Entity class Account { @Column(unique=true) String email; } "
                + "interface BaseRepository<K, T> extends org.springframework.data.repository.CrudRepository<T, K> {} "
                + "class Service { BaseRepository<Long, Account> repo; void create(String input) { "
                + "if (!repo.existsByEmail(input)) { repo.save(input); } } }"))
                .isEmpty();
    }

    @Test
    void uniqueSubsetProtectsCompositeEqualityButUnsupportedPredicatesRemainReview() throws Exception {
        String entity = "@Entity class Account { @Column(unique=true) String email; String tenantId; }";
        assertThat(analyzeRepository(entity, "TenantIdAndEmail", "tenantId, email")).isEmpty();
        assertThat(analyzeRepository(entity, "EmailIgnoreCase", "email")).hasSize(1);
        assertThat(analyzeRepository(entity, "TenantIdOrEmail", "tenantId, email")).hasSize(1);
    }

    @Test
    void handlesMultipleTableConstraintsIndividually() throws Exception {
        assertThat(analyzeRepository("@Entity @Table(uniqueConstraints={@UniqueConstraint(columnNames=\"id\"), "
                + "@UniqueConstraint(columnNames=\"email\")}) class Account { String id; String email; }", "Email", "email"))
                .isEmpty();
    }

    @Test
    void resolvesAcrossFilesWithoutConfusingSameSimpleEntityNames() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        java.util.Map<String, String> files = java.util.Map.of(
                "accounts/Account.java", "package accounts; @Entity public class Account { String email; }",
                "other/Account.java", "package other; @Entity public class Account { @Column(unique=true) String email; }",
                "accounts/AccountRepository.java", "package accounts; import org.springframework.data.jpa.repository.JpaRepository; "
                        + "public interface AccountRepository extends JpaRepository<Account, Long> {}",
                "Service.java", "import accounts.AccountRepository; class Service { AccountRepository repo; "
                        + "void create(String email) { if (!repo.existsByEmail(email)) { repo.save(email); } } }");
        List<SourceFile> sourceFiles = new java.util.ArrayList<>();
        for (var entry : files.entrySet()) {
            Path path = root.resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue());
            sourceFiles.add(new SourceFile(root, path));
        }
        var sources = new JavaSourceParser().parse(sourceFiles);
        assertThat(new CheckThenActRaceRule().analyze(new AnalysisContext(new ProgramModelBuilder().build(sources), sources)))
                .hasSize(1);
        Files.writeString(root.resolve("accounts/Account.java"),
                "package accounts; @Entity public class Account { @Column(unique=true) String email; }");
        sources = new JavaSourceParser().parse(sourceFiles);
        assertThat(new CheckThenActRaceRule().analyze(new AnalysisContext(new ProgramModelBuilder().build(sources), sources)))
                .isEmpty();
    }

    private List<Finding> analyzeRepository(String entities, String properties, String arguments) throws Exception {
        String parameters = java.util.Arrays.stream(arguments.split(", "))
                .map(argument -> "String " + argument).collect(java.util.stream.Collectors.joining(", "));
        return analyzeSource("import org.springframework.data.jpa.repository.JpaRepository; " + entities
                + " interface AccountRepository extends JpaRepository<Account, Long> {} "
                + "class Service { AccountRepository repo; void create(" + parameters + ") { "
                + "if (!repo.existsBy" + properties + "(" + arguments + ")) { repo.save(" + arguments + "); } } }");
    }

    private List<Finding> analyze(String method) throws Exception {
        return analyzeSource("class Service { " + method + " }");
    }

    private List<Finding> analyzeSource(String source) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        Path file = root.resolve("Service.java");
        Files.writeString(file, source);
        var sources = new JavaSourceParser().parse(List.of(new SourceFile(root, file)));
        var context = new AnalysisContext(new ProgramModelBuilder().build(sources), sources);
        return new CheckThenActRaceRule().analyze(context);
    }
}
