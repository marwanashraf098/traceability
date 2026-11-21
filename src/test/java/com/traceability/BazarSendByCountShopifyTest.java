package com.traceability;

import com.traceability.bazar.BazarService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bazar POS B7d-1 — Shopify and the count send, on a tenant whose Main Warehouse is linked and counted, the gateway
 * mocked. Before Mark sent nothing reaches Shopify — not the count send, not a removal (no claim row of any kind, no
 * call). At Mark sent the existing claimSend's per-piece arrival rule decides, label-agnostic: the −N holds the
 * labelled AND the untagged pieces sent by count whose receiving +1 reached Shopify, leaves out (payload.excluded)
 * an untagged piece whose arrival never did, and never holds a piece removed from the send.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSendByCountShopifyTest {

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
    @Autowired BazarService bazar;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;

    final AtomicInteger seq = new AtomicInteger();
    UUID tenant, owner, main, variant;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        owner = UUID.randomUUID();
        main = UUID.randomUUID();
        variant = UUID.randomUUID();
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        String shop = "bazar-count-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar Count Shopify', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            owner, tenant, "owner-" + tenant + "@bazar-count-shopify.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
            "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", main, tenant,
            "gid://shopify/Location/" + shop);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Dress', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
            "VALUES (?, ?, ?, ?, 'Red', 'SKU-RED', ?, 500.00)", variant, tenant, product, "gid://shopify/ProductVariant/" + variant,
            "gid://shopify/InventoryItem/" + variant);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, created_at) VALUES (?, ?, ?, ?, 4, 'initial_seed', 'seed', '{}'::jsonb, " +
            "'applied', now() - interval '1 hour')", tenant, UUID.randomUUID(), variant, main);
    }

    @BeforeEach
    void reset() {
        Mockito.reset(shopify, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        TenantContext.set(tenant);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    /** A piece received at main after the seed; {@code counted}: its receipt's +1 applied. {@code tagged=false} → no code. */
    private String piece(boolean tagged, boolean counted, int minutesAgo) {
        String id = String.format("01BAZARCOUNTSH%012d", seq.incrementAndGet());
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, tenant, owner, main);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, ?)",
            tenant, UUID.randomUUID(), variant, main, receipt.toString(), counted ? "applied" : "failed");
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, receipt_id, " +
            "created_at) VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, ?, now() - (? * interval '1 minute'))",
            id, tenant, variant, "BC-" + id, tagged ? "C" + (3000000 + seq.get()) : null, main, receipt, minutesAgo);
        return id;
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private void await(String sql, int n, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(sql, args) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + ": " + sql);
            Thread.sleep(25);
        }
        Thread.sleep(300);
    }

    @Test
    void markSent_takesTheCountedPiecesOutOfShopify_labelAgnostic_neverARemovedPiece() throws Exception {
        String tagged = piece(true, true, 40);
        String untaggedCounted = piece(false, true, 30);
        String untaggedNever = piece(false, false, 20);
        String removed = piece(true, true, 10);
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Count Shopify Bazar",
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), null, "Count Shopify Hall",
            List.of("cash"), List.of("owner"), null, null, null), owner);
        UUID eventId = (UUID) ev.get("id");
        UUID transferId = (UUID) ev.get("transfer_id");

        assertThat(bazar.sendByCount(eventId, variant, 3, "count-1", owner).pieceIds())
            .containsExactly(tagged, untaggedCounted, untaggedNever);
        assertThat(bazar.scanOut(eventId, "BC-" + removed, owner, false).success()).isTrue();
        assertThat(bazar.removeFromSend(eventId, "BC-" + removed, null, null, "remove-1", owner).pieceIds())
            .containsExactly(removed);
        assertThat(bazar.removeFromSend(eventId, null, variant, 1, "remove-2", owner).pieceIds())
            .as("newest added first: the last counted piece").containsExactly(untaggedNever);
        assertThat(bazar.sendByCount(eventId, variant, 1, "count-2", owner).pieceIds())
            .as("the oldest free piece again").containsExactly(untaggedNever);

        // Nothing reached Shopify before Mark sent: no claim of any kind, no call.
        assertThat(count("SELECT count(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", tenant)).isZero();
        assertThat(count("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type NOT IN ('initial_seed', 'receiving_session')", tenant)).isZero();
        verifyNoInteractions(shopify);

        bazar.markSent(eventId, owner);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, transferId);
        Map<String, Object> claim = jdbc.queryForMap("SELECT deltas::text AS deltas, piece_ids::text AS pieces, " +
            "payload::text AS payload FROM transfer_shopify_syncs WHERE transfer_id = ?", transferId);
        assertThat((String) claim.get("pieces")).contains(tagged, untaggedCounted)
            .doesNotContain(untaggedNever).doesNotContain(removed);
        assertThat((String) claim.get("deltas")).contains("\"" + variant + "\": 2");
        assertThat((String) claim.get("payload")).contains(untaggedNever).doesNotContain(removed);
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        assertThat(count("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type NOT IN ('initial_seed', 'receiving_session')", tenant)).isZero();
    }
}
