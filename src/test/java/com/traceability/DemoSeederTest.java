package com.traceability;

import com.traceability.demo.DemoSeeder;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.notifications.EmailGateway;
import com.traceability.notifications.ExceptionDigestJob;
import com.traceability.notifications.ExceptionImmediateAlertJob;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FR-DEMO Day 1 — DemoSeeder bootstrap + reseed.
 *
 * background-job-server.enabled=true so the @ConditionalOnProperty-gated
 * ExceptionDigestJob/ExceptionImmediateAlertJob/DemoReseedJob beans exist to autowire
 * (mirrors ExceptionImmediateAlertJobTest); JobScheduler is @MockBean so nothing queued
 * elsewhere in the app context actually executes during these tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "org.jobrunr.background-job-server.enabled=true")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoSeederTest {

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
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean JobScheduler         jobScheduler;
    @MockBean EmailGateway         emailGateway;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate               jdbc;
    @Autowired DemoSeeder                 demoSeeder;
    @Autowired ShopifyInventoryService     shopifyInventoryService;
    @Autowired ExceptionDigestJob          digestJob;
    @Autowired ExceptionImmediateAlertJob  immediateJob;
    @Autowired com.traceability.inventory.ReceivingService receiving;

    private static final UUID DEMO_ID = DemoSeeder.DEMO_TENANT_ID;

    @BeforeEach
    void resetMocks() {
        reset(jobScheduler, emailGateway, shopifyGateway, tokenProvider);
    }

    // ── Fixture helpers for a REAL control tenant ───────────────────────────

    private UUID seedControlTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID seedControlStore(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')",
                id, tenantId, "control-" + id + ".myshopify.com");
        return id;
    }

    private UUID seedControlVariant(UUID tenantId, UUID storeId) {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?,?,?,?,?)",
                productId, tenantId, storeId, "ctrl-prod-" + productId, "Control Product");
        UUID variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?,?,?,?,?)",
                variantId, tenantId, productId, "ctrl-var-" + variantId, "Control Variant");
        return variantId;
    }

    private String seedControlPiece(UUID tenantId, UUID variantId, String status) {
        String pieceId = "PC-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, ?, ?::piece_status)",
                pieceId, tenantId, variantId, pieceId, pieceId, status);
        return pieceId;
    }

    private long countRows(String table, UUID tenantId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class, tenantId);
        return n == null ? 0 : n;
    }

    private List<Map<String, Object>> snapshot(String table, UUID tenantId) {
        return jdbc.queryForList(
                "SELECT * FROM " + table + " WHERE tenant_id = ? ORDER BY id", tenantId);
    }

    // -----------------------------------------------------------------------
    // (a) CROSS-TENANT CONTROL: reseed() resets the demo tenant to golden
    //     counts and leaves a real control tenant byte-for-byte untouched.
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    void reseed_resetsDemoToGoldenCounts_leavesControlTenantUntouched() {
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();

        UUID controlId = seedControlTenant("Reseed Control Co");
        UUID controlStoreId = seedControlStore(controlId);
        UUID controlVariantId = seedControlVariant(controlId, controlStoreId);
        seedControlPiece(controlId, controlVariantId, "available");
        seedControlPiece(controlId, controlVariantId, "lost");

        List<Map<String, Object>> controlPiecesBefore  = snapshot("pieces", controlId);
        List<Map<String, Object>> controlVariantsBefore = snapshot("variants", controlId);
        long controlProductsBefore = countRows("products", controlId);

        // Mutate the demo tenant like a live visitor would: scan a piece to a new status,
        // and drop a stray junk row into a table the demo fixture never touches.
        String anyDemoPieceId = jdbc.queryForObject(
                "SELECT id FROM pieces WHERE tenant_id = ? AND status = 'available'::piece_status LIMIT 1",
                String.class, DEMO_ID);
        jdbc.update("UPDATE pieces SET status = 'reserved'::piece_status WHERE id = ? AND tenant_id = ?",
                anyDemoPieceId, DEMO_ID);
        jdbc.update("INSERT INTO audit_log (tenant_id, action) VALUES (?, 'visitor_did_something')", DEMO_ID);

        long productsBeforeReseed = countRows("products", DEMO_ID);
        long variantsBeforeReseed = countRows("variants", DEMO_ID);

        demoSeeder.reseed();

        // ---- control tenant: byte-for-byte untouched ----
        assertThat(snapshot("pieces", controlId)).isEqualTo(controlPiecesBefore);
        assertThat(snapshot("variants", controlId)).isEqualTo(controlVariantsBefore);
        assertThat(countRows("products", controlId)).isEqualTo(controlProductsBefore);

        // ---- demo tenant: reset to golden counts ----
        assertThat(countRows("products", DEMO_ID)).isEqualTo(productsBeforeReseed).isEqualTo(6L);
        assertThat(countRows("variants", DEMO_ID)).isEqualTo(variantsBeforeReseed).isEqualTo(20L);
        // 20 variants * 3 available (60) + 3 in-transit + 2 returns-pending + 1 lost (66)
        // + ISSUE 3's damaged + restocked return items (2) + ISSUE 4's finalized
        // receiving session (12+8+20=40 pieces) = 108, + 4 seeded transfer pieces
        // (2 still out_on_transfer, 1 returned_good, 1 condemned) = 112.
        assertThat(countRows("pieces", DEMO_ID)).isEqualTo(112L);
        // 10 pickable + 3 in-transit + 1 blocked = 14
        assertThat(countRows("orders", DEMO_ID)).isEqualTo(14L);
        assertThat(countRows("audit_log", DEMO_ID)).isEqualTo(0L); // the stray visitor row is gone
        assertThat(countRows("stores", DEMO_ID)).isEqualTo(1L);
        // ISSUE 3: 2 sessions (1 open with 2 pending + 1 damaged item, 1 closed with 1 restocked item).
        assertThat(countRows("return_sessions", DEMO_ID)).isEqualTo(2L);
        assertThat(countRows("return_session_items", DEMO_ID)).isEqualTo(4L);

        Long lostCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pieces WHERE tenant_id = ? AND status = 'lost'::piece_status",
                Long.class, DEMO_ID);
        assertThat(lostCount).as("exactly one seeded lost-piece exception").isEqualTo(1L);

        Long blockedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND on_hold = true",
                Long.class, DEMO_ID);
        assertThat(blockedCount).as("exactly one seeded blocked-customer exception").isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // (b) reseed() aborts loudly if :demo cannot be uniquely/safely resolved.
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    void reseed_abortsIfDemoTenantUnresolvable() {
        demoSeeder.ensureBootstrapped();

        // Zero is_demo=true rows.
        jdbc.update("UPDATE tenants SET is_demo = false WHERE id = ?", DEMO_ID);
        assertThatThrownBy(demoSeeder::reseed).isInstanceOf(IllegalStateException.class);

        // More than one is_demo=true row.
        UUID secondDemoId = seedControlTenant("Accidental Second Demo");
        jdbc.update("UPDATE tenants SET is_demo = true WHERE id IN (?, ?)", DEMO_ID, secondDemoId);
        assertThatThrownBy(demoSeeder::reseed).isInstanceOf(IllegalStateException.class);
        jdbc.update("DELETE FROM tenants WHERE id = ?", secondDemoId);

        // is_demo=true resolves to a known production pilot tenant id.
        UUID jumi = UUID.fromString("07fc572c-2158-412d-ae31-ec61e22378b7");
        jdbc.update("UPDATE tenants SET is_demo = false WHERE id = ?", DEMO_ID);
        jdbc.update("INSERT INTO tenants (id, name, is_demo) VALUES (?, 'Jumi (forbidden)', true)", jumi);
        assertThatThrownBy(demoSeeder::reseed).isInstanceOf(IllegalStateException.class);
        jdbc.update("DELETE FROM tenants WHERE id = ?", jumi);

        // Restore for subsequent tests.
        jdbc.update("UPDATE tenants SET is_demo = true WHERE id = ?", DEMO_ID);
        demoSeeder.reseed();
        assertThat(countRows("products", DEMO_ID)).isEqualTo(6L);
    }

    // -----------------------------------------------------------------------
    // (c) reseed() twice in a row — no FK violation (proves delete order).
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    void reseed_twiceInARow_noFkViolation() {
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();
        assertThatCode(() -> demoSeeder.reseed()).doesNotThrowAnyException();
        assertThatCode(() -> demoSeeder.reseed()).doesNotThrowAnyException();
        assertThat(countRows("products", DEMO_ID)).isEqualTo(6L);
        assertThat(countRows("pieces", DEMO_ID)).isEqualTo(112L);
    }

    // -----------------------------------------------------------------------
    // (d) Shopify write gateway is never invoked for the store-less demo tenant.
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    void shopifyWriteGateway_neverInvokedForDemoTenant() throws Exception {
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();

        String anyDemoPieceId = jdbc.queryForObject(
                "SELECT id FROM pieces WHERE tenant_id = ? AND status = 'available'::piece_status LIMIT 1",
                String.class, DEMO_ID);
        UUID locationId = jdbc.queryForObject(
                "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
                UUID.class, DEMO_ID);

        TenantContext.set(DEMO_ID);
        try {
            shopifyInventoryService.onReturnInspectionAvailable(DEMO_ID, anyDemoPieceId, locationId)
                    .get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        verifyNoInteractions(shopifyGateway);
        verifyNoInteractions(tokenProvider);

        Map<String, Object> adjustment = jdbc.queryForMap(
                "SELECT status, error FROM shopify_inventory_adjustments " +
                "WHERE tenant_id = ? AND trigger_type = 'return_inspection' ORDER BY created_at DESC LIMIT 1",
                DEMO_ID);
        assertThat(adjustment.get("status")).isEqualTo("failed");
        assertThat(adjustment.get("error").toString()).contains("No store found for tenant");
    }

    // -----------------------------------------------------------------------
    // (e) No Bosta poll pickup for the courier_accounts-less demo tenant.
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    void noBostaAccountRegisteredForDemoTenant() {
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();

        assertThat(countRows("courier_accounts", DEMO_ID)).isZero();

        // The exact driving query BostaStatusPollJob/BostaDiscoveryPollJob use to decide
        // which tenants to poll — the demo tenant structurally cannot appear in it.
        List<UUID> polledTenants = jdbc.queryForList(
                "SELECT ca.tenant_id FROM courier_accounts ca", UUID.class);
        assertThat(polledTenants).doesNotContain(DEMO_ID);
    }

    // -----------------------------------------------------------------------
    // (f) EmailGateway is never invoked for the is_demo tenant, even though its
    //     own fixture seeds exactly the CRITICAL/HIGH + blocked-customer data that
    //     would otherwise fire the immediate sweep and the daily digest.
    // -----------------------------------------------------------------------
    @Test
    @Order(6)
    void emailGateway_neverInvokedForDemoTenant_immediateSweepAndDigest() {
        demoSeeder.ensureBootstrapped();
        demoSeeder.reseed();

        // The demo fixture itself seeds a CRITICAL (lost piece) and a LOW (blocked
        // customer) exception — real signal that would otherwise trip both jobs.
        Long lostCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pieces WHERE tenant_id = ? AND status = 'lost'::piece_status",
                Long.class, DEMO_ID);
        assertThat(lostCount).isGreaterThanOrEqualTo(1L);

        immediateJob.run();
        digestJob.run();

        verify(emailGateway, never()).send(any(), any(), any());
        verify(emailGateway, never()).sendMagicLink(any(), any());

        // Confirm the guard is the is_demo filter, not "no owner/manager recipient" —
        // the demo owner IS an active owner and would otherwise qualify as a recipient.
        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner' AND active = true",
                Long.class, DEMO_ID);
        assertThat(ownerCount).isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // (g) ISSUE 1b — the seeded OPEN receiving session (REC-DEMO-2) finalizes for
    //     real through ReceivingService.finalize()/InventoryLedger.batchReceive()
    //     without a pieces_short_code_tenant_unique violation. Before the
    //     piece_counters seed in loadGoldenFixture(), this call would have failed:
    //     batchReceive()'s counter claim started at 0 for a tenant piece_counters
    //     had never seen, colliding with the fixture's own raw-inserted short codes.
    // -----------------------------------------------------------------------
    @Test
    @Order(7)
    void demoOpenReceivingSession_finalizesWithoutUniqueViolation() {
        demoSeeder.reseed();

        UUID openSessionId = jdbc.queryForObject(
                "SELECT id FROM receipts WHERE tenant_id = ? AND reference = 'REC-DEMO-2'",
                UUID.class, DEMO_ID);
        UUID ownerId = jdbc.queryForObject(
                "SELECT id FROM users WHERE tenant_id = ? AND role = 'owner'",
                UUID.class, DEMO_ID);

        assertThatCode(() ->
                TenantContext.runAs(DEMO_ID, () -> receiving.finalize(openSessionId, ownerId))
        ).as("finalize() must not throw a short_code unique violation for the demo tenant")
                .doesNotThrowAnyException();

        Long piecesFromThisSession = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pieces WHERE receipt_id = ?", Long.class, openSessionId);
        assertThat(piecesFromThisSession).as("5 + 5 seeded line quantities").isEqualTo(10L);

        String status = jdbc.queryForObject(
                "SELECT status FROM receipts WHERE id = ?", String.class, openSessionId);
        assertThat(status).isEqualTo("finalized");
    }
}
