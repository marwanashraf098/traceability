package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyStoreNeedsReauthException;
import com.traceability.integrations.shopify.ShopifyTransientException;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify silent disconnect (live 2026-10-27, testreciving: every token refresh got 401 but the store stayed
 * 'connected' — ShopifyTokenProvider wrote needs_reauth inside the transaction it then rolled back). The REAL token
 * provider runs here; only the Shopify gateway is mocked.
 *
 *   n1 — refresh rejected (401 / invalid_grant): stores.status = 'needs_reauth' is COMMITTED, the HIGH
 *        shopify_connection_lost alert is open ("Shopify connection lost — reconnect to keep stock in sync"), the
 *        stock update is kept (failed never_sent, 1 attempt)
 *   n2 — while needs_reauth: the retry pass spends no attempt and calls nothing; the piece / transfer sends leave
 *        their claims alone (a person's re-send is told to reconnect)
 *   n3 — refresh 5xx / timeout: the store stays 'connected', no alert, and the retry pass sends the update
 *   n4 — reconnect (new tokens, status 'connected'): the alert clears, the waiting update is applied exactly once
 *   n5 — stock-take write-off push while needs_reauth: held ('failed' + heldForReconnect, no rethrow → no JobRunr
 *        retry, no Shopify call); released once after reconnect and pushed exactly once
 *   (exchange_dispatch held / resumed: ExchangeDispatchDecrementTest.h1)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyConnectionLostTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifyInventoryService inventory;
    @Autowired TransferShopifySync transfers;
    @Autowired ExceptionService exceptions;
    @Autowired EncryptionService encryption;
    @Autowired StockTakeShopifyPushJob stockTakePush;
    @MockBean ShopifyGateway shopifyGateway;

    record T(UUID tenant, UUID store, String shop, UUID location) {}
    record V(UUID id, String item) {}

    @BeforeEach
    void reset() { Mockito.reset(shopifyGateway); }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void n1_refreshRejected_storeMarkedNeedsReauth_highAlert_updateKept() throws Exception {
        T t = tenant("n1"); V v = variant(t, "n1");
        refreshRejected(t);

        UUID session = receive(t, v, 2);

        assertThat(status(t)).as("committed, not rolled back").isEqualTo("needs_reauth");
        assertThat(claim(t, session, v)).containsEntry("status", "failed")
            .containsEntry("failure_class", "never_sent").containsEntry("attempt_count", 1);
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        Map<String, Object> lost = connectionLost(t);
        assertThat(lost).isNotNull();
        assertThat(lost.get("severity")).isEqualTo("HIGH");
        assertThat((String) lost.get("descriptionEn")).startsWith("Shopify connection lost — reconnect to keep stock in sync");
        assertThat((String) lost.get("descriptionAr")).startsWith("انقطع الاتصال بشوبيفاي");
        assertThat(lost).containsEntry("actionUrl", "/settings?tab=connections").containsEntry("clearsWhen", "SHOPIFY_RECONNECTED");
        assertThat(increment(t)).as("the waiting update names the fix")
            .containsEntry("kind", "setup").containsEntry("problem", "NEEDS_REAUTH");
    }

    @Test
    void n2_whileNeedsReauth_noAttemptSpent_noShopifyCall() throws Exception {
        T t = tenant("n2"); V v = variant(t, "n2");
        refreshRejected(t);
        UUID session = receive(t, v, 2);
        makeDue(t, session, v);
        clearInvocations(shopifyGateway);

        ShopifyInventoryService.RetryResult r = TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);

        assertThat(r.blockedBy()).isEqualTo(IncrementRecoveryRules.SetupProblem.NEEDS_REAUTH);
        assertThat(claim(t, session, v)).containsEntry("status", "failed").containsEntry("attempt_count", 1);
        verifyNoInteractions(shopifyGateway);

        // Piece / transfer sends: nothing is touched; a person's re-send is refused with the fix.
        TenantContext.runAs(t.tenant(), () -> { inventory.pushPieceClaimNow(t.tenant(), -1L, false); return null; });
        assertThat(TenantContext.runAs(t.tenant(), () -> transfers.sweep(t.tenant()))).isZero();
        assertThatThrownBy(() -> TenantContext.runAs(t.tenant(), () -> {
                inventory.pushPieceClaimNow(t.tenant(), -1L, true); return null; }))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Reconnect Shopify");
        verifyNoInteractions(shopifyGateway);
    }

    @Test
    void n3_refreshTransient_staysConnected_retried() throws Exception {
        T t = tenant("n3"); V v = variant(t, "n3");
        expiringTokens(t);
        when(shopifyGateway.refreshAccessToken(eq(t.shop()), anyString()))
            .thenThrow(new ShopifyTransientException("Shopify refresh 503 for " + t.shop(), null))
            .thenReturn(new ShopifyGateway.TokenResponse("shpat_new", "shprt_new", 3600L, 7_776_000L, null));

        UUID session = receive(t, v, 2);

        assertThat(status(t)).isEqualTo("connected");
        assertThat(connectionLost(t)).isNull();
        assertThat(claim(t, session, v)).containsEntry("status", "failed").containsEntry("failure_class", "never_sent");

        makeDue(t, session, v);
        ShopifyInventoryService.RetryResult r = TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
        assertThat(r.blockedBy()).isNull();
        assertThat(claim(t, session, v)).containsEntry("status", "applied").containsEntry("attempt_count", 2);
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(eq(t.shop()), eq("shpat_new"), any(), any(), eq(2), any(), any());
    }

    @Test
    void n4_reconnect_alertClears_waitingUpdateAppliedExactlyOnce() throws Exception {
        T t = tenant("n4"); V v = variant(t, "n4");
        refreshRejected(t);
        UUID session = receive(t, v, 2);
        makeDue(t, session, v);
        TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);    // blocked while needs_reauth
        assertThat(status(t)).isEqualTo("needs_reauth");

        // Reconnect: the OAuth callback's write (UPDATE_STORE_TOKEN) — new tokens, status 'connected'.
        jdbc.update("UPDATE stores SET access_token_encrypted = ?, access_token_expires_at = ?, " +
                "refresh_token_encrypted = ?, refresh_token_expires_at = ?, status = 'connected' WHERE id = ?",
            encryption.encrypt("shpat_reconnected"), Timestamp.from(Instant.now().plusSeconds(3600)),
            encryption.encrypt("shprt_reconnected"), Timestamp.from(Instant.now().plusSeconds(7_776_000)), t.store());
        assertThat(connectionLost(t)).as("clears by itself on reconnect").isNull();
        clearInvocations(shopifyGateway);

        TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
        TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);   // a second pass sends nothing more

        verify(shopifyGateway, times(1)).adjustInventoryQuantities(eq(t.shop()), eq("shpat_reconnected"), any(), any(),
            eq(2), any(), any());
        assertThat(claim(t, session, v)).containsEntry("status", "applied").containsEntry("attempt_count", 2);
        assertThat(increment(t)).isNull();
    }

    @Test
    void n5_stockTakePush_heldWhileNeedsReauth_pushedOnceAfterReconnect() {
        T t = tenant("n5"); V v = variant(t, "n5");
        UUID owner = UUID.randomUUID(), session = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'h', 'owner')",
            owner, t.tenant(), "o-" + owner + "@n5.test");
        jdbc.update("INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by) " +
            "VALUES (?, ?, 'finalized', 'all', ?, ?)", session, t.tenant(), t.location(), owner);
        jdbc.update("INSERT INTO stock_take_shopify_syncs (tenant_id, session_id, status, payload) VALUES (?, ?, 'pending', ?::jsonb)",
            t.tenant(), session, "{\"locationId\": \"" + t.location() + "\", \"deltas\": {\"" + v.id() + "\": -2}}");
        jdbc.update("UPDATE stores SET status = 'needs_reauth' WHERE id = ?", t.store());

        stockTakePush.push(session, t.tenant());   // no exception: JobRunr spends no retry

        Map<String, Object> held = jdbc.queryForMap("SELECT status, error, send_started_at, " +
            "jsonb_exists(payload, 'heldForReconnect') AS held FROM stock_take_shopify_syncs WHERE session_id = ?", session);
        assertThat(held).containsEntry("status", "failed").containsEntry("held", true).containsEntry("send_started_at", null);
        assertThat((String) held.get("error")).startsWith("Held");
        assertThat(TenantContext.runAs(t.tenant(), () -> stockTakePush.releaseHeld(t.tenant()))).as("still disconnected").isEmpty();
        verifyNoInteractions(shopifyGateway);

        // Reconnect: fresh tokens, status 'connected'.
        jdbc.update("UPDATE stores SET access_token_encrypted = ?, access_token_expires_at = ?, " +
                "refresh_token_encrypted = ?, refresh_token_expires_at = ?, status = 'connected' WHERE id = ?",
            encryption.encrypt("shpat_back"), Timestamp.from(Instant.now().plusSeconds(3600)),
            encryption.encrypt("shprt_back"), Timestamp.from(Instant.now().plusSeconds(7_776_000)), t.store());
        assertThat(TenantContext.runAs(t.tenant(), () -> stockTakePush.releaseHeld(t.tenant()))).containsExactly(session);
        assertThat(TenantContext.runAs(t.tenant(), () -> stockTakePush.releaseHeld(t.tenant())))
            .as("released once — never enqueued twice").isEmpty();

        stockTakePush.push(session, t.tenant());
        stockTakePush.push(session, t.tenant());   // a duplicate run finds it pushed: no second call

        assertThat(jdbc.queryForObject("SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, session))
            .isEqualTo("pushed");
        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(eq(t.shop()), eq("shpat_back"), any(), any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, " +
            "connection_type) VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, 'oauth')", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, "gid://shopify/Location/" + shop);
        return new T(tenant, store, shop, location);
    }

    private V variant(T t, String key) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String item = "gid://shopify/InventoryItem/" + key + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, t.tenant(), t.store(), "gid://shopify/Product/" + t.shop() + "-" + key);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, t.tenant(), product,
            "gid://shopify/ProductVariant/" + t.shop() + "-" + key, "SKU-" + key, item);
        return new V(variant, item);
    }

    /** An access token about to expire (so the next call refreshes) and a valid refresh token. */
    private void expiringTokens(T t) {
        jdbc.update("UPDATE stores SET access_token_encrypted = ?, access_token_expires_at = ?, " +
                "refresh_token_encrypted = ?, refresh_token_expires_at = ? WHERE id = ?",
            encryption.encrypt("shpat_old"), Timestamp.from(Instant.now().plusSeconds(60)),
            encryption.encrypt("shprt_old"), Timestamp.from(Instant.now().plusSeconds(7_000_000)), t.store());
    }

    /** Shopify answers the refresh with 401 — ShopifyHttpGateway maps every 4xx to this exception. */
    private void refreshRejected(T t) {
        expiringTokens(t);
        when(shopifyGateway.refreshAccessToken(eq(t.shop()), anyString()))
            .thenThrow(new ShopifyStoreNeedsReauthException(t.shop(), "Shopify rejected refresh with 401 UNAUTHORIZED"));
    }

    private UUID receive(T t, V v, int qty) throws Exception {
        UUID session = UUID.randomUUID();
        inventory.onReceivingSessionClose(t.tenant(), session, t.location(), Map.of(v.id(), qty)).get(5, TimeUnit.SECONDS);
        return session;
    }

    private void makeDue(T t, UUID session, V v) {
        jdbc.update("UPDATE shopify_inventory_adjustments SET next_attempt_at = now() - interval '1 second' " +
            "WHERE tenant_id = ? AND trigger_id = ? AND variant_id = ? AND next_attempt_at IS NOT NULL",
            t.tenant(), session.toString(), v.id());
    }

    private String status(T t) {
        return jdbc.queryForObject("SELECT status::text FROM stores WHERE id = ?", String.class, t.store());
    }

    private Map<String, Object> claim(T t, UUID session, V v) {
        return jdbc.queryForMap("SELECT status, failure_class, attempt_count FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_id = ? AND variant_id = ?", t.tenant(), session.toString(), v.id());
    }

    private List<Map<String, Object>> alerts(T t) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen());
    }

    private Map<String, Object> connectionLost(T t) {
        return alerts(t).stream().filter(e -> "shopify_connection_lost".equals(e.get("type"))).findFirst().orElse(null);
    }

    private Map<String, Object> increment(T t) {
        return alerts(t).stream().filter(e -> "inventory_increment_sync_failed".equals(e.get("type"))).findFirst().orElse(null);
    }
}
