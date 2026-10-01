package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Every Shopify inventory trigger fires its job only AFTER the caller's transaction commits
 * (ShopifyInventoryService.afterCommit). Receiving is covered by SeedSupersedesClaimsTest a1/a2;
 * this covers the other five:
 *
 *   restock (return_inspection, +1)   unhold (hold_exit, +1)
 *   adjustPiece → damaged (damage_move)   voidPiece (void_correction, −1)   hold (hold_enter, −1)
 *
 * For each: (1) with the commit deliberately delayed, the Shopify call sees the piece's NEW status
 * as committed (read on a separate connection); (2) a rolled-back caller creates no claim row and
 * makes no Shopify call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AfterCommitTriggersTest {

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
    static final long LATE_COMMIT_MS = 1500;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired ReturnService returns;
    @Autowired PieceAdjustService adjust;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID user, UUID variant, String item) {}

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── restock → return_inspection (+1) ──────────────────────────────────────────

    @Test
    void restock_committed() throws Exception {
        T t = tenant("rc");
        String piece = piece(t, "return_pending_inspection", null);
        AtomicReference<String> seen = onShopify(t, piece, Call.ADJUST);

        callLate(t, () -> returns.restock(piece, t.location(), t.user()));

        assertThat(awaitSeen(seen)).isEqualTo("available");
        awaitClaim(t, "return_inspection", "applied");
    }

    @Test
    void restock_rolledBack() throws Exception {
        T t = tenant("rr");
        String piece = piece(t, "return_pending_inspection", null);
        callRolledBack(t, () -> returns.restock(piece, t.location(), t.user()));
        assertNothing(t);
    }

    // ── unhold → hold_exit (+1) ───────────────────────────────────────────────────

    @Test
    void unhold_committed() throws Exception {
        T t = tenant("uc");
        String piece = heldPiece(t);
        AtomicReference<String> seen = onShopify(t, piece, Call.ADJUST);

        callLate(t, () -> adjust.unhold(piece, t.user()));

        assertThat(awaitSeen(seen)).isEqualTo("available");
        awaitClaim(t, "hold_exit", "applied");
    }

    @Test
    void unhold_rolledBack() throws Exception {
        T t = tenant("ur");
        String piece = heldPiece(t);
        callRolledBack(t, () -> adjust.unhold(piece, t.user()));
        assertNothing(t, "hold_exit");
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── adjustPiece available → damaged (damage_move) ─────────────────────────────

    @Test
    void damage_committed() throws Exception {
        T t = tenant("dc");
        String piece = piece(t, "available", null);
        AtomicReference<String> seen = onShopify(t, piece, Call.DAMAGE);

        callLate(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));

        assertThat(awaitSeen(seen)).isEqualTo("damaged");
        awaitClaim(t, "damage_move", "applied");
    }

    @Test
    void damage_rolledBack() throws Exception {
        T t = tenant("dr");
        String piece = piece(t, "available", null);
        callRolledBack(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));
        assertNothing(t);
    }

    // ── voidPiece (void_correction, −1) ───────────────────────────────────────────

    @Test
    void void_committed() throws Exception {
        T t = tenant("vc");
        String piece = piece(t, "available", appliedReceipt(t));
        AtomicReference<String> seen = onShopify(t, piece, Call.VOID);

        callLate(t, () -> adjust.voidPiece(piece, "receiving_overcount", null, t.user()));

        assertThat(awaitSeen(seen)).isEqualTo("voided");
        awaitClaim(t, "void_correction", "applied");
    }

    @Test
    void void_rolledBack() throws Exception {
        T t = tenant("vr");
        String piece = piece(t, "available", appliedReceipt(t));
        callRolledBack(t, () -> adjust.voidPiece(piece, "receiving_overcount", null, t.user()));
        assertNothing(t, "void_correction");
    }

    // ── hold (hold_enter, −1) ─────────────────────────────────────────────────────

    @Test
    void hold_committed() throws Exception {
        T t = tenant("hc");
        String piece = piece(t, "available", null);
        AtomicReference<String> seen = onShopify(t, piece, Call.HOLD);

        callLate(t, () -> adjust.hold(piece, "quality_check", null, t.user()));

        assertThat(awaitSeen(seen)).isEqualTo("on_hold");
        awaitClaim(t, "hold_enter", "applied");
    }

    @Test
    void hold_rolledBack() throws Exception {
        T t = tenant("hr");
        String piece = piece(t, "available", null);
        callRolledBack(t, () -> adjust.hold(piece, "quality_check", null, t.user()));
        assertNothing(t);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    enum Call { ADJUST, DAMAGE, VOID, HOLD }

    /** When the given Shopify call arrives, record the piece's COMMITTED status as seen then. */
    private AtomicReference<String> onShopify(T t, String piece, Call call) {
        AtomicReference<String> seen = new AtomicReference<>();
        org.mockito.stubbing.Answer<Void> record = inv -> { seen.set(committedStatus(piece)); return null; };
        switch (call) {
            case ADJUST -> doAnswer(record).when(shopifyGateway)
                .adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
            case DAMAGE -> doAnswer(record).when(shopifyGateway)
                .moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any());
            case VOID -> doAnswer(record).when(shopifyGateway)
                .pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
            case HOLD -> doAnswer(record).when(shopifyGateway)
                .pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        }
        return seen;
    }

    /** Runs the caller inside an outer transaction whose commit comes LATE_COMMIT_MS after it. */
    private void callLate(T t, Runnable caller) {
        TenantContext.runAs(t.tenant(), () -> new TransactionTemplate(txm).executeWithoutResult(st -> {
            caller.run();
            sleep(LATE_COMMIT_MS);
        }));
    }

    private void callRolledBack(T t, Runnable caller) {
        TenantContext.runAs(t.tenant(), () -> new TransactionTemplate(txm).executeWithoutResult(st -> {
            caller.run();
            st.setRollbackOnly();
        }));
        sleep(LATE_COMMIT_MS);
    }

    private static String awaitSeen(AtomicReference<String> seen) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (seen.get() == null) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Shopify was never called");
            Thread.sleep(25);
        }
        return seen.get();
    }

    private void awaitClaim(T t, String triggerType, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = ? AND status = ?", Integer.class, t.tenant(), triggerType, status) == 0) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError(triggerType + " claim never reached " + status);
            Thread.sleep(25);
        }
    }

    /** No claim of any trigger (or none of triggerType, when the fixture already holds others) and no Shopify write. */
    private void assertNothing(T t, String... triggerType) {
        String sql = "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?" +
            (triggerType.length > 0 ? " AND trigger_type = '" + triggerType[0] + "'" : "");
        assertThat(jdbc.queryForObject(sql, Integer.class, t.tenant())).as("a rolled-back caller creates no claim").isZero();
        if (triggerType.length == 0) {
            verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        }
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
        if (!(triggerType.length > 0 && "hold_exit".equals(triggerType[0]))) {
            verify(shopifyGateway, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        }
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        String item = "gid://shopify/InventoryItem/" + name + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shop, "SKU-" + name, item);
        return new T(tenant, store, shop, location, traced, user, variant, item);
    }

    private String piece(T t, String status, UUID receiptId) {
        int k = seq.incrementAndGet();
        String id = String.format("01AFTERCOMMIT%013d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, receipt_id) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, ?)", id, t.tenant(), t.variant(), status,
            "AC-" + k, String.format("A%07d", k), t.location(), receiptId);
        return id;
    }

    /** A receipt whose receiving increment applied — so a void of one of its pieces decrements. */
    private UUID appliedReceipt(T t) {
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, t.tenant(), t.user(), t.location());
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, 'applied')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), receipt.toString());
        return receipt;
    }

    /** An on_hold piece through the real hold path (committed), its hold_enter job finished. */
    private String heldPiece(T t) throws Exception {
        String piece = piece(t, "available", null);
        TenantContext.runAs(t.tenant(), () -> adjust.hold(piece, "quality_check", null, t.user()));
        awaitClaim(t, "hold_enter", "applied");
        clearInvocations(shopifyGateway);
        return piece;
    }

    /** The piece's status as a separate connection sees it — i.e. committed. */
    private String committedStatus(String piece) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             PreparedStatement ps = c.prepareStatement("SELECT status::text FROM pieces WHERE id = ?")) {
            ps.setString(1, piece);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
