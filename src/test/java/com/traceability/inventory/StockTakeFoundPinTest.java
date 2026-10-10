package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * D5 (2026-10-10) — pins EVERY stock_take_found outcome of the pre-refactor
 * StockTakeReconciliationService.foundIncrementEligible, written and run green against it BEFORE
 * it moved onto PieceShopifyRules (one shared "departure reached Shopify" predicate). A lost piece
 * scanned in a stock take gets the +1 only when its latest → lost departure really left Shopify:
 *
 *   pin1  stock-take write-off FROM available, its push 'pushed' with the variant in the deltas   → +1
 *   pin2  … push 'failed'                                                                          → 0
 *   pin3  … push 'pending'                                                                         → 0
 *   pin4  … push 'failed_ambiguous'                                                                → 0
 *   pin5  … push 'pushed' but the variant is not in its deltas                                      → 0
 *   pin6  … push wholly superseded by the seed, write-off at/before the seed's snapshot             → +1
 *   pin7  … push superseded by the seed, write-off AFTER the snapshot                              → 0
 *   pin8  … partially superseded: the variant moved to payload.superseded, before the snapshot      → +1
 *   pin9  on_hold → lost, the hold cycle's hold_enter 'applied'                                     → +1
 *   pin10 on_hold → lost, the hold cycle's hold_enter 'failed'                                      → 0
 *   pin11 on_hold → lost, hold_enter applied only for an EARLIER hold cycle                        → 0
 *   pin12 a manual Lookup lost from available (no Shopify claim of any kind)                        → 0
 *   pin13 damaged → lost                                                                            → 0
 *   pin14 with_courier → lost (courier)                                                             → 0
 *   pin15 a lost piece with no → lost event                                                         → 0
 *   pin16 on_hold → lost by a stock-take write-off, its hold_enter applied                          → +1
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakeFoundPinTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate                   jdbc;
    @Autowired StockTakeService               stockTake;
    @Autowired StockTakeReconciliationService reconciliation;

    record T(UUID tenant, UUID store, UUID location, UUID user, UUID variant) {}

    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void stubs() {
        Mockito.reset(jobScheduler, shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── stock-take write-offs from available ─────────────────────────────────────

    @Test void pin1_pushed_variantInDeltas_plusOne()        { assertFound(stockTakeWriteOff("pushed", true, null, false), 1); }
    @Test void pin2_pushFailed_noPlusOne()                  { assertFound(stockTakeWriteOff("failed", true, null, false), 0); }
    @Test void pin3_pushPending_noPlusOne()                 { assertFound(stockTakeWriteOff("pending", true, null, false), 0); }
    @Test void pin4_pushAmbiguous_noPlusOne()               { assertFound(stockTakeWriteOff("failed_ambiguous", true, null, false), 0); }
    @Test void pin5_pushed_variantNotInDeltas_noPlusOne()   { assertFound(stockTakeWriteOff("pushed", false, null, false), 0); }
    @Test void pin6_supersededBySeed_beforeSnapshot_plusOne() {
        assertFound(stockTakeWriteOff("superseded_by_seed", true, "now() + interval '1 hour'", false), 1);
    }
    @Test void pin7_supersededBySeed_afterSnapshot_noPlusOne() {
        assertFound(stockTakeWriteOff("superseded_by_seed", true, "now() - interval '3 hours'", false), 0);
    }
    @Test void pin8_partiallySuperseded_beforeSnapshot_plusOne() {
        assertFound(stockTakeWriteOff("pending", false, "now() + interval '1 hour'", true), 1);
    }

    // ── on_hold → lost ───────────────────────────────────────────────────────────

    @Test void pin9_onHoldLost_holdEnterApplied_plusOne()   { assertFound(heldThenLost("applied", false, "theft_suspected"), 1); }
    @Test void pin10_onHoldLost_holdEnterFailed_noPlusOne() { assertFound(heldThenLost("failed", false, "theft_suspected"), 0); }
    @Test void pin11_onHoldLost_earlierCycleApplied_noPlusOne() { assertFound(heldThenLost("applied", true, "theft_suspected"), 0); }
    @Test void pin16_onHoldStockTakeWriteOff_holdEnterApplied_plusOne() {
        assertFound(heldThenLost("applied", false, "stock_take_missing"), 1);
    }

    // ── other departures ─────────────────────────────────────────────────────────

    @Test void pin12_manualLostFromAvailable_noPlusOne() {
        T t = tenant("p12");
        String p = lostPiece(t);
        event(t, p, "adjusted", "available", "lost", "{\"reason\":\"cycle_count_missing\"}", "now() - interval '2 hours'");
        assertFound(new Case(t, p), 0);
    }

    @Test void pin13_damagedThenLost_noPlusOne() {
        T t = tenant("p13");
        String p = lostPiece(t);
        event(t, p, "adjusted", "available", "damaged", "{\"reason\":\"damaged_in_storage\"}", "now() - interval '3 hours'");
        event(t, p, "adjusted", "damaged", "lost", "{\"reason\":\"stock_take_missing\",\"session_id\":\"" + UUID.randomUUID() + "\"}",
            "now() - interval '2 hours'");
        assertFound(new Case(t, p), 0);
    }

    @Test void pin14_courierLost_noPlusOne() {
        T t = tenant("p14");
        String p = lostPiece(t);
        event(t, p, "courier_update", "with_courier", "lost", null, "now() - interval '2 hours'");
        assertFound(new Case(t, p), 0);
    }

    @Test void pin15_noLostEvent_noPlusOne() {
        T t = tenant("p15");
        assertFound(new Case(t, lostPiece(t)), 0);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    record Case(T t, String piece) {}

    /** A piece written off in an earlier stock take (FROM available) whose push row has the given state.
     *  snapshotSql: superseded_snapshot_at; partial: the variant sits in payload.superseded. */
    private Case stockTakeWriteOff(String pushStatus, boolean variantInDeltas, String snapshotSql, boolean partial) {
        T t = tenant("st-" + pushStatus);
        String p = lostPiece(t);
        UUID earlier = session(t, "finalized");
        event(t, p, "adjusted", "available", "lost",
            "{\"reason\":\"stock_take_missing\",\"session_id\":\"" + earlier + "\"}", "now() - interval '2 hours'");
        String deltas = variantInDeltas ? "{\"" + t.variant() + "\": 1}" : "{\"" + UUID.randomUUID() + "\": 1}";
        String superseded = partial ? ", \"superseded\": {\"" + t.variant() + "\": 1}" : "";
        jdbc.update("INSERT INTO stock_take_shopify_syncs (tenant_id, session_id, status, payload, pushed_at, " +
            "superseded_snapshot_at) VALUES (?, ?, ?, ?::jsonb, " + ("pushed".equals(pushStatus) ? "now()" : "NULL") + ", " +
            (snapshotSql == null ? "NULL" : snapshotSql) + ")",
            t.tenant(), earlier, pushStatus, "{\"deltas\": " + deltas + superseded + "}");
        return new Case(t, p);
    }

    /** available → on_hold (hold_event_id) → lost; hold_enter claim with the given status, for this
     *  cycle or (earlierCycle) only for a previous one. */
    private Case heldThenLost(String holdEnterStatus, boolean earlierCycle, String lostReason) {
        T t = tenant("h-" + holdEnterStatus);
        String p = lostPiece(t);
        String cycle = UUID.randomUUID().toString();
        if (earlierCycle) {
            String old = UUID.randomUUID().toString();
            event(t, p, "held", "available", "on_hold", "{\"hold_event_id\":\"" + old + "\"}", "now() - interval '5 hours'");
            event(t, p, "unheld", "on_hold", "available", null, "now() - interval '4 hours'");
            holdClaim(t, p + ":" + old, holdEnterStatus);
        } else {
            holdClaim(t, p + ":" + cycle, holdEnterStatus);
        }
        event(t, p, "held", "available", "on_hold", "{\"hold_event_id\":\"" + cycle + "\"}", "now() - interval '3 hours'");
        String meta = "stock_take_missing".equals(lostReason)
            ? "{\"reason\":\"stock_take_missing\",\"session_id\":\"" + session(t, "finalized") + "\"}"
            : "{\"reason\":\"" + lostReason + "\"}";
        event(t, p, "adjusted", "on_hold", "lost", meta, "now() - interval '2 hours'");
        return new Case(t, p);
    }

    private void holdClaim(T t, String triggerId, String status) {
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, -1, 'hold_enter', ?, ?, now() - interval '3 hours')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), triggerId, status);
    }

    /** Opens a stock take, scans the lost piece (found) and one available piece, and reads the plan. */
    @SuppressWarnings("unchecked")
    private void assertFound(Case c, long expected) {
        T t = c.t();
        String other = piece(t, "available");
        UUID s = TenantContext.runAs(t.tenant(), () ->
            (UUID) stockTake.openSession("all", null, t.location(), null, t.user()).get("sessionId"));
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, other, "good", t.user()));
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, c.piece(), "good", t.user()));
        TenantContext.runAs(t.tenant(), () -> reconciliation.attestComplete(s, t.user()));
        Map<String, Object> plan = (Map<String, Object>) TenantContext.runAs(t.tenant(),
            () -> reconciliation.reconciliation(s)).get("finalizePlan");
        assertThat(plan).containsEntry("founds", 1).containsEntry("foundIncrements", expected);
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, "gid://shopify/Location/" + shop);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shop, "SKU-" + name,
            "gid://shopify/InventoryItem/" + shop);
        return new T(tenant, store, location, user, variant);
    }

    private String piece(T t, String status) {
        int k = seq.incrementAndGet();
        String id = "01PIN" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, now() - interval '1 day')",
            id, t.tenant(), t.variant(), status, "PIN-" + id, String.format("N%07d", k), t.location());
        return id;
    }

    private String lostPiece(T t) { return piece(t, "lost"); }

    private UUID session(T t, String status) {
        UUID s = UUID.randomUUID();
        jdbc.update("INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by, opened_at) " +
            "VALUES (?, ?, ?, 'all', ?, ?, now() - interval '6 hours')", s, t.tenant(), status, t.location(), t.user());
        return s;
    }

    private void event(T t, String piece, String type, String from, String to, String metadata, String atSql) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, metadata, occurred_at, location_id) " +
            "VALUES (?, ?, ?, ?::piece_status, ?::piece_status, ?::jsonb, " + atSql + ", ?)",
            t.tenant(), piece, type, from, to, metadata, t.location());
    }
}
