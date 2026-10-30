package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Drift fix 2 (2026-10-30) — the Traced location deactivated in Shopify (BROEK: every +N failed never_sent "The variant
 * couldn't be stocked at Traced Main Warehouse because the location was deactivated", silently, until its attempts ran out).
 * The REAL services run; Shopify (gateway, token, location reads) is mocked.
 *
 *   l1 — a write fails on the deactivated location → locations.shopify_location_state = 'inactive', HIGH
 *        shopify_location_inactive alert (EN / AR, → Settings › Connections, clears by itself), and everything is held
 *        like needs_reauth: the retry pass spends no attempt, a new +N calls nothing, a piece claim stays queued (a
 *        person's re-send is told to reactivate), the stock-take push is held
 *   l2 — the periodic check notices a deactivation on its own (no failed write needed)
 *   l3 — reactivation (the check reads isActive = true) → the alert clears and the held updates go out exactly once
 *        (a second check sends nothing)
 *   l4 — owner "Re-send failed updates" for claims that used up their attempts meanwhile: +N applied through the normal
 *        path, the −1s owed for its units (Fix 1 revisit) sent once, Shopify's count = Traced's; a second re-send sends
 *        nothing; a manager gets 403
 *   l5 — deleted location (the write's answer, or the check finds no location) → 'deleted' + the alert with
 *        reconnect / recreate guidance
 *   l6 — a normal active store is unaffected: +N applies, no flag, no alert, the check changes nothing
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyLocationStateTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";
    static final String DEACTIVATED =
        "inventoryActivate failed: The variant couldn't be stocked at Traced Main Warehouse because the location was deactivated.";
    static final String NOT_FOUND = "inventoryActivate failed: The variant couldn't be stocked because the location wasn't found.";

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired PieceAdjustService adjust;
    @Autowired ShopifyInventoryService inventory;
    @Autowired ShopifyLocationCheckService checks;
    @Autowired StockTakeShopifyPushJob stockTakePush;
    @Autowired TransferShopifySync transfers;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyLocationGateway locationGateway;
    @MockBean JobScheduler jobScheduler;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID owner, UUID manager,
             UUID variant, String item) {}

    final List<JobLambda> enqueued = new CopyOnWriteArrayList<>();
    final AtomicInteger seq = new AtomicInteger();
    /** Shopify's available count at the Traced location, by inventory item — what the mocked writes did to it. */
    final Map<String, Integer> shopifyCount = new ConcurrentHashMap<>();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider, locationGateway, jobScheduler);
        enqueued.clear();
        shopifyCount.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(jobScheduler.enqueue(any(JobLambda.class))).thenAnswer(inv -> { enqueued.add(inv.getArgument(0)); return null; });
        doAnswer(inv -> { shopifyCount.merge(inv.getArgument(2), (Integer) inv.getArgument(4), Integer::sum); return null; })
            .when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        doAnswer(inv -> { shopifyCount.merge(inv.getArgument(2), (Integer) inv.getArgument(4), Integer::sum); return null; })
            .when(shopifyGateway).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── l1 ────────────────────────────────────────────────────────────────────────

    @Test
    void l1_writeFailsOnDeactivatedLocation_flag_highAlert_everythingHeld() throws Exception {
        T t = tenant("l1");
        UUID counted = appliedReceipt(t, 1);
        String piece = piece(t, counted);
        locationAnswers(DEACTIVATED);

        UUID session = receive(t, 2);

        assertThat(locationState(t)).isEqualTo("inactive");
        assertThat(claim(t, session)).containsEntry("status", "failed").containsEntry("failure_class", "never_sent")
            .containsEntry("attempt_count", 1);
        Map<String, Object> a = alert(t, "shopify_location_inactive");
        assertThat(a).isNotNull();
        assertThat(a.get("severity")).isEqualTo("HIGH");
        assertThat((String) a.get("descriptionEn"))
            .startsWith("Your Traced location in Shopify is deactivated — reactivate it to keep stock in sync");
        assertThat((String) a.get("descriptionAr")).startsWith("موقع Traced في شوبيفاي غير مُفعَّل");
        assertThat(a).containsEntry("actionUrl", "/settings?tab=connections")
            .containsEntry("clearsWhen", "SHOPIFY_LOCATION_ACTIVE").containsEntry("suggestedAction", "reactivate_shopify_location");
        clearInvocations(shopifyGateway);

        // Held: the retry pass spends nothing, a new +N calls nothing.
        makeDue(t, session);
        ShopifyInventoryService.RetryResult r = TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
        assertThat(r.blockedBy()).isEqualTo(IncrementRecoveryRules.SetupProblem.LOCATION_INACTIVE);
        assertThat(claim(t, session)).containsEntry("attempt_count", 1);
        UUID second = receive(t, 1);
        assertThat(claim(t, second)).containsEntry("status", "failed").containsEntry("attempt_count", 1);
        assertThat((String) claim(t, second).get("error")).startsWith("Held — the Traced location is inactive");

        // A piece departure: claimed, stays queued, nothing sent; a person's re-send is told to reactivate.
        as(t, () -> adjust.markSold(piece, null, t.owner()));
        Thread.sleep(300);
        TenantContext.runAs(t.tenant(), () -> inventory.sweepPieceClaims(t.tenant()));
        assertThat(pieceClaim(t, "piece_sold", piece)).containsEntry("status", "queued").containsEntry("attempt_count", 0);
        long claimId = ((Number) pieceClaim(t, "piece_sold", piece).get("id")).longValue();
        assertThatThrownBy(() -> TenantContext.runAs(t.tenant(), () -> { inventory.pushPieceClaimNow(t.tenant(), claimId, true); return null; }))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Reactivate the Traced location in Shopify");
        assertThat(TenantContext.runAs(t.tenant(), () -> transfers.sweep(t.tenant()))).isZero();

        // The stock-take push is held (no JobRunr retry spent).
        UUID st = stockTakeSync(t);
        stockTakePush.push(st, t.tenant());
        Map<String, Object> held = jdbc.queryForMap("SELECT status, error, jsonb_exists(payload, 'heldForReconnect') AS held " +
            "FROM stock_take_shopify_syncs WHERE session_id = ?", st);
        assertThat(held).containsEntry("status", "failed").containsEntry("held", true);
        assertThat((String) held.get("error")).startsWith("Held — the Traced location is not active in Shopify");

        verify(shopifyGateway, never()).activateInventoryItem(any(), any(), any(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
    }

    // ── l2 ────────────────────────────────────────────────────────────────────────

    @Test
    void l2_periodicCheckNoticesDeactivation() {
        T t = tenant("l2");
        when(locationGateway.readLocationActive(eq(t.shop()), eq("tok"), eq(t.traced()))).thenReturn(Optional.of(false));

        assertThat(TenantContext.runAs(t.tenant(), () -> checks.check(t.tenant()))).contains("inactive");

        assertThat(locationState(t)).isEqualTo("inactive");
        assertThat(alert(t, "shopify_location_inactive")).isNotNull();
        verifyNoInteractions(shopifyGateway);
    }

    // ── l3 ────────────────────────────────────────────────────────────────────────

    @Test
    void l3_reactivation_alertClears_heldUpdatesReleasedOnce() throws Exception {
        T t = tenant("l3");
        UUID counted = appliedReceipt(t, 1);
        String piece = piece(t, counted);
        locationAnswers(DEACTIVATED);
        UUID session = receive(t, 2);                       // fails → inactive (next retry in 10 min)
        as(t, () -> adjust.markSold(piece, null, t.owner()));  // held: queued
        UUID st = stockTakeSync(t);
        stockTakePush.push(st, t.tenant());                 // held
        Thread.sleep(300);
        assertThat(locationState(t)).isEqualTo("inactive");

        // The merchant reactivates "Traced Main Warehouse" in Shopify.
        doNothing().when(shopifyGateway).activateInventoryItem(any(), any(), any(), any(), any());
        when(locationGateway.readLocationActive(any(), any(), any())).thenReturn(Optional.of(true));
        clearInvocations(shopifyGateway);

        assertThat(TenantContext.runAs(t.tenant(), () -> checks.check(t.tenant()))).contains("active");
        for (JobLambda job : enqueued) job.run();
        TenantContext.runAs(t.tenant(), () -> checks.check(t.tenant()));   // still active: nothing more
        TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
        TenantContext.runAs(t.tenant(), () -> inventory.sweepPieceClaims(t.tenant()));

        assertThat(alert(t, "shopify_location_inactive")).as("clears by itself").isNull();
        assertThat(claim(t, session)).containsEntry("status", "applied").containsEntry("attempt_count", 2);
        assertThat(pieceClaim(t, "piece_sold", piece)).containsEntry("status", "applied");
        assertThat(jdbc.queryForObject("SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, st))
            .isEqualTo("pushed");
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), eq(t.item()), eq(t.traced()), eq(2), any(), any());
        verify(shopifyGateway, times(1)).pushPieceSold(any(), any(), eq(t.item()), eq(t.traced()), eq(-1), any(), any());
        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(any(), any(), any(), eq(t.traced()), any(), any());
        assertThat(enqueued).hasSize(1);
    }

    // ── l4 ────────────────────────────────────────────────────────────────────────

    @Test
    void l4_ownerResendsExhaustedClaims_owedDecrementsFollow_shopifyMatchesTraced() {
        T t = tenant("l4");
        UUID counted = appliedReceipt(t, 3);
        shopifyCount.put(t.item(), 3);                                   // Shopify has the 3 counted units
        List<String> before = List.of(piece(t, counted), piece(t, counted), piece(t, counted));
        UUID r = exhaustedReceipt(t, 4);                                 // +4 failed 5× on the deactivated location
        List<String> lostOnes = List.of(piece(t, r), piece(t, r), piece(t, r), piece(t, r));
        as(t, () -> adjust.markSold(lostOnes.get(0), null, t.owner()));    // sold meanwhile: skipped (+4 never counted)
        as(t, () -> adjust.markSold(lostOnes.get(1), null, t.owner()));
        assertThat(pieceClaim(t, "piece_sold", lostOnes.get(0))).containsEntry("skip_reason", "arrival_not_counted");
        assertThat(TenantContext.runAs(t.tenant(), () -> inventory.countLocationFailures(t.tenant()))).isEqualTo(1);

        assertThat(post(t, "manager", "/api/v1/shopify/location/resend-failed").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> res = post(t, "owner", "/api/v1/shopify/location/resend-failed");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("increments", 1).containsEntry("applied", 1);
        assertThat(jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'receiving_session' AND trigger_id = ?", String.class, t.tenant(), r.toString())).isEqualTo("applied");
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), eq(t.item()), any(), eq(4), any(), any());
        verify(shopifyGateway, times(2)).pushPieceSold(any(), any(), eq(t.item()), any(), eq(-1), any(), any());
        int tracedAvailable = jdbc.queryForObject("SELECT COUNT(*) FROM pieces WHERE tenant_id = ? AND status = 'available' " +
            "AND current_location_id = ?", Integer.class, t.tenant(), t.location());
        assertThat(tracedAvailable).isEqualTo(before.size() + 2);
        assertThat(shopifyCount.get(t.item())).as("Shopify = Traced").isEqualTo(tracedAvailable);

        ResponseEntity<Map> again = post(t, "owner", "/api/v1/shopify/location/resend-failed");
        assertThat(again.getBody()).containsEntry("increments", 0).containsEntry("applied", 0);
        TenantContext.runAs(t.tenant(), () -> inventory.sweepPieceClaims(t.tenant()));
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, times(2)).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(audits(t, "shopify_location_resend_failed")).isEqualTo(2);
    }

    // ── l5 ────────────────────────────────────────────────────────────────────────

    @Test
    void l5_deletedLocation_alertWithRecreateGuidance() throws Exception {
        T t = tenant("l5");
        locationAnswers(NOT_FOUND);
        receive(t, 1);

        assertThat(locationState(t)).isEqualTo("deleted");
        Map<String, Object> a = alert(t, "shopify_location_inactive");
        assertThat((String) a.get("descriptionEn")).startsWith("Your Traced location in Shopify was deleted — stock updates are on hold")
            .contains("Reconnect Shopify").contains("recreate the location");
        assertThat((String) a.get("descriptionAr")).startsWith("تم حذف موقع Traced في شوبيفاي");
        assertThat(a).containsEntry("suggestedAction", "recreate_shopify_location");

        T u = tenant("l5b");
        when(locationGateway.readLocationActive(eq(u.shop()), any(), any())).thenReturn(Optional.empty());
        assertThat(TenantContext.runAs(u.tenant(), () -> checks.check(u.tenant()))).contains("deleted");
        assertThat(alert(u, "shopify_location_inactive")).isNotNull();
    }

    // ── l6 ────────────────────────────────────────────────────────────────────────

    @Test
    void l6_normalActiveStore_unaffected() throws Exception {
        T t = tenant("l6");
        UUID session = receive(t, 2);
        when(locationGateway.readLocationActive(any(), any(), any())).thenReturn(Optional.of(true));

        assertThat(TenantContext.runAs(t.tenant(), () -> checks.check(t.tenant()))).contains("active");

        assertThat(claim(t, session)).containsEntry("status", "applied");
        assertThat(locationState(t)).isEqualTo("active");
        assertThat(alert(t, "shopify_location_inactive")).isNull();
        assertThat(TenantContext.runAs(t.tenant(), () -> IncrementRecoveryRules.holdReason(jdbc, t.tenant()))).isNull();
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** Shopify answers every activation at the Traced location with {@code error} (as ShopifyHttpGateway throws it). */
    private void locationAnswers(String error) {
        doThrow(new ShopifyException(error)).when(shopifyGateway).activateInventoryItem(any(), any(), any(), any(), any());
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        UUID owner = UUID.randomUUID(), manager = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        String item = "gid://shopify/InventoryItem/" + name + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            owner, tenant, name + "-o-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Manager', ?, 'h', 'manager')",
            manager, tenant, name + "-m-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shop, "SKU-" + name, item);
        return new T(tenant, store, shop, location, traced, owner, manager, variant, item);
    }

    private UUID appliedReceipt(T t, int units) {
        return receipt(t, units, "applied", 1, null, null);
    }

    /** +N that used up its 5 attempts on the deactivated location (never sent). */
    private UUID exhaustedReceipt(T t, int units) {
        return receipt(t, units, "failed", 5, "never_sent", "Activation at the Traced location failed (adjust not sent): " + DEACTIVATED);
    }

    private UUID receipt(T t, int units, String status, int attempts, String failureClass, String error) {
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, t.tenant(), t.owner(), t.location());
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, attempt_count, failure_class, error, first_attempt_at, created_at) " +
            "VALUES (?, ?, ?, ?, ?, 'receiving_session', ?, ?::jsonb, ?, ?, ?, ?, now() - interval '2 days', now() - interval '2 days')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), units, receipt.toString(),
            "{\"reason\": \"received\", \"delta\": " + units + "}", status, attempts, failureClass, error);
        return receipt;
    }

    private String piece(T t, UUID receipt) {
        int k = seq.incrementAndGet();
        String id = "01LS" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, receipt_id) " +
            "VALUES (?, ?, ?, 'available'::piece_status, ?, ?, ?, ?)",
            id, t.tenant(), t.variant(), "LS-" + id, String.format("L%07d", k), t.location(), receipt);
        return id;
    }

    private UUID receive(T t, int qty) throws Exception {
        UUID session = UUID.randomUUID();
        inventory.onReceivingSessionClose(t.tenant(), session, t.location(), Map.of(t.variant(), qty)).get(10, TimeUnit.SECONDS);
        return session;
    }

    /** A finalized stock take whose −1 push is pending. */
    private UUID stockTakeSync(T t) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by) " +
            "VALUES (?, ?, 'finalized', 'all', ?, ?)", session, t.tenant(), t.location(), t.owner());
        jdbc.update("INSERT INTO stock_take_shopify_syncs (tenant_id, session_id, status, payload) VALUES (?, ?, 'pending', ?::jsonb)",
            t.tenant(), session, "{\"locationId\": \"" + t.location() + "\", \"deltas\": {\"" + t.variant() + "\": 1}}");
        return session;
    }

    private void makeDue(T t, UUID session) {
        jdbc.update("UPDATE shopify_inventory_adjustments SET next_attempt_at = now() - interval '1 second' " +
            "WHERE tenant_id = ? AND trigger_id = ?", t.tenant(), session.toString());
    }

    private String locationState(T t) {
        return jdbc.queryForObject("SELECT shopify_location_state FROM locations WHERE id = ?", String.class, t.location());
    }

    private Map<String, Object> claim(T t, UUID session) {
        return jdbc.queryForMap("SELECT status, failure_class, attempt_count, error FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'receiving_session' AND trigger_id = ?", t.tenant(), session.toString());
    }

    private Map<String, Object> pieceClaim(T t, String triggerType, String piece) {
        return jdbc.queryForMap("SELECT id, status, skip_reason, attempt_count FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = ? AND split_part(trigger_id, ':', 1) = ?", t.tenant(), triggerType, piece);
    }

    private Map<String, Object> alert(T t, String type) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> type.equals(e.get("type"))).findFirst().orElse(null);
    }

    private int audits(T t, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Integer.class,
            t.tenant(), action);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(T t, String role, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken("owner".equals(role) ? t.owner() : t.manager(), t.tenant(), role));
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(h), Map.class);
    }

    private void as(T t, Runnable r) {
        TenantContext.runAs(t.tenant(), () -> { r.run(); return null; });
    }
}
