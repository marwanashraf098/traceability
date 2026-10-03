package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.inventory.StockTakeReconciliationService;
import com.traceability.inventory.StockTakeService;
import com.traceability.inventory.UlidGenerator;
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

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Review mode S4 — the mute list for simulated-courier tenants (V130), each against a real control.
 *
 *   m1 stuck_shipment: an old 'created' forward leg → muted for simulated, still raised for real
 *   m2 cancelled_live_shipment: a cancelled order with a 'created' leg → muted for simulated, raised for real
 *   m3 every other detector still runs for a simulated tenant (a lost piece stays CRITICAL)
 *   m4 immediate CRITICAL/HIGH email: never to a simulated tenant, still to a real one
 *   m5 daily digest email: never to a simulated tenant, still to a real one
 *   m6 inventory claim: a simulated tenant's non-gid (fixture) variant → no claim row; its real gid
 *      variant → claimed; a real tenant's non-gid variant → claimed (unchanged)
 *   m7 stock-take push: a simulated tenant's non-gid write-off is left out of the deltas
 *      (nothing_to_push); a real tenant's is pushed (pending)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "org.jobrunr.background-job-server.enabled=true")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewModeMuteTest {

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

    @Autowired JdbcTemplate                   jdbc;
    @Autowired ExceptionService               exceptions;
    @Autowired ExceptionImmediateAlertJob     immediate;
    @Autowired ExceptionDigestJob             digest;
    @Autowired ShopifyInventoryService        inventory;
    @Autowired StockTakeService               stockTake;
    @Autowired StockTakeReconciliationService reconciliation;

    @MockBean EmailGateway         emailGateway;
    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    /** A tenant with an owner, a linked fulfillment location, a store, a gid variant and a fixture variant. */
    private final class T {
        final UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID(), store = UUID.randomUUID(),
                   location = UUID.randomUUID(), gidVariant, fixtureVariant;
        final String email = "owner-" + UUID.randomUUID() + "@test.com";

        T(String name, boolean simulated) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, name);
            if (simulated) jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'test')", tenant);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'x', 'owner')",
                owner, tenant, email);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status) " +
                "VALUES (?, ?, 'Main', true, 'gid://shopify/Location/1', 'linked')", location, tenant);
            jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
                "VALUES (?, ?, ?, 'connected', 'idle', 'read_products,write_inventory', now())",
                store, tenant, "mute-" + store + ".myshopify.com");
            gidVariant     = variant("gid://shopify/ProductVariant/" + UUID.randomUUID());
            fixtureVariant = variant("review-fixture:variant:" + UUID.randomUUID());
        }

        UUID variant(String externalId) {
            UUID product = jdbc.queryForObject(
                "INSERT INTO products (tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P', 'active') RETURNING id",
                UUID.class, tenant, store, "prod:" + externalId);
            return jdbc.queryForObject(
                "INSERT INTO variants (tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V', ?) RETURNING id",
                UUID.class, tenant, product, externalId, "SKU-" + UUID.randomUUID());
        }

        UUID order(String status) {
            return jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, '#M', ?::order_status, now()) RETURNING id",
                UUID.class, tenant, store, "ext-" + UUID.randomUUID(), status);
        }

        void createdLeg(UUID order, int ageDays) {
            jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at) " +
                "VALUES (?, ?, 'bosta', ?, 'created', 'forward', now() - (? * interval '1 day'))",
                tenant, order, String.valueOf(3_100_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 899_999_999L)), ageDays);
        }

        String piece(UUID variant, String status) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'M' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
                id, tenant, variant, "PC-" + id, id, status, location);
            return id;
        }

        List<String> openTypes() {
            return TenantContext.runAs(tenant, () -> exceptions.detectAllOpen().stream()
                .map(e -> String.valueOf(e.get("type"))).toList());
        }
    }

    @BeforeEach
    void resetMocks() { reset(emailGateway, jobScheduler, shopifyGateway, tokenProvider); }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── Detectors ────────────────────────────────────────────────────────────

    @Test
    void m1_stuckShipment_mutedForSimulated_raisedForReal() {
        T sim = new T("M1s", true), real = new T("M1r", false);
        sim.createdLeg(sim.order("new"), 30);
        real.createdLeg(real.order("new"), 30);
        assertThat(sim.openTypes()).doesNotContain("stuck_shipment");
        assertThat(real.openTypes()).contains("stuck_shipment");
    }

    @Test
    void m2_cancelledLiveShipment_mutedForSimulated_raisedForReal() {
        T sim = new T("M2s", true), real = new T("M2r", false);
        sim.createdLeg(sim.order("cancelled"), 0);
        real.createdLeg(real.order("cancelled"), 0);
        assertThat(sim.openTypes()).doesNotContain("cancelled_live_shipment");
        assertThat(real.openTypes()).contains("cancelled_live_shipment");
    }

    @Test
    void m3_otherDetectors_stillRunForSimulated() {
        T sim = new T("M3", true);
        sim.piece(sim.gidVariant, "lost");
        assertThat(sim.openTypes()).isNotEmpty();
    }

    // ── Emails ───────────────────────────────────────────────────────────────

    @Test
    void m4_immediateEmail_neverToSimulated_stillToReal() {
        T sim = new T("M4s", true), real = new T("M4r", false);
        sim.piece(sim.gidVariant, "lost");      // CRITICAL
        real.piece(real.gidVariant, "lost");
        immediate.run();
        verify(emailGateway, never()).send(eq(sim.email), anyString(), anyString());
        verify(emailGateway, atLeastOnce()).send(eq(real.email), anyString(), anyString());
    }

    @Test
    void m5_dailyDigest_neverToSimulated_stillToReal() {
        T sim = new T("M5s", true), real = new T("M5r", false);
        sim.piece(sim.gidVariant, "lost");
        real.piece(real.gidVariant, "lost");
        digest.run();
        verify(emailGateway, never()).send(eq(sim.email), anyString(), anyString());
        verify(emailGateway, atLeastOnce()).send(eq(real.email), anyString(), anyString());
    }

    // ── Shopify inventory claims ─────────────────────────────────────────────

    @Test
    void m6_inventoryClaim_skippedOnlyForASimulatedTenantsFixtureVariant() throws Exception {
        T sim = new T("M6s", true), real = new T("M6r", false);
        UUID simSession = UUID.randomUUID(), realSession = UUID.randomUUID();
        inventory.onReceivingSessionClose(sim.tenant, simSession, sim.location,
            Map.of(sim.fixtureVariant, 2, sim.gidVariant, 1)).get(30, TimeUnit.SECONDS);
        inventory.onReceivingSessionClose(real.tenant, realSession, real.location,
            Map.of(real.fixtureVariant, 2)).get(30, TimeUnit.SECONDS);

        assertThat(claimedVariants(simSession)).containsExactly(sim.gidVariant);
        assertThat(claimedVariants(realSession)).containsExactly(real.fixtureVariant);
    }

    @Test
    void m7_stockTakePush_leavesOutASimulatedTenantsFixtureVariant() {
        T sim = new T("M7s", true), real = new T("M7r", false);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        assertThat(finalizeWithOneWriteOff(sim, sim.fixtureVariant)).isEqualTo("nothing_to_push");
        assertThat(finalizeWithOneWriteOff(real, real.fixtureVariant)).isEqualTo("pending");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<UUID> claimedVariants(UUID sessionId) {
        return jdbc.queryForList("SELECT variant_id FROM shopify_inventory_adjustments WHERE trigger_type = 'receiving_session' " +
            "AND trigger_id = ?", UUID.class, sessionId.toString());
    }

    /** One piece written off from available, one other piece scanned, finalized; returns the push claim's status. */
    private String finalizeWithOneWriteOff(T t, UUID variant) {
        String missing = t.piece(variant, "available");
        return TenantContext.runAs(t.tenant, () -> {
            UUID session = (UUID) stockTake.openSession("all", null, t.location, null, t.owner).get("sessionId");
            String counted = t.piece(variant, "available");
            stockTake.scan(session, "PC-" + counted, "good", t.owner);
            reconciliation.attestComplete(session, t.owner);
            reconciliation.resolve(session, List.of(new StockTakeReconciliationService.ResolveItem(missing, "lost")), t.owner);
            reconciliation.finalizeSession(session, t.owner);
            return jdbc.queryForObject("SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, session);
        });
    }
}
