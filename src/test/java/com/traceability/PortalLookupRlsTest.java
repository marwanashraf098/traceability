package com.traceability;

import com.traceability.portal.PortalService;
import com.traceability.portal.PortalTokenService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns portal lookup under REAL RLS: a PortalService wired by hand to an app_user
 * TenantAwareDataSource (no BYPASSRLS), the same pattern as ShopifyConnectAmbientContextTest /
 * InventoryLedgerTest's appUserLedger — the Spring context itself stays on postgres because
 * app_user can't be the primary datasource during context refresh (see that class's javadoc).
 *
 * Tenant A's slug + an order number that exists only in tenant B → NOT_FOUND; same-tenant
 * positive control → SUCCESS. Revert-to-confirm: with PortalService.lookup()'s
 * TenantContext.runAs removed, this test fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalLookupRlsTest {

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

    @Autowired JdbcTemplate       jdbc;     // postgres — seeding only
    @Autowired PortalTokenService tokens;

    PortalService appUserPortal;
    UUID tenantA, tenantB;

    @BeforeAll
    void setup() {
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserPortal = new PortalService(new JdbcTemplate(appUserDs),
            new DataSourceTransactionManager(appUserDs), tokens);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, 'Tenant A', 'rls-a', true)", tenantA);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, 'Tenant B', 'rls-b', true)", tenantB);
        UUID storeA = UUID.randomUUID(), storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'rls-a.myshopify.com', 'disconnected')", storeA, tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'rls-b.myshopify.com', 'disconnected')", storeB, tenantB);

        deliveredOrder(tenantA, storeA, "#7001", "+20 100 000 7001");   // positive control, tenant A
        deliveredOrder(tenantB, storeB, "#8001", "01000008001");        // exists ONLY in tenant B
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void crossTenant_aSlugWithBOrder_notFound_sameTenantControlSucceeds() {
        PortalService.LookupResult control = appUserPortal.lookup("rls-a", "7001", "01000007001").orElseThrow();
        assertThat(control.outcome()).as("same-tenant positive control under app_user + RLS")
            .isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(control.body().get("orderNumber")).isEqualTo("#7001");

        PortalService.LookupResult cross = appUserPortal.lookup("rls-a", "8001", "01000008001").orElseThrow();
        assertThat(cross.outcome()).as("tenant A's slug must never reach tenant B's order")
            .isEqualTo(PortalService.Outcome.NOT_FOUND);

        PortalService.LookupResult ownSlug = appUserPortal.lookup("rls-b", "8001", "01000008001").orElseThrow();
        assertThat(ownSlug.outcome()).as("the same B order via B's own slug").isEqualTo(PortalService.Outcome.SUCCESS);

        // Throttle ledger rows land in the right tenant under RLS.
        Map<String, Object> counts = jdbc.queryForMap(
            "SELECT COUNT(*) FILTER (WHERE tenant_id = ?) AS a, COUNT(*) FILTER (WHERE tenant_id = ?) AS b " +
            "FROM portal_lookup_attempts", tenantA, tenantB);
        assertThat(((Number) counts.get("a")).intValue()).isEqualTo(2);
        assertThat(((Number) counts.get("b")).intValue()).isEqualTo(1);
    }

    private void deliveredOrder(UUID tenant, UUID store, String number, String phone) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_phone) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), ?) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '2 days')",
                    tenant, order, String.valueOf(4_000_000_000L + Math.abs(order.getLeastSignificantBits() % 1_000_000_000L)));
    }
}
