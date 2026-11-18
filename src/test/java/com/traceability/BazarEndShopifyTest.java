package com.traceability;

import com.traceability.bazar.BazarDayService;
import com.traceability.bazar.BazarEndService;
import com.traceability.bazar.BazarSaleService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Bazar POS B5 — Shopify at the end of a bazar, through the real paths, the gateway mocked, on a tenant whose Main
 * Warehouse is linked and counted (seeded): the send's −N went out (one pushTransferOut); after the event ends a
 * leftover scanned back good gets EXACTLY ONE +1 (the existing onTransferReturn path — retried scan included); the
 * sold pieces, the piece taken back damaged and brought home, and the piece marked lost get NO write; closing the
 * transfer writes nothing; no other Shopify call at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarEndShopifyTest {

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

    @Autowired JdbcTemplate     jdbc;
    @Autowired BazarService     bazar;
    @Autowired BazarSaleService sales;
    @Autowired BazarDayService  days;
    @Autowired BazarEndService  end;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;

    final AtomicInteger seq = new AtomicInteger();
    UUID tenant, owner, main, variant;
    String traced;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        owner = UUID.randomUUID();
        main = UUID.randomUUID();
        variant = UUID.randomUUID();
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        String shop = "bazar-end-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar End Shopify', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            owner, tenant, "owner-" + tenant + "@bazar-end-shopify.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
            "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", main, tenant, traced);
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

    /** A piece received at main the real way: its receipt's +1 applied (so its departure counts). */
    private String piece() {
        String id = String.format("01BAZARENDSHOP%012d", seq.incrementAndGet());
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, tenant, owner, main);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, 'applied')",
            tenant, UUID.randomUUID(), variant, main, receipt.toString());
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, receipt_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, ?)", id, tenant, variant, "BE-" + id,
            "B" + (2000000 + seq.get()), main, receipt);
        return id;
    }

    private String code(String id) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
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
    void soldPiecesGetNoWrite_aGoodLeftoverGetsExactlyOnePlusOne_damagedAndLostNone() throws Exception {
        BazarSaleService.Actor actor = new BazarSaleService.Actor(owner, "owner");
        String sold1 = piece(), sold2 = piece(), damaged = piece(), good = piece(), lost = piece();
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("Shopify End Bazar",
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), null, "Shopify End Hall",
            List.of("cash"), List.of("owner"), null, null, null), owner);
        UUID eventId = (UUID) ev.get("id");
        UUID transferId = (UUID) ev.get("transfer_id");
        for (String p : List.of(sold1, sold2, damaged, good, lost)) {
            assertThat(bazar.scanOut(eventId, "BE-" + p, owner, false).success()).isTrue();
        }
        bazar.markSent(eventId, owner);
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, transferId);
        bazar.start(eventId, owner);

        sales.sell(eventId, code(sold1), "s1", null, actor);
        sales.sell(eventId, code(sold2), "s2", null, actor);
        sales.sell(eventId, code(damaged), "s3", null, actor);
        sales.returnSale(eventId, code(damaged), null, "damaged", "r3", actor);
        LocalDate today = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", LocalDate.class);
        days.close(eventId, today, new BazarDayService.Entered(new BigDecimal("1000"), null, null, null, BigDecimal.ZERO),
            null, "close-1", actor);

        end.end(eventId, actor);
        end.scanBack(eventId, code(good), "back-good", actor);
        end.scanBack(eventId, code(good), "back-good", actor);                  // the phone's retry: replayed
        end.scanBack(eventId, code(damaged), "back-damaged", actor);
        end.markLost(eventId, List.of(lost), actor);
        end.closeTransfer(eventId, actor);

        String applied = "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'transfer_return' AND status = 'applied'";
        await(applied, 1, tenant);
        assertThat(count(applied, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT split_part(trigger_id, ':', 1) FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'transfer_return'", String.class, tenant)).containsExactly(good);
        verify(shopify, times(1)).adjustInventoryQuantities(any(), any(), any(), eq(traced), eq(1), anyString(), any());
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());   // only the send
        // Nothing else: no claim of any other kind for the bazar's pieces (sold, damaged, lost).
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type NOT IN ('initial_seed', 'receiving_session', 'transfer_return')", tenant)).isZero();
        verify(shopify, never()).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopify, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
    }
}
