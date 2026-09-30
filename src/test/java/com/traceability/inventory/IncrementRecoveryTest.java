package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyAdjustFailedException;
import com.traceability.integrations.shopify.ShopifyAdjustFailedException.FailureClass;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Failed-increment recovery (Part D).
 *
 *   r1 — never_sent: classified on the original attempt; the retry uses the SAME key.
 *   r2 — rejected: the retry uses a NEW key (claim key + attempt number), fresh baseline.
 *   r3 — ambiguous: resent IDENTICALLY (same key, same sent baseline) within 20 h.
 *   r4 — ambiguous past 20 h: no send at all; alert "gave_up".
 *   r5 — setup problem (location not linked): no attempt spent, one exception naming the fix
 *        with the blocked count; auto-resolves once linked and the retry succeeds.
 *   r6 — setup problem (missing scope): "Reconnect Shopify to grant inventory access".
 *   r7 — legacy: never retried automatically; one exception "N units across M variants … since".
 *   r8 — backoff 10 min, 1 h, 6 h, 24 h, then stop after 5 attempts (alert "gave_up").
 *   r9 — the delta is never recomputed: every retry sends the claim's original delta.
 *   r10 — manual repush: 409 CONFIRMATION_REQUIRED past 24 h; with confirm it sends (new key).
 *   x1 — cross-tenant on app_user: tenant A's pass never touches B's claim; A's own retry works.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncrementRecoveryTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifyInventoryService inventory;
    @Autowired ExceptionService exceptions;
    @Autowired ObjectMapper mapper;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced) {}
    record V(UUID id, String item) {}

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── r1–r4: classification + the key each class retries with ─────────────────

    @Test
    void r1_neverSent_retriedWithTheSameKey() throws Exception {
        T t = tenant("r1"); V v = variant(t, "r1");
        failNextAdjustWith(FailureClass.NEVER_SENT, null);
        UUID session = receive(t, v, 3);
        assertThat(row(t, session, v).get("failure_class")).isEqualTo("never_sent");

        makeDue(t, session, v);
        retry(t);

        List<String> keys = adjustKeys();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).as("never_sent retries with the SAME key").isEqualTo(keys.get(0));
        assertThat(keys.get(0)).isEqualTo(ShopifyGateway.idempotencyKey(t.tenant(), "receiving_session", session.toString(), v.id(), t.location()));
        assertThat(row(t, session, v)).containsEntry("status", "applied").containsEntry("attempt_count", 2);
    }

    @Test
    void r2_rejected_retriedWithANewKey_claimKeyPlusAttempt() throws Exception {
        T t = tenant("r2"); V v = variant(t, "r2");
        failNextAdjustWith(FailureClass.REJECTED, 4);
        UUID session = receive(t, v, 3);
        assertThat(row(t, session, v).get("failure_class")).isEqualTo("rejected");

        makeDue(t, session, v);
        retry(t);

        List<String> keys = adjustKeys();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).isNotEqualTo(keys.get(0))
            .isEqualTo(IncrementRecoveryRules.retryKey(t.tenant(), "receiving_session", session.toString(), v.id(), t.location(), 2));
        verify(shopifyGateway, never()).resendInventoryAdjustment(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(row(t, session, v).get("status")).isEqualTo("applied");
    }

    @Test
    void r3_ambiguous_resentIdenticallyWithin20h() throws Exception {
        T t = tenant("r3"); V v = variant(t, "r3");
        failNextAdjustWith(FailureClass.AMBIGUOUS, 7);
        UUID session = receive(t, v, 3);
        Map<String, Object> first = row(t, session, v);
        assertThat(first).containsEntry("failure_class", "ambiguous").containsEntry("change_from_quantity", 7);
        String firstKey = adjustKeys().get(0);

        makeDue(t, session, v);
        retry(t);

        verify(shopifyGateway).resendInventoryAdjustment(eq(t.shop()), eq("tok"), eq(v.item()), eq(t.traced()),
            eq(3), eq("received"), eq(firstKey), eq(7));
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(row(t, session, v).get("status")).isEqualTo("applied");
    }

    @Test
    void r4_ambiguousPast20h_noSend_alertGaveUp() throws Exception {
        T t = tenant("r4"); V v = variant(t, "r4");
        failNextAdjustWith(FailureClass.AMBIGUOUS, 7);
        UUID session = receive(t, v, 5);
        jdbc.update("UPDATE shopify_inventory_adjustments SET sent_key_first_at = now() - interval '21 hours' " +
            "WHERE tenant_id = ? AND trigger_id = ?", t.tenant(), session.toString());
        makeDue(t, session, v);

        retry(t);

        verify(shopifyGateway, never()).resendInventoryAdjustment(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(row(t, session, v)).containsEntry("status", "failed").containsEntry("next_attempt_at", null);
        Map<String, Object> alert = alert(t, "gave_up");
        assertThat(alert).isNotNull();
        assertThat(((Number) alert.get("units")).intValue()).isEqualTo(5);
        assertThat((String) alert.get("descriptionEn")).contains("5 units across 1 variants");
    }

    // ── r5–r6: setup problems block attempts and name the fix ───────────────────

    @Test
    void r5_locationNotLinked_noAttempt_oneExceptionNamingTheFix_autoResolves() throws Exception {
        T t = tenant("r5"); V v = variant(t, "r5");
        jdbc.update("UPDATE locations SET shopify_sync_status = 'error', shopify_location_id = NULL WHERE id = ?", t.location());
        UUID s1 = receive(t, v, 2);
        UUID s2 = receive(t, v, 4);
        assertThat(row(t, s1, v)).containsEntry("failure_class", "never_sent").containsEntry("attempt_count", 1);
        makeDue(t, s1, v); makeDue(t, s2, v);
        clearInvocations(shopifyGateway);

        ShopifyInventoryService.RetryResult r = retry(t);

        assertThat(r.blockedBy()).isEqualTo(IncrementRecoveryRules.SetupProblem.LOCATION_NOT_LINKED);
        verifyNoInteractions(shopifyGateway);
        assertThat(row(t, s1, v).get("attempt_count")).as("no attempt spent").isEqualTo(1);
        List<Map<String, Object>> alerts = alerts(t);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)).containsEntry("kind", "setup");
        assertThat((String) alerts.get(0).get("descriptionEn")).startsWith("Link your warehouse to a Shopify location")
            .contains("2 stock update(s)");

        // Fixed: linked → the retries succeed → the exception is gone.
        jdbc.update("UPDATE locations SET shopify_sync_status = 'linked', shopify_location_id = ? WHERE id = ?", t.traced(), t.location());
        retry(t);
        assertThat(row(t, s1, v).get("status")).isEqualTo("applied");
        assertThat(row(t, s2, v).get("status")).isEqualTo("applied");
        assertThat(alerts(t)).isEmpty();
    }

    @Test
    void r6_missingScope_exceptionSaysReconnect() throws Exception {
        T t = tenant("r6"); V v = variant(t, "r6");
        jdbc.update("UPDATE stores SET access_token_scopes = 'read_orders,read_products' WHERE id = ?", t.store());
        UUID s = receive(t, v, 1);
        makeDue(t, s, v);
        clearInvocations(shopifyGateway);

        retry(t);

        verifyNoInteractions(shopifyGateway);
        Map<String, Object> alert = alert(t, "setup");
        assertThat((String) alert.get("descriptionEn")).startsWith("Reconnect Shopify to grant inventory access");
    }

    // ── r7: legacy ─────────────────────────────────────────────────────────────

    @Test
    void r7_legacy_neverRetried_oneExceptionWithUnitsVariantsSince() throws Exception {
        T t = tenant("r7"); V a = variant(t, "r7a"); V b = variant(t, "r7b");
        failNextAdjustWith(FailureClass.REJECTED, 0);
        UUID s1 = receive(t, a, 6);
        failNextAdjustWith(FailureClass.REJECTED, 0);
        UUID s2 = receive(t, b, 4);
        jdbc.update("UPDATE shopify_inventory_adjustments SET legacy = true, created_at = '2026-07-21 10:00+00', " +
            "next_attempt_at = now() - interval '1 minute' WHERE tenant_id = ?", t.tenant());
        clearInvocations(shopifyGateway);

        retry(t);

        verifyNoInteractions(shopifyGateway);
        assertThat(row(t, s1, a).get("status")).isEqualTo("failed");
        List<Map<String, Object>> alerts = alerts(t);
        assertThat(alerts).hasSize(1);
        assertThat((String) alerts.get(0).get("descriptionEn"))
            .isEqualTo("10 units across 2 variants received in Traced never reached Shopify (since 2026-07-21)");
        assertThat((List<?>) alerts.get(0).get("variants")).hasSize(2);
        assertThat(row(t, s2, b).get("attempt_count")).isEqualTo(1);
    }

    // ── r8–r9: backoff and the delta ────────────────────────────────────────────

    @Test
    void r8_backoff_10m_1h_6h_24h_thenStopsAfter5() throws Exception {
        T t = tenant("r8"); V v = variant(t, "r8");
        doThrow(new ShopifyAdjustFailedException(FailureClass.REJECTED, 0, "inventoryAdjustQuantities failed: nope", null))
            .when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        UUID s = receive(t, v, 2);

        List<Long> gapsMinutes = new java.util.ArrayList<>();
        for (int attempt = 1; attempt <= 5; attempt++) {
            Map<String, Object> row = row(t, s, v);
            assertThat(row.get("attempt_count")).isEqualTo(attempt);
            Object gap = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_attempt_at - last_attempt_at) / 60 " +
                "FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_id = ?", Object.class, t.tenant(), s.toString());
            gapsMinutes.add(gap == null ? null : Math.round(((Number) gap).doubleValue()));
            if (attempt < 5) { makeDue(t, s, v); retry(t); }
        }
        assertThat(gapsMinutes).containsExactly(10L, 60L, 360L, 1440L, null);

        makeDue(t, s, v);   // even if forced due, a claim with 5 attempts is not picked
        retry(t);
        verify(shopifyGateway, times(5)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(alert(t, "gave_up")).isNotNull();
    }

    @Test
    void r9_deltaNeverRecomputed_everyRetrySendsTheOriginalDelta() throws Exception {
        T t = tenant("r9"); V v = variant(t, "r9");
        failNextAdjustWith(FailureClass.REJECTED, 1);
        UUID s = receive(t, v, 3);
        // Traced's stock for the variant changes after the failure — the retry must still send +3.
        for (int i = 0; i < 9; i++) {
            String id = String.format("01RNINE%019d", i);
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                "VALUES (?, ?, ?, 'available'::piece_status, ?, ?, ?)", id, t.tenant(), v.id(), "R9-" + i, "N" + i, t.location());
        }
        makeDue(t, s, v);
        retry(t);

        ArgumentCaptor<Integer> deltas = ArgumentCaptor.forClass(Integer.class);
        verify(shopifyGateway, times(2)).adjustInventoryQuantities(any(), any(), any(), any(), deltas.capture(), any(), any());
        assertThat(deltas.getAllValues()).containsExactly(3, 3);
    }

    // ── r10: manual repush ──────────────────────────────────────────────────────

    @Test
    void r10_manualRepush_confirmRequiredPast24h_thenSendsWithNewKey() throws Exception {
        T t = tenant("r10"); V v = variant(t, "r10");
        failNextAdjustWith(FailureClass.REJECTED, 0);
        UUID s = receive(t, v, 2);
        jdbc.update("UPDATE shopify_inventory_adjustments SET first_attempt_at = now() - interval '30 hours', legacy = true " +
            "WHERE tenant_id = ?", t.tenant());

        TenantContext.set(t.tenant());
        assertThatThrownBy(() -> inventory.repushFailedIncrement("receiving_session", s.toString(), v.id(), false))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("CONFIRMATION_REQUIRED");
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        inventory.repushFailedIncrement("receiving_session", s.toString(), v.id(), true);
        TenantContext.clear();

        List<String> keys = adjustKeys();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(1)).isEqualTo(IncrementRecoveryRules.retryKey(t.tenant(), "receiving_session", s.toString(), v.id(), t.location(), 2));
        assertThat(row(t, s, v).get("status")).isEqualTo("applied");
    }

    // ── x1: cross-tenant on a real app_user connection ─────────────────────────

    @Test
    void x1_appUser_tenantAPassNeverTouchesB_ownRetryWorks() throws Exception {
        T a = tenant("x1a"); V va = variant(a, "x1a");
        T b = tenant("x1b"); V vb = variant(b, "x1b");
        failNextAdjustWith(FailureClass.REJECTED, 0);
        UUID sa = receive(a, va, 2);
        failNextAdjustWith(FailureClass.REJECTED, 0);
        UUID sb = receive(b, vb, 3);
        makeDue(a, sa, va); makeDue(b, sb, vb);
        clearInvocations(shopifyGateway);

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        ShopifyInventoryService appService = new ShopifyInventoryService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            mapper, new StoreRepository(appJdbc, appTxm));

        ShopifyInventoryService.RetryResult r = TenantContext.runAs(a.tenant(), appService::retryDueIncrements);

        assertThat(r.due()).as("A sees only its own due claim").isEqualTo(1);
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(eq(a.shop()), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(eq(b.shop()), any(), any(), any(), anyInt(), any(), any());
        assertThat(row(a, sa, va).get("status")).as("same-tenant positive control").isEqualTo("applied");
        assertThat(row(b, sb, vb)).containsEntry("status", "failed").containsEntry("attempt_count", 1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        return new T(tenant, store, shop, location, traced);
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

    /** The next adjust call fails with this class (and sent baseline); later calls succeed. */
    private void failNextAdjustWith(FailureClass cls, Integer baseline) {
        doThrow(new ShopifyAdjustFailedException(cls, baseline, "simulated " + cls.db(), null))
            .doNothing()
            .when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
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

    private ShopifyInventoryService.RetryResult retry(T t) {
        return TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
    }

    private Map<String, Object> row(T t, UUID session, V v) {
        return jdbc.queryForMap("SELECT status, failure_class, change_from_quantity, attempt_count, next_attempt_at, " +
            "sent_idempotency_key FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_id = ? AND variant_id = ?",
            t.tenant(), session.toString(), v.id());
    }

    private List<String> adjustKeys() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway, atLeast(0)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), keys.capture());
        return keys.getAllValues();
    }

    private List<Map<String, Object>> alerts(T t) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "inventory_increment_sync_failed".equals(e.get("type"))).toList();
    }

    private Map<String, Object> alert(T t, String kind) {
        return alerts(t).stream().filter(e -> kind.equals(e.get("kind"))).findFirst().orElse(null);
    }
}
