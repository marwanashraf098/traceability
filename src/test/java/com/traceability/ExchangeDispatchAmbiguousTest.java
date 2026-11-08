package com.traceability;

import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Part D fix P1 (2026-11-08) — exchange_dispatch splits its failures: no confirmed answer from Shopify →
 * 'failed_ambiguous' (the −1 may have landed), which is never sent again: the manual re-push refuses it and a later
 * dispatch signal for the same piece can't re-claim it. A definite failure stays 'failed' and re-sendable
 * (ExchangeDispatchDecrementTest.f1).
 *
 *   a1 — ShopifyAmbiguousException → 'failed_ambiguous', the CRITICAL void_hold_sync_failed alert, re-push 409,
 *        a second dispatch signal sends nothing — one call in total
 *   a2 — an unexpected RuntimeException once the call may have started → 'failed_ambiguous' too
 *   a3 — positive control: a definite ShopifyException → 'failed', and the re-push sends it
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeDispatchAmbiguousTest {

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

    static final String SHOP_DOMAIN = "exchange-ambiguous.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/887";
    static final String ITEM_GID    = "gid://shopify/InventoryItem/8801";

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate            jdbc;
    @Autowired ShopifyInventoryService shopifyInventory;
    @Autowired ExceptionService        exceptions;

    UUID tenantId, storeId, variantId, mainLoc;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Exchange Ambiguous Tenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            storeId, tenantId, SHOP_DOMAIN);
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/88', 'Linen Shirt', 'active')", productId, tenantId, storeId);
        variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/8811', 'White / M', 'LIN-W-M')", variantId, tenantId, productId);
        mainLoc = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", mainLoc, tenantId, TRACED_GID);
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
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE exchanges SET outbound_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    @Test
    void a1_ambiguous_failedAmbiguous_alert_repushRefused_neverSentAgain() throws Exception {
        doThrow(new ShopifyAmbiguousException("Exchange dispatch: no confirmed response from " + SHOP_DOMAIN))
            .when(shopifyGateway).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        String piece = replacementPiece();

        dispatch(piece);
        assertThat(status(piece)).isEqualTo("failed_ambiguous");

        TenantContext.set(tenantId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) exceptions
            .listExceptions("void_hold_sync_failed", null, 0, 50).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("trigger_type", "exchange_dispatch").containsEntry("severity", "CRITICAL");
        assertThatThrownBy(() -> shopifyInventory.repushFailedVoidOrHold("exchange_dispatch", piece))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("never re-sent");
        TenantContext.clear();

        // A later dispatch signal for the same piece (pickup close after the webhook, a duplicate webhook) can't
        // re-claim it either.
        doNothing().when(shopifyGateway).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        dispatch(piece);

        verify(shopifyGateway, times(1)).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        assertThat(status(piece)).isEqualTo("failed_ambiguous");
    }

    @Test
    void a2_unexpectedRuntimeException_failedAmbiguous() throws Exception {
        doThrow(new IllegalStateException("socket closed mid-response"))
            .when(shopifyGateway).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        String piece = replacementPiece();
        dispatch(piece);
        assertThat(status(piece)).isEqualTo("failed_ambiguous");
    }

    @Test
    void a3_definiteFailure_staysFailed_andIsReSendable() throws Exception {
        doThrow(new ShopifyException("Exchange dispatch failed: inventory item not stocked"))
            .doNothing()
            .when(shopifyGateway).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
        String piece = replacementPiece();
        dispatch(piece);
        assertThat(status(piece)).isEqualTo("failed");

        TenantContext.set(tenantId);
        shopifyInventory.repushFailedVoidOrHold("exchange_dispatch", piece);
        TenantContext.clear();
        assertThat(status(piece)).isEqualTo("applied");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** A packed replacement piece of an internal exchange order named by an exchanges row, at the main warehouse. */
    private String replacementPiece() {
        String tracking = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
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
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, outbound_variant_id, raw) " +
            "VALUES (?, ?, 'mapped', ?, ?, '{}'::jsonb)", tenantId, tracking, orderId, variantId);
        return piece;
    }

    private void dispatch(String piece) throws Exception {
        shopifyInventory.onExchangeReplacementDispatched(tenantId, piece).get(5, TimeUnit.SECONDS);
    }

    private String status(String piece) {
        return jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND trigger_id = ?", String.class, piece);
    }
}
