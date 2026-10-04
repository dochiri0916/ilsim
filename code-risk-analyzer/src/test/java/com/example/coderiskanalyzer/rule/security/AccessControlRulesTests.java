package com.example.coderiskanalyzer.rule.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.coderiskanalyzer.production.analysis.AnalysisContext;
import com.example.coderiskanalyzer.production.finding.Finding;
import com.example.coderiskanalyzer.production.model.ProgramModelBuilder;
import com.example.coderiskanalyzer.production.parser.JavaSourceParser;
import com.example.coderiskanalyzer.production.rule.RiskRule;
import com.example.coderiskanalyzer.production.rule.security.MissingObjectAuthorizationRule;
import com.example.coderiskanalyzer.production.rule.security.TenantIsolationRule;
import com.example.coderiskanalyzer.production.source.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessControlRulesTests {

    @TempDir Path tempDir;

    private static final String CONTROLLER = """
            @RestController %s
            class OrderController {
                OrderRepository repository;
                @GetMapping("/orders/{id}") %s
                Order get(%s) { return repository.findById(id).orElseThrow(); %s }
            }
            """;

    @Test
    void reportsRequestIdentifierLookupWithoutAnyCallerIdentity() throws Exception {
        assertThat(authorization(CONTROLLER.formatted("", "", "@PathVariable Long id", "")))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.ruleId()).isEqualTo("SEC-006");
                    assertThat(finding.status().name()).isEqualTo("REVIEW");
                    assertThat(finding.location().startLine()).isEqualTo(5);
                });
        assertThat(authorization(CONTROLLER.formatted("", "", "@RequestParam(\"orderId\") Long id", ""))).hasSize(1);
    }

    @Test
    void acceptsVisibleAuthorizationOrCallerIdentity() throws Exception {
        assertThat(authorization(CONTROLLER.formatted("", "@PreAuthorize(\"hasRole('ADMIN')\")", "@PathVariable Long id", ""))).isEmpty();
        assertThat(authorization(CONTROLLER.formatted("@Secured(\"ROLE_ADMIN\")", "", "@PathVariable Long id", ""))).isEmpty();
        assertThat(authorization(CONTROLLER.formatted("", "", "@PathVariable Long id, Principal principal",
                "audit(principal.getName());"))).isEmpty();
        assertThat(authorization(CONTROLLER.formatted("", "", "@PathVariable Long id, @AuthenticationPrincipal User user",
                "audit(user);"))).isEmpty();
        assertThat(authorization(CONTROLLER.formatted("", "", "@PathVariable Long id",
                "SecurityContextHolder.getContext().getAuthentication();"))).isEmpty();
    }

    @Test
    void callerIdentityThatTheHandlerNeverUsesIsNotAProtection() throws Exception {
        assertThat(authorization(CONTROLLER.formatted("", "", "@PathVariable Long id, Principal principal", ""))).hasSize(1);
    }

    private static final String DELEGATING_CONTROLLER = """
            package demo;
            @RestController
            class OrderController {
                private final OrderService service;
                @GetMapping("/orders/{id}")
                Order get(@PathVariable Long id) { return service.load(id); }
            }
            """;

    @Test
    void acceptsAnOwnershipCheckOrAccessRuleFoundDeeperOnTheCallPath() throws Exception {
        assertThat(authorization(DELEGATING_CONTROLLER, """
                package demo;
                class OrderService {
                    private final OrderRepository orders;
                    private final AccessPolicy policy;
                    Order load(Long id) { Order order = orders.findById(id).orElseThrow(); verify(order); return order; }
                    void verify(Order order) { policy.checkOwnership(order); }
                }
                """)).isEmpty();
        assertThat(authorization(DELEGATING_CONTROLLER, """
                package demo;
                class OrderService {
                    @PreAuthorize("@orders.isOwner(#id)") Order load(Long id) { return null; }
                }
                """)).isEmpty();
        assertThat(authorization(DELEGATING_CONTROLLER, """
                package demo;
                class OrderService {
                    private final OrderRepository orders;
                    Order load(Long id) { return orders.findByIdAndOwnerId(id, currentUser()).orElseThrow(); }
                    Long currentUser() { return 1L; }
                }
                """)).isEmpty();
    }

    @Test
    void reportsTheInspectedCallPathWhenNoCheckIsFound() throws Exception {
        assertThat(authorization(DELEGATING_CONTROLLER, """
                package demo;
                class OrderService {
                    private final OrderRepository orders;
                    Order load(Long id) { return orders.findById(id).orElseThrow(); }
                }
                """)).singleElement().satisfies(finding -> assertThat(finding.evidence().getFirst().attributes())
                .containsEntry("inspectedMethods", List.of("OrderController#get", "OrderService#load")));
    }

    @Test
    void ignoresNonIdentifierInputsAndNonHandlers() throws Exception {
        assertThat(authorization("""
                @RestController class SearchController {
                    @GetMapping("/search") List<Order> search(@RequestParam String keyword) { return service.search(keyword); }
                }
                """)).isEmpty();
        assertThat(authorization("""
                class OrderService {
                    Order get(Long id) { return repository.findById(id).orElseThrow(); }
                }
                """)).isEmpty();
        assertThat(authorization("""
                @RestController class UnusedController {
                    @GetMapping("/orders/{id}") String get(@PathVariable Long id) { return "ok"; }
                }
                """)).isEmpty();
    }

    private static final String ENTITY = """
            @Entity %s
            class Order {
                Long id;
                %s String tenantId;
                String status;
            }
            """;
    private static final String REPOSITORY = """
            interface OrderRepository extends JpaRepository<Order, Long> {
                List<Order> findByStatus(String status);
                List<Order> findByTenantIdAndStatus(String tenantId, String status);
                @Query("select o from Order o where o.status = :status")
                List<Order> active(String status);
                @Query("select o from Order o where o.tenantId = :tenantId")
                List<Order> mine(String tenantId);
            }
            """;
    private static final String SERVICE = """
            class OrderService {
                private final OrderRepository orders;
                OrderService(OrderRepository orders) { this.orders = orders; }
                Order load(Long id) { return orders.findById(id).orElseThrow(); }
                List<Order> mine(String tenant) { return orders.findByTenantIdAndStatus(tenant, "OPEN"); }
            }
            """;

    @Test
    void reportsTenantScopedQueriesAndInheritedLookupsWithoutTenantFilter() throws Exception {
        List<Finding> findings = tenant(ENTITY.formatted("", ""), REPOSITORY, SERVICE);
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.ruleId()).isEqualTo("SEC-007");
            assertThat(finding.status().name()).isEqualTo("REVIEW");
        });
        assertThat(findings).extracting(finding -> finding.evidence().getFirst().attributes().get("method"))
                .containsExactlyInAnyOrder("findByStatus", "active", "findById");
    }

    @Test
    void acceptsAnInheritedLookupWhoseCallerChecksTheLoadedTenant() throws Exception {
        String checked = """
                class OrderService {
                    private final OrderRepository orders;
                    OrderService(OrderRepository orders) { this.orders = orders; }
                    Order load(Long id, String tenant) {
                        Order order = orders.findById(id).orElseThrow();
                        if (!order.getTenantId().equals(tenant)) throw new IllegalStateException();
                        return order;
                    }
                    Order other(Long id) { Order order = orders.findById(id).orElseThrow(); verifyTenant(order); return order; }
                    void verifyTenant(Order order) { }
                }
                """;
        assertThat(tenant(ENTITY.formatted("", ""), REPOSITORY, checked))
                .extracting(finding -> finding.evidence().getFirst().attributes().get("method"))
                .containsExactlyInAnyOrder("findByStatus", "active");
    }

    @Test
    void trustsFrameworkTenantFiltersAndIgnoresUnscopedEntities() throws Exception {
        assertThat(tenant(ENTITY.formatted("", "@TenantId"), REPOSITORY, SERVICE)).isEmpty();
        assertThat(tenant(ENTITY.formatted("@Filter(name = \"tenantFilter\")", ""), REPOSITORY, SERVICE)).isEmpty();
        assertThat(tenant(ENTITY.formatted("", "").replace("tenantId", "ownerName"), REPOSITORY, SERVICE)).isEmpty();
    }

    private List<Finding> authorization(String... sources) throws Exception {
        return analyze(new MissingObjectAuthorizationRule(), sources);
    }

    private List<Finding> tenant(String... sources) throws Exception {
        return analyze(new TenantIsolationRule(), sources);
    }

    private List<Finding> analyze(RiskRule rule, String... sources) throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root);
        List<SourceFile> files = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            Path file = root.resolve("Source" + System.nanoTime() + "_" + i + ".java");
            Files.writeString(file, sources[i]);
            files.add(new SourceFile(root, file));
        }
        var parsed = new JavaSourceParser().parse(files);
        return rule.analyze(new AnalysisContext(new ProgramModelBuilder().build(parsed), parsed));
    }
}
