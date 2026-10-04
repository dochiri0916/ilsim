package com.example.coderiskanalyzer.impact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.coderiskanalyzer.production.config.AnalyzerConfigLoader;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.source.SourceRootDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImpactCommandTests {

    @TempDir Path project;

    private final ObjectMapper mapper = new ObjectMapper();

    private ImpactCommand command() {
        return new ImpactCommand(new SourceDiscoveryService(new SourceRootDetector()), new JavaSourceParser(),
                new AnalyzerConfigLoader());
    }

    private void write(String path, String content) throws Exception {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void writeProject(String priceMethod, String entityField) throws Exception {
        write("src/main/java/demo/Price.java", """
                package demo;
                public class Price {
                    %s
                }
                """.formatted(priceMethod));
        write("src/main/java/demo/OrderService.java", """
                package demo;
                public class OrderService {
                    private final Price price = new Price();
                    public int total(int amount) { return discount(amount); }
                    int discount(int amount) { return price.of(amount); }
                }
                """);
        write("src/main/java/demo/OrderController.java", """
                package demo;
                public class OrderController {
                    private final OrderService service = new OrderService();
                    public int order(int amount) { return service.total(amount); }
                }
                """);
        write("src/main/java/demo/Order.java", """
                package demo;
                @Entity
                public class Order {
                    @Id Long id;
                    %s
                }
                """.formatted(entityField));
        write("src/test/java/demo/OrderControllerTests.java", """
                package demo;
                class OrderControllerTests {
                    @Test void orders() { new OrderController().order(1); }
                }
                """);
        write("src/test/java/demo/UnrelatedTests.java", """
                package demo;
                class UnrelatedTests {
                    @Test void nothing() { }
                }
                """);
        write("changed.txt", "src/main/java/demo/Price.java\n");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String name) throws Exception {
        return mapper.readValue(project.resolve(name).toFile(), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsTransitiveCallersAndTheTestsThatReachTheChangedFile() throws Exception {
        // given
        writeProject("public int of(int amount) { return amount; }", "@Column(nullable = false) String status;");
        // when
        var response = command().run(project.toString(), "changed.txt", 4, "", "build/impact.json");
        // then: Price#of <- discount <- total <- order <- the controller test
        assertThat(response.exitCode()).isZero();
        var impact = (Map<String, Object>) read("build/impact.json").get("impact");
        assertThat((Map<String, Object>) impact.get("callers")).containsOnlyKeys(
                "demo.OrderService#discount(int)", "demo.OrderService#total(int)", "demo.OrderController#order(int)");
        assertThat((Map<String, Object>) impact.get("tests")).containsOnlyKeys("demo.OrderControllerTests#orders()");
        assertThat((Map<String, Object>) impact.get("tests")).containsEntry("demo.OrderControllerTests#orders()", 4);
        assertThat((List<String>) impact.get("callerFiles"))
                .containsExactly("src/main/java/demo/OrderController.java", "src/main/java/demo/OrderService.java");
    }

    @Test
    @SuppressWarnings("unchecked")
    void depthLimitsHowFarCallersAreFollowed() throws Exception {
        writeProject("public int of(int amount) { return amount; }", "String status;");
        command().run(project.toString(), "changed.txt", 1, "", "build/impact.json");
        var impact = (Map<String, Object>) read("build/impact.json").get("impact");
        assertThat((Map<String, Object>) impact.get("callers")).containsOnlyKeys("demo.OrderService#discount(int)");
        assertThat((Map<String, Object>) impact.get("tests")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsSignatureAnnotationAndConstraintChangesAgainstTheEarlierSnapshot() throws Exception {
        // given
        writeProject("public int of(int amount) { return amount; }", "@Column(nullable = true) String status;");
        assertThat(command().run(project.toString(), "", 3, "", "build/before.json").exitCode()).isZero();
        writeProject("@Transactional(readOnly = true) public long of(int amount, int rate) { return amount; }",
                "@Column(nullable = false, unique = true) String status;");
        // when
        var response = command().run(project.toString(), "", 3, "build/before.json", "build/after.json");
        // then
        assertThat(response.exitCode()).isZero();
        var changes = (List<Map<String, Object>>) read("build/after.json").get("changes");
        assertThat(changes).extracting(change -> change.get("change") + " " + change.get("key")).containsExactly(
                "CONSTRAINT_CHANGED FIELD demo.Order.status",
                "API_REMOVED METHOD demo.Price#of(int)");
        assertThat(response.output()).contains("contract changes: 2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsATransactionAnnotationChangeWithoutASignatureChange() throws Exception {
        writeProject("@Transactional public int of(int amount) { return amount; }", "String status;");
        command().run(project.toString(), "", 3, "", "build/before.json");
        writeProject("public int of(int amount) { return amount; }", "String status;");
        command().run(project.toString(), "", 3, "build/before.json", "build/after.json");
        var changes = (List<Map<String, Object>>) read("build/after.json").get("changes");
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.get("change")).isEqualTo("ANNOTATION_CHANGED");
            assertThat(change.get("key")).isEqualTo("METHOD demo.Price#of(int)");
        });
    }

    @Test
    void refusesPathsOutsideTheProject() throws Exception {
        writeProject("public int of(int amount) { return amount; }", "String status;");
        assertThat(command().run(project.toString(), "", 3, "", "../escape.json").exitCode()).isEqualTo(2);
        assertThat(command().run(project.toString(), "", 11, "", "").exitCode()).isEqualTo(2);
    }
}
