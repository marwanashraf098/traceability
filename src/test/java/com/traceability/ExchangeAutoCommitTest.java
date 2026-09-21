package com.traceability;

import com.traceability.inventory.ExchangeService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Build task ("outbound exchange variant: exact-match auto-commit + ranked recs"),
 * Part B — {@link ExchangeService#tryAutoMap} (auto-commit path) and
 * {@link ExchangeService#overrideOutboundVariant} (pre-pack correction).
 *
 *   b1 — EXACT-resolving exchange auto-commits: order_items gets a real variant FK
 *        (the resolver's pick, never a placeholder), auto_matched=true, status='mapped'.
 *   b2 — RECS-resolving exchange does NOT auto-commit: stays 'needs_mapping',
 *        auto_matched stays false, no order created — human map() still required.
 *   b3 — idempotent: a second tryAutoMap() call on an already-mapped exchange is a
 *        no-op (claim guard), exactly one order.
 *   b4 — the auto-committed order's order_items row is a REAL FK that FulfillService.
 *        scan()'s standard WRONG_VARIANT check enforces exactly as it would for any
 *        other order — wrong-variant piece rejected, correct-variant piece accepted.
 *   b5 — overrideOutboundVariant() corrects an auto-match before pack: order_items +
 *        exchanges.outbound_variant_id both move, auto_matched flips back to false.
 *   b6 — override 409s once a piece has already been scanned for the order.
 *   b7 — override 400s for a variant belonging to a different store.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeAutoCommitTest {

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

    @Autowired ExchangeService  excSvc;
    @Autowired FulfillService   fulfillSvc;
    @Autowired JdbcTemplate     jdbc;
    @MockBean  JobScheduler     jobScheduler;

    UUID tenantId, storeId, locationId, actorId;
    UUID xsSVariant, mlVariant;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        UUID bandanasId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EAC-Tenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Actor', 'eac@test.com', 'x', 'owner'::user_role)", actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'EacLoc')", locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'eac.myshopify.com', 'connected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-EAC-BANDANAS', 'The Bandanas')", bandanasId, tenantId, storeId);
        xsSVariant = UUID.randomUUID();
        mlVariant  = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-EAC-XSS', 'XS/S / Pink & White', 'BAND-XSS-PW')",
                    xsSVariant, tenantId, bandanasId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-EAC-ML', 'M/L / Pink & White', 'BAND-ML-PW')",
                    mlVariant, tenantId, bandanasId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces       WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM exchanges    WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments    WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders       WHERE tenant_id = ?", tenantId);
    }

    // ── b1: EXACT auto-commits — real FK, auto_matched=true ─────────────────────────

    @Test
    void b1_exactResolution_autoCommits_realVariantFk_autoMatchedTrue() {
        seedExchange("920000001", "XS/S pink & white bandana", "some old red hat");

        excSvc.tryAutoMap("920000001");

        Map<String, Object> exchange = jdbc.queryForMap(
            "SELECT status, auto_matched, outbound_order_id, outbound_variant_id, inbound_variant_id " +
            "FROM exchanges WHERE tenant_id = ? AND tracking_number = '920000001'", tenantId);
        assertThat(exchange.get("status")).isEqualTo("mapped");
        assertThat(exchange.get("auto_matched")).isEqualTo(true);
        assertThat(exchange.get("outbound_variant_id")).isEqualTo(xsSVariant);
        assertThat(exchange.get("inbound_variant_id"))
            .as("Part A is outbound-only — auto-commit never touches the inbound leg")
            .isNull();

        UUID orderId = (UUID) exchange.get("outbound_order_id");
        Map<String, Object> item = jdbc.queryForMap(
            "SELECT variant_id, quantity FROM order_items WHERE order_id = ?", orderId);
        assertThat(item.get("variant_id")).isEqualTo(xsSVariant);
        assertThat(((Number) item.get("quantity")).intValue()).isEqualTo(1);
    }

    // ── b2: RECS never auto-commits — stays needs_mapping ────────────────────────────

    @Test
    void b2_recsResolution_doesNotAutoCommit_staysNeedsMapping_noOrderCreated() {
        seedExchange("920000002", "pink & white bandana", "some old red hat");

        excSvc.tryAutoMap("920000002");

        Map<String, Object> exchange = jdbc.queryForMap(
            "SELECT status, auto_matched, outbound_order_id FROM exchanges " +
            "WHERE tenant_id = ? AND tracking_number = '920000002'", tenantId);
        assertThat(exchange.get("status")).isEqualTo("needs_mapping");
        assertThat(exchange.get("auto_matched")).isEqualTo(false);
        assertThat(exchange.get("outbound_order_id")).isNull();

        Integer orderCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE external_id = ?",
            Integer.class, "internal:exchange:920000002");
        assertThat(orderCount).isZero();
    }

    // ── b3: idempotent — second call on an already-mapped exchange is a no-op ──────

    @Test
    void b3_secondTryAutoMapCall_onAlreadyMappedExchange_isNoOp_exactlyOneOrder() {
        seedExchange("920000003", "XS/S pink & white bandana", "some old red hat");

        excSvc.tryAutoMap("920000003");
        excSvc.tryAutoMap("920000003");

        Integer orderCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE external_id = ?",
            Integer.class, "internal:exchange:920000003");
        assertThat(orderCount).isEqualTo(1);
    }

    // ── b4: auto-committed order still passes the STANDARD WRONG_VARIANT scan check ─

    @Test
    void b4_autoCommittedOrder_scanRejectsWrongVariant_acceptsCorrectVariant() {
        seedExchange("920000004", "XS/S pink & white bandana", "some old red hat");
        excSvc.tryAutoMap("920000004");

        UUID orderId = jdbc.queryForObject(
            "SELECT outbound_order_id FROM exchanges WHERE tenant_id = ? AND tracking_number = '920000004'",
            UUID.class, tenantId);

        String wrongVariantPiece = receivePiece(mlVariant);
        FulfillService.ScanResult wrongResult = fulfillSvc.scan(orderId, wrongVariantPiece, actorId);
        assertThat(wrongResult.success()).isFalse();
        assertThat(wrongResult.code()).isEqualTo("WRONG_VARIANT");

        String correctVariantPiece = receivePiece(xsSVariant);
        FulfillService.ScanResult correctResult = fulfillSvc.scan(orderId, correctVariantPiece, actorId);
        assertThat(correctResult.success())
            .as("the resolver-committed variant is a real order_items FK — a matching " +
                "piece must scan exactly as it would for any human-mapped order")
            .isTrue();
    }

    // ── b5: override corrects an auto-match before pack ─────────────────────────────

    @Test
    void b5_overrideOutboundVariant_correctsAutoMatch_beforePack() {
        seedExchange("920000005", "XS/S pink & white bandana", "some old red hat");
        excSvc.tryAutoMap("920000005");
        UUID exchangeId = jdbc.queryForObject(
            "SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = '920000005'", UUID.class, tenantId);
        UUID orderId = jdbc.queryForObject(
            "SELECT outbound_order_id FROM exchanges WHERE id = ?", UUID.class, exchangeId);

        excSvc.overrideOutboundVariant(exchangeId, mlVariant);

        Map<String, Object> item = jdbc.queryForMap(
            "SELECT variant_id FROM order_items WHERE order_id = ?", orderId);
        assertThat(item.get("variant_id")).isEqualTo(mlVariant);

        Map<String, Object> exchange = jdbc.queryForMap(
            "SELECT outbound_variant_id, auto_matched FROM exchanges WHERE id = ?", exchangeId);
        assertThat(exchange.get("outbound_variant_id")).isEqualTo(mlVariant);
        assertThat(exchange.get("auto_matched"))
            .as("an overridden pick is a human decision now — the review marker must clear")
            .isEqualTo(false);
    }

    // ── b6: override 409s once a piece has already been scanned ────────────────────

    @Test
    void b6_overrideOutboundVariant_afterPieceScanned_conflicts() {
        seedExchange("920000006", "XS/S pink & white bandana", "some old red hat");
        excSvc.tryAutoMap("920000006");
        UUID exchangeId = jdbc.queryForObject(
            "SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = '920000006'", UUID.class, tenantId);
        UUID orderId = jdbc.queryForObject(
            "SELECT outbound_order_id FROM exchanges WHERE id = ?", UUID.class, exchangeId);

        String piece = receivePiece(xsSVariant);
        FulfillService.ScanResult scanResult = fulfillSvc.scan(orderId, piece, actorId);
        assertThat(scanResult.success()).isTrue();

        ResponseStatusException ex = catchThrowableOfType(
            () -> excSvc.overrideOutboundVariant(exchangeId, mlVariant), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> item = jdbc.queryForMap(
            "SELECT variant_id FROM order_items WHERE order_id = ?", orderId);
        assertThat(item.get("variant_id"))
            .as("a conflicting override must never overwrite the already-scanned line")
            .isEqualTo(xsSVariant);
    }

    // ── b7: override 400s for a variant belonging to a different store ─────────────

    @Test
    void b7_overrideOutboundVariant_differentStoreVariant_badRequest() {
        seedExchange("920000007", "XS/S pink & white bandana", "some old red hat");
        excSvc.tryAutoMap("920000007");
        UUID exchangeId = jdbc.queryForObject(
            "SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = '920000007'", UUID.class, tenantId);

        UUID otherStoreId = UUID.randomUUID();
        UUID otherProductId = UUID.randomUUID();
        UUID otherVariantId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'eac-other.myshopify.com', 'connected')", otherStoreId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-EAC-OTHER', 'Other Product')", otherProductId, tenantId, otherStoreId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-EAC-OTHER', 'Other Variant', 'OTHER-01')",
                    otherVariantId, tenantId, otherProductId);

        assertThatThrownBy(() -> excSvc.overrideOutboundVariant(exchangeId, otherVariantId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");

        jdbc.update("DELETE FROM variants WHERE id = ?", otherVariantId);
        jdbc.update("DELETE FROM products WHERE id = ?", otherProductId);
        jdbc.update("DELETE FROM stores WHERE id = ?", otherStoreId);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void seedExchange(String tracking, String outboundDesc, String inboundDesc) {
        String raw = String.format("""
            {"type":{"code":30,"value":"Exchange"},
             "specs":{"packageDetails":{"description":"%s","itemsCount":1}},
             "returnSpecs":{"packageDetails":{"description":"%s","itemsCount":1}},
             "cod":"0","goodsInfo":{"amount":"600"},
             "receiver":{"fullName":"Maya Mostafa","phone":"+201000301512"},
             "dropOffAddress":{"firstLine":"12 Palm St","city":{"name":"October"}}}
            """, outboundDesc, inboundDesc);
        jdbc.update(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, " +
            "  inbound_description, cod, goods_value, raw) " +
            "VALUES (?, ?, 'needs_mapping', ?, ?, 0, 600, ?::jsonb)",
            tenantId, tracking, outboundDesc, inboundDesc, raw);
    }

    private String receivePiece(UUID variantId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at, last_user_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?, now(), ?)",
            id, tenantId, variantId, "EAC-" + id.substring(id.length() - 8), id, locationId, actorId);
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, ?, NULL, 'available'::piece_status)",
            tenantId, id, actorId, locationId);
        return id;
    }
}
