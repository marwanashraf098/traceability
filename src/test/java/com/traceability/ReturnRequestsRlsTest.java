package com.traceability;

import com.traceability.portal.ReturnRequestService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns portal Step 4b — merchant return-request endpoints under REAL RLS: a
 * ReturnRequestService wired by hand to an app_user TenantAwareDataSource (no BYPASSRLS), same
 * pattern as PortalLookupRlsTest. Tenant A's context can't list, read, approve or reject tenant
 * B's request; same-tenant positive control succeeds. Without a tenant context the service
 * fails closed and a raw app_user read returns zero rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnRequestsRlsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;   // postgres — seeding and out-of-band checks only

    ReturnRequestService appUserRequests;
    JdbcTemplate         appUserJdbc;
    TransactionTemplate  appUserTx;
    UUID tenantA, tenantB, requestA, requestA2, requestB, userA;

    @BeforeAll
    void setup() {
        DataSource appUserDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc     = new JdbcTemplate(appUserDs);
        appUserRequests = new ReturnRequestService(appUserJdbc);
        appUserTx       = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        userA   = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RR Tenant A')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RR Tenant B')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                    "VALUES (?, ?, 'Owner A', ?, 'x', 'owner', true)", userA, tenantA, "rr-owner-" + userA + "@test.local");
        requestA  = seed(tenantA, "#1101", "RR-AAA2");
        requestA2 = seed(tenantA, "#1102", "RR-AAA3");
        requestB  = seed(tenantB, "#2201", "RR-BBB2");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void crossTenant_aCannotListReadApproveOrRejectB_sameTenantControlSucceeds() {
        List<Map<String, Object>> listed = asA(() -> (List<Map<String, Object>>) appUserRequests.list(null, 0, 50).get("items"));
        assertThat(listed).extracting(r -> r.get("reference")).containsExactlyInAnyOrder("RR-AAA2", "RR-AAA3");
        assertThat(asA(() -> appUserRequests.list(null, 0, 50).get("total"))).isEqualTo(2);

        assertThat(asA(() -> appUserRequests.detail(requestA)).get("reference")).as("positive control").isEqualTo("RR-AAA2");
        assertNotFound(() -> appUserRequests.detail(requestB));
        assertNotFound(() -> { appUserRequests.approve(requestB, userA); return null; });
        assertNotFound(() -> { appUserRequests.reject(requestB, "not yours", userA); return null; });
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, requestB))
            .as("B's request untouched").isEqualTo("requested");

        asA(() -> { appUserRequests.approve(requestA, userA); return null; });
        asA(() -> { appUserRequests.reject(requestA2, "Outside policy", userA); return null; });
        assertThat(jdbc.queryForList("SELECT status::text FROM return_requests WHERE tenant_id = ? ORDER BY reference",
            String.class, tenantA)).containsExactly("approved", "rejected");
    }

    @Test
    void rlsAlone_hidesOtherTenantsRows_evenWithoutATenantFilter() {
        List<String> refs = asA(() -> appUserJdbc.queryForList("SELECT reference FROM return_requests", String.class));
        assertThat(refs).doesNotContain("RR-BBB2").contains("RR-AAA2");
    }

    @Test
    void withoutTenantContext_serviceFailsClosed_andRawReadReturnsZeroRows() {
        assertThatThrownBy(() -> appUserTx.execute(s -> appUserRequests.list(null, 0, 50)))
            .isInstanceOf(IllegalStateException.class);
        Integer visible = appUserTx.execute(s -> appUserJdbc.queryForObject("SELECT COUNT(*) FROM return_requests", Integer.class));
        assertThat(visible).as("app_user with no GUC sees nothing").isZero();
    }

    private <T> T asA(Supplier<T> body) {
        return TenantContext.runAs(tenantA, () -> appUserTx.execute(s -> body.get()));
    }

    private void assertNotFound(Supplier<Object> body) {
        assertThatThrownBy(() -> asA(body))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    private UUID seed(UUID tenant, String number, String reference) {
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "rr-" + store + ".myshopify.com");
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number);
        return jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, reference) VALUES (?, ?, ?) RETURNING id",
            UUID.class, tenant, order, reference);
    }
}
