package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Finding 2 (2026-11-04): Step 5a's exchange_dispatch −1 is SKIPPED (status 'skipped', skip_reason
 * 'shopify_native_exchange', no Shopify call, no alert) for the replacement of a Shopify-native exchange — a dashboard
 * Bosta exchange (exchanges row, no Traced request) whose
 *   (a) businessReference ends in Shopify's Return name (":…-R<digits>"), or
 *   (b) matched original is a Shopify order with a same-variant line Traced never counted (quantity 0, current_qty > 0).
 *
 * A1/A2 rule (a) + control; B1/B2/B3 rule (b) + controls; P1 Traced portal and L1 Lookup exchanges still decrement
 * exactly once even with both signals present; H1 a claim queued BEFORE the fix (the prod BROEK row's shape) is never
 * sent by the resume; F1 a failed native claim's re-push never sends; X1 cross-tenant on app_user with the same-tenant
 * positive control.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyNativeExchangeDispatchTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    static final String SHOP_DOMAIN = "native-exchange.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/877";
    static final String ITEM_GID    = "gid://shopify/InventoryItem/8601";

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean BostaGateway         bostaGateway;

    @Autowired JdbcTemplate            jdbc;
    @Autowired ObjectMapper            mapper;
    @Autowired ShopifyInventoryService shopifyInventory;
    @Autowired PickupSessionService    pickups;
    @Autowired ExceptionService        exceptions;

    UUID tenantId, storeId, variantId, otherVariantId, mainLoc, actorId;
    UUID tenantB;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID(); actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Native Exchange Tenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Worker', ?, 'h', 'owner')",
            actorId, tenantId, "w-" + actorId + "@native.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            storeId, tenantId, SHOP_DOMAIN);
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/85', 'Dress', 'active')",
            productId, tenantId, storeId);
        variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'gid://shopify/ProductVariant/8501', 'Red / L', 'DR-R-L')",
            variantId, tenantId, productId);
        otherVariantId = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'gid://shopify/ProductVariant/8502', 'Red / M', 'DR-R-M')",
            otherVariantId, tenantId, productId);
        mainLoc = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", mainLoc, tenantId, TRACED_GID);

        tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Native Exchange B')", tenantB);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-test");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn(ITEM_GID);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        UUID t = tenantId;
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM pickup_shipments WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM pickups WHERE tenant_id = ?", t);
        jdbc.update("UPDATE exchanges SET outbound_order_id = NULL, matched_order_id = NULL, return_request_id = NULL WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
    }

    // ── A: the businessReference names a Shopify Return ─────────────────────────

    @Test
    void a1_returnNameReference_skipped_noCall_noAlert() throws Exception {
        Fixture f = exchange(Kind.DASHBOARD, "BRK-46369-EG:BRK-46369-EG-R1", Original.NONE);
        closePickup(f);
        awaitRow(f.piece());
        assertSkippedNative(f.piece());
        assertNoAlert();
    }

    @Test
    void a2_referenceWithoutAReturnSuffix_countedOriginal_stillDecrementsOnce() throws Exception {
        Fixture plain = exchange(Kind.DASHBOARD, "BRK-46370-EG", Original.COUNTED_SAME_VARIANT);
        closePickup(plain);
        awaitRow(plain.piece());
        assertDecrementedOnce(plain.piece());

        reset(shopifyGateway);
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn(ITEM_GID);
        Fixture notAReturn = exchange(Kind.DASHBOARD, "BRK-46371-EG:BRK-46371-EG-X1", Original.NONE);
        closePickup(notAReturn);
        awaitRow(notAReturn.piece());
        assertDecrementedOnce(notAReturn.piece());
    }

    // ── B: the Shopify original has a same-variant line Traced never counted ────

    @Test
    void b1_uncountedSameVariantLineOnTheShopifyOriginal_skipped_noCall_noAlert() throws Exception {
        Fixture f = exchange(Kind.DASHBOARD, null, Original.UNCOUNTED_SAME_VARIANT);
        closePickup(f);
        awaitRow(f.piece());
        assertSkippedNative(f.piece());
        assertNoAlert();
    }

    @Test
    void b2_uncountedLineOfAnotherVariant_stillDecrementsOnce() throws Exception {
        Fixture f = exchange(Kind.DASHBOARD, null, Original.UNCOUNTED_OTHER_VARIANT);
        closePickup(f);
        awaitRow(f.piece());
        assertDecrementedOnce(f.piece());
    }

    @Test
    void b3_originalNotAShopifyOrder_stillDecrementsOnce() throws Exception {
        Fixture f = exchange(Kind.DASHBOARD, null, Original.UNCOUNTED_SAME_VARIANT_NOT_SHOPIFY);
        closePickup(f);
        awaitRow(f.piece());
        assertDecrementedOnce(f.piece());
    }

    // ── P / L: Traced-booked exchanges are never skipped ────────────────────────

    @Test
    void p1_tracedPortalExchange_withBothSignals_decrementsExactlyOnce() throws Exception {
        Fixture f = exchange(Kind.PORTAL, "BRK-46372-EG:BRK-46372-EG-R1", Original.UNCOUNTED_SAME_VARIANT);
        closePickup(f);
        awaitRow(f.piece());
        shopifyInventory.onExchangeReplacementDispatched(tenantId, f.piece()).get(5, TimeUnit.SECONDS);   // repeat: no second call
        assertDecrementedOnce(f.piece());
    }

    @Test
    void l1_lookupExchange_withBothSignals_decrementsExactlyOnce() throws Exception {
        Fixture f = exchange(Kind.LOOKUP, "BRK-46373-EG:BRK-46373-EG-R1", Original.UNCOUNTED_SAME_VARIANT);
        closePickup(f);
        awaitRow(f.piece());
        shopifyInventory.onExchangeReplacementDispatched(tenantId, f.piece()).get(5, TimeUnit.SECONDS);
        assertDecrementedOnce(f.piece());
    }

    // ── H / F: claims written before the fix are never sent ─────────────────────

    @Test
    void h1_queuedBeforeTheFix_resume_skipsIt_noCall() {
        // The prod BROEK row: claimed 'queued' (held while the store needed a reconnect) before the fix existed.
        Fixture f = exchange(Kind.DASHBOARD, "BRK-46369-EG:BRK-46369-EG-R1", Original.UNCOUNTED_SAME_VARIANT);
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", f.piece());
        insertClaim(f.piece(), "queued");

        TenantContext.runAs(tenantId, () -> shopifyInventory.resumeHeldExchangeDispatches(tenantId));
        assertSkippedNative(f.piece());
        TenantContext.runAs(tenantId, () -> shopifyInventory.resumeHeldExchangeDispatches(tenantId));
        verify(shopifyGateway, never()).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        assertNoAlert();
    }

    @Test
    void f1_failedNativeClaim_repush_skipsIt_noCall() {
        Fixture f = exchange(Kind.DASHBOARD, null, Original.UNCOUNTED_SAME_VARIANT);
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", f.piece());
        insertClaim(f.piece(), "failed");

        TenantContext.runAs(tenantId, () -> shopifyInventory.repushFailedVoidOrHold("exchange_dispatch", f.piece()));
        assertSkippedNative(f.piece());
        assertNoAlert();
    }

    // ── X: cross-tenant on a real app_user connection ─────────────────────────

    @Test
    void x1_otherTenantsContext_nothing_sameTenantOnAppUser_skipsNative_andDecrementsAControl() throws Exception {
        Fixture nat = exchange(Kind.DASHBOARD, "BRK-46374-EG:BRK-46374-EG-R2", Original.UNCOUNTED_SAME_VARIANT);
        Fixture ctl = exchange(Kind.DASHBOARD, "BRK-46375-EG", Original.COUNTED_SAME_VARIANT);
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id IN (?, ?)", nat.piece(), ctl.piece());

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        ShopifyInventoryService appUserService = new ShopifyInventoryService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            mapper, new StoreRepository(appJdbc, appTxm));

        // Tenant B's context naming tenant A's pieces: RLS hides them → nothing written, no call.
        appUserService.onExchangeReplacementDispatched(tenantB, nat.piece()).get(5, TimeUnit.SECONDS);
        appUserService.onExchangeReplacementDispatched(tenantB, ctl.piece()).get(5, TimeUnit.SECONDS);
        assertThat(claimCount(nat.piece()) + claimCount(ctl.piece())).isZero();
        verify(shopifyGateway, never()).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());

        // Same tenant on the same app_user connection: the rule sees A's rows under RLS.
        appUserService.onExchangeReplacementDispatched(tenantId, nat.piece()).get(5, TimeUnit.SECONDS);
        assertSkippedNative(nat.piece());
        appUserService.onExchangeReplacementDispatched(tenantId, ctl.piece()).get(5, TimeUnit.SECONDS);
        assertDecrementedOnce(ctl.piece());

        Integer visibleToB = TenantContext.runAs(tenantB, () -> new TransactionTemplate(appTxm).execute(s ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'exchange_dispatch'", Integer.class)));
        assertThat(visibleToB).isZero();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    enum Kind { DASHBOARD, PORTAL, LOOKUP }
    enum Original { NONE, COUNTED_SAME_VARIANT, UNCOUNTED_SAME_VARIANT, UNCOUNTED_OTHER_VARIANT, UNCOUNTED_SAME_VARIANT_NOT_SHOPIFY }
    record Fixture(UUID orderId, String tracking, String piece) {}

    /**
     * An internal exchange order (one line, a 'created' forward leg, one packed piece at the main warehouse) for an
     * exchange of {@code kind}: DASHBOARD = an exchanges row with no request; PORTAL = an exchanges row booked for a
     * Traced request (return_request_id); LOOKUP = a Lookup request naming the order (replacement_order_id) — with a
     * dashboard-looking exchanges row as well, the worst case. The exchanges row carries {@code businessReference}
     * and is matched to {@code original}.
     */
    private Fixture exchange(Kind kind, String businessReference, Original original) {
        String tracking = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        UUID originalId = original == Original.NONE ? null : originalOrder(original);

        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'awaiting_pickup'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, "internal:exchange:" + tracking, "EXC-" + tracking);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, tenantId, orderId, variantId);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'forward')", tenantId, orderId, tracking);
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'packed'::piece_status, ?, ?)",
            piece, tenantId, variantId, "PC-" + piece, piece, mainLoc, orderId);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            tenantId, item, piece);

        UUID requestId = null;
        if (kind != Kind.DASHBOARD) {
            requestId = jdbc.queryForObject(
                "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, origin, replacement_order_id) " +
                "VALUES (?, ?, 'exchange', 'approved', ?, ?, ?) RETURNING id", UUID.class,
                tenantId, originalId != null ? originalId : orderId, reference(),
                kind == Kind.LOOKUP ? "lookup" : "portal", kind == Kind.LOOKUP ? orderId : null);
        }
        String raw = businessReference == null ? "{}"
            : mapper.createObjectNode().put("businessReference", businessReference).toString();
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, outbound_variant_id, " +
            "matched_order_id, match_method, return_request_id, raw) VALUES (?, ?, 'matched', ?, ?, ?, ?, ?, ?::jsonb)",
            tenantId, tracking, orderId, variantId, originalId, originalId == null ? null : "reference",
            kind == Kind.PORTAL ? requestId : null, raw);
        return new Fixture(orderId, tracking, piece);
    }

    /** The exchange's original order: delivered, its first line counted (qty 1), plus Shopify's exchange line. */
    private UUID originalOrder(Original original) {
        long n = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        String ext = original == Original.UNCOUNTED_SAME_VARIANT_NOT_SHOPIFY ? "internal:exchange:orig-" + n : "gid://shopify/Order/" + n;
        UUID id = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, ext, "BRK-" + n + "-EG");
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
            "VALUES (?, ?, ?, 1, ?, '{\"quantity\": 1, \"current_quantity\": 1}'::jsonb)",
            tenantId, id, otherVariantId, "gid://shopify/LineItem/" + n + "1");
        if (original != Original.COUNTED_SAME_VARIANT) {
            UUID exchangeLineVariant = original == Original.UNCOUNTED_OTHER_VARIANT ? otherVariantId : variantId;
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                "VALUES (?, ?, ?, 0, ?, '{\"quantity\": 1, \"current_quantity\": 1}'::jsonb)",
                tenantId, id, exchangeLineVariant, "gid://shopify/LineItem/" + n + "2");
        } else {
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                "VALUES (?, ?, ?, 1, ?, '{\"quantity\": 1, \"current_quantity\": 1}'::jsonb)",
                tenantId, id, variantId, "gid://shopify/LineItem/" + n + "2");
        }
        return id;
    }

    private String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }

    private void insertClaim(String piece, String status) {
        jdbc.update("INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, payload, status, error) " +
            "VALUES (?, gen_random_uuid(), ?, ?, -1, 'exchange_dispatch', ?, '{\"reason\":\"exchange_dispatch\",\"delta\":-1}'::jsonb, ?, ?)",
            tenantId, variantId, mainLoc, piece, status, "failed".equals(status) ? "Shopify said no" : null);
    }

    private void closePickup(Fixture f) {
        UUID pickup = pickups.openSession(tenantId, actorId, LocalDate.now(), null, null);
        assertThat(pickups.scan(tenantId, pickup, actorId, f.tracking()).outcome())
            .isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        pickups.closeSession(tenantId, pickup, actorId);
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, f.piece()))
            .isEqualTo("with_courier");
    }

    private void awaitRow(String piece) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments " +
                "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ? AND status <> 'pending'", Integer.class, piece);
            if (done != null && done >= 1) { Thread.sleep(200); return; }   // let a stray second trigger finish
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for exchange_dispatch on " + piece);
    }

    private int claimCount(String piece) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", Integer.class, piece);
    }

    private void assertSkippedNative(String piece) {
        assertThat(claimCount(piece)).as("one claim row").isEqualTo(1);
        Map<String, Object> r = jdbc.queryForMap("SELECT status, skip_reason, delta FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", piece);
        assertThat(r).containsEntry("status", "skipped").containsEntry("skip_reason", "shopify_native_exchange");
        verify(shopifyGateway, never()).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    private void assertDecrementedOnce(String piece) {
        assertThat(claimCount(piece)).as("one claim row").isEqualTo(1);
        Map<String, Object> r = jdbc.queryForMap("SELECT status, skip_reason, delta, location_id FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", piece);
        assertThat(r).containsEntry("status", "applied").containsEntry("delta", -1).containsEntry("location_id", mainLoc);
        assertThat(r.get("skip_reason")).isNull();
        verify(shopifyGateway, times(1)).pushExchangeDispatch(eq(SHOP_DOMAIN), eq("shpat-test"), eq(ITEM_GID), eq(TRACED_GID),
            eq(-1), eq("traced://piece/" + piece), anyString());
    }

    private void assertNoAlert() {
        assertThat((List<?>) TenantContext.runAs(tenantId, () -> exceptions.listExceptions("void_hold_sync_failed", null, 0, 50))
            .get("items")).as("a native skip is the correct outcome, not a failure").isEmpty();
    }
}
