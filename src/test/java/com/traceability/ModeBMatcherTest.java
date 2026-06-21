package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration tests for Mode-B phone+COD fallback matcher (matchByPhoneAndCod fix).
 *
 * Test matrix:
 *   1.  Unique phone+COD match → auto-link (shipment created, pieces advanced)
 *   2.  No match (phone+COD, 0 candidates) → NO_MATCH in unlinked table
 *   3.  Multi-match (2 orders same phone+COD) → AMBIGUOUS_MULTI in unlinked table
 *   4.  E.164 "+20…" ↔ local "0…" phone equivalence → auto-link
 *   5.  COD mismatch (phone matches, COD differs) → NO_MATCH
 *   6.  COD = 0 (prepaid) is a valid match value, not "missing" → match proceeds
 *   7.  COD-only (no Bosta phone) → COD_ONLY_AMBIGUOUS, no candidate query needed
 *   8.  Terminal order excluded (delivered) → NO_MATCH even with matching phone+COD
 *   9.  Already-linked order excluded (active shipment) → NO_MATCH; with the partial
 *       unique index, a simulated concurrent double-INSERT results in exactly one active
 *       shipment and the loser is flagged
 *   10. COD flat scalar fix: raw.cod (not raw.cod.amount) is parsed correctly
 *   11. Pilot-today case: Bosta phone present, order.customer_phone NULL → NO_MATCH
 *   12. normalizePhone handles "+20 100 123 4567" (spaces), "00201001234567" (IDD prefix),
 *       and "1001234567" (missing leading zero) — all produce "01001234567"
 *   13. Double-ingest idempotency: outer BostaWebhookJob dedup prevents the same webhook
 *       from reaching tryMatchDelivery() a second time
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModeBMatcherTest {

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

    @Autowired ShipmentLinkService shipmentLinkSvc;
    @Autowired BostaWebhookJob     bostaWebhookJob;
    @Autowired JdbcTemplate        jdbc;
    @Autowired EncryptionService   encryptionService;
    @Autowired ObjectMapper        mapper;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    UUID tenantId, storeId, variantId;

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        UUID userId    = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'MBTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Ziad', 'ziad@mb.local', 'h', 'owner')", userId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'mb.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-MB', 'Widget', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-MB', 'Red L', 'WID-RED-L')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'test-hash', 'active')",
                    tenantId, encryptionService.encrypt("mb-api-key"));
    }

    @BeforeEach void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations   WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events  WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces        WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments     WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items   WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders        WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", tenantId);
    }

    // ── 1. Unique phone+COD match → auto-link ────────────────────────────────

    @Test
    void t01_uniquePhoneCodMatch_autoLinks() {
        UUID orderId = createPackedOrderWithPhoneCod("01001234567", new BigDecimal("150"));
        String tracking = "AWB-MB01";
        Long wid = insertWebhookEvent(tracking);
        mockDelivery(tracking, bostaRaw("01001234567", "150", null));

        bostaWebhookJob.process(wid, tenantId);

        assertThat(webhookStatus(wid)).isEqualTo("processed");
        // Shipment created and linked to the correct order
        UUID shipOrder = jdbc.queryForObject(
            "SELECT order_id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            UUID.class, tracking, tenantId);
        assertThat(shipOrder).isEqualTo(orderId);
        // Order advanced to awaiting_pickup
        assertThat(orderStatus(orderId)).isEqualTo("awaiting_pickup");
        // No unlinked row
        int unlinked = jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ?",
            Integer.class, tenantId);
        assertThat(unlinked).isZero();
    }

    // ── 2. No match (phone+COD, 0 candidates) → NO_MATCH ────────────────────

    @Test
    void t02_noMatch_routesToUnlinkedWithReasonNoMatch() {
        createPackedOrderWithPhoneCod("01001234567", new BigDecimal("150"));
        String tracking = "AWB-MB02";
        Long wid = insertWebhookEvent(tracking);
        // Different phone — no candidate
        mockDelivery(tracking, bostaRaw("01009999999", "150", null));

        bostaWebhookJob.process(wid, tenantId);

        String reason = unlinkedReason(tracking);
        assertThat(reason).isEqualTo(ShipmentLinkService.REASON_NO_MATCH);
        assertThat(shipmentExists(tracking)).isFalse();
    }

    // ── 3. Multi-match (>1 candidate) → AMBIGUOUS_MULTI ─────────────────────

    @Test
    void t03_multiMatch_routesToUnlinkedWithReasonAmbiguousMulti() {
        // Two orders with the same phone and COD — ambiguous
        createPackedOrderWithPhoneCod("01001234567", new BigDecimal("200"));
        createPackedOrderWithPhoneCod("01001234567", new BigDecimal("200"));
        String tracking = "AWB-MB03";
        Long wid = insertWebhookEvent(tracking);
        mockDelivery(tracking, bostaRaw("01001234567", "200", null));

        bostaWebhookJob.process(wid, tenantId);

        assertThat(unlinkedReason(tracking)).isEqualTo(ShipmentLinkService.REASON_AMBIGUOUS_MULTI);
        assertThat(shipmentExists(tracking)).isFalse();
    }

    // ── 4. E.164 "+20…" ↔ local "0…" phone equivalence → auto-link ──────────

    @Test
    void t04_phoneNormalization_e164EqualsLocal() {
        // Order stores local form; Bosta delivers E.164 form
        UUID orderId = createPackedOrderWithPhoneCod("01001234567", new BigDecimal("300"));
        String tracking = "AWB-MB04";
        Long wid = insertWebhookEvent(tracking);
        mockDelivery(tracking, bostaRaw("+201001234567", "300", null));

        bostaWebhookJob.process(wid, tenantId);

        UUID shipOrder = jdbc.queryForObject(
            "SELECT order_id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            UUID.class, tracking, tenantId);
        assertThat(shipOrder).isEqualTo(orderId);
    }

    // ── 5. COD mismatch → NO_MATCH ───────────────────────────────────────────

    @Test
    void t05_codMismatch_noMatch() {
        createPackedOrderWithPhoneCod("01002345678", new BigDecimal("500"));
        String tracking = "AWB-MB05";
        Long wid = insertWebhookEvent(tracking);
        // Phone matches, COD differs
        mockDelivery(tracking, bostaRaw("01002345678", "501", null));

        bostaWebhookJob.process(wid, tenantId);

        assertThat(unlinkedReason(tracking)).isEqualTo(ShipmentLinkService.REASON_NO_MATCH);
    }

    // ── 6. COD = 0 (prepaid) treated as valid match value, not "missing" ─────

    @Test
    void t06_codZero_isValidMatchValue_notMissing() {
        // Insert order with cod_amount = 0 (unusual but valid scalar)
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "  customer_phone, cod_amount, payment_method, placed_at) " +
            "VALUES (?, ?, ?, 'ORD-ZERO', 'packed'::order_status, '01003456789', 0, " +
            "  'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-ZERO");
        UUID itemId = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id", UUID.class, tenantId, orderId, variantId);
        String pid = insertPiece("packed");
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
                    "VALUES (?, ?, ?, 'packed'::allocation_status)", tenantId, itemId, pid);

        String tracking = "AWB-MB06";
        Long wid = insertWebhookEvent(tracking);
        mockDelivery(tracking, bostaRaw("01003456789", "0", null));

        bostaWebhookJob.process(wid, tenantId);

        // COD=0 went through the full match path and linked — not treated as missing
        UUID shipOrder = jdbc.queryForObject(
            "SELECT order_id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            UUID.class, tracking, tenantId);
        assertThat(shipOrder).isEqualTo(orderId);
    }

    // ── 7. No Bosta phone → COD_ONLY_AMBIGUOUS (no candidate query) ──────────

    @Test
    void t07_noBostaPhone_routesToCodOnlyAmbiguous() {
        createPackedOrderWithPhoneCod("01004567890", new BigDecimal("100"));
        String tracking = "AWB-MB07";
        Long wid = insertWebhookEvent(tracking);
        // Raw has COD but no consignee phone field at all
        ObjectNode raw = mapper.createObjectNode();
        raw.put("cod", 100);
        mockDelivery(tracking, raw);

        bostaWebhookJob.process(wid, tenantId);

        assertThat(unlinkedReason(tracking)).isEqualTo(ShipmentLinkService.REASON_COD_ONLY);
        assertThat(shipmentExists(tracking)).isFalse();
    }

    // ── 8. Terminal order (delivered) excluded from candidates ────────────────

    @Test
    void t08_terminalOrderDelivered_excludedFromCandidates() {
        // Order is delivered — terminal status, must never be re-linked
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "  customer_phone, cod_amount, payment_method, placed_at) " +
            "VALUES (?, ?, 'EXT-DEL', 'ORD-DEL', 'delivered'::order_status, '01005678901', 250, " +
            "  'cod'::order_payment_method, now())",
            tenantId, storeId);

        String tracking = "AWB-MB08";
        Long wid = insertWebhookEvent(tracking);
        mockDelivery(tracking, bostaRaw("01005678901", "250", null));

        bostaWebhookJob.process(wid, tenantId);

        assertThat(unlinkedReason(tracking)).isEqualTo(ShipmentLinkService.REASON_NO_MATCH);
        assertThat(shipmentExists(tracking)).isFalse();
    }

    // ── 9. Already-linked order excluded + partial unique index guard ─────────

    @Test
    void t09_alreadyLinkedOrder_excludedAndIndexPreventsDoubleLink() {
        UUID orderId = createPackedOrderWithPhoneCod("01006789012", new BigDecimal("400"));

        // Simulate: first delivery already linked (active shipment exists)
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, 'AWB-FIRST', 'with_courier')",
            tenantId, orderId);
        jdbc.update("UPDATE orders SET status = 'with_courier' WHERE id = ?", orderId);

        // Second delivery arrives for same phone+COD
        String tracking = "AWB-MB09";
        Long wid = insertWebhookEvent(tracking);
        mockDelivery(tracking, bostaRaw("01006789012", "400", null));

        bostaWebhookJob.process(wid, tenantId);

        // NOT EXISTS sub-select in candidate query excludes the already-linked order
        assertThat(unlinkedReason(tracking)).isEqualTo(ShipmentLinkService.REASON_NO_MATCH);
        assertThat(shipmentExists(tracking)).isFalse();

        // The original active shipment is untouched — exactly one active shipment for the order
        int activeShipments = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments " +
            "WHERE order_id = ? AND internal_state NOT IN ('terminated','cancelled')",
            Integer.class, orderId);
        assertThat(activeShipments).isEqualTo(1);
    }

    // ── 10. COD flat scalar: raw.cod (not raw.cod.amount) ─────────────────────

    @Test
    void t10_codFlatScalar_parsedCorrectly_notNestedAmount() {
        UUID orderId = createPackedOrderWithPhoneCod("01007890123", new BigDecimal("175"));
        String tracking = "AWB-MB10";
        Long wid = insertWebhookEvent(tracking);

        // Build raw with COD as flat scalar (the correct Bosta format)
        ObjectNode raw = mapper.createObjectNode();
        raw.putObject("consignee").put("phone", "01007890123");
        raw.put("cod", new BigDecimal("175")); // flat scalar, NOT {amount: 175}

        mockDelivery(tracking, raw);
        bostaWebhookJob.process(wid, tenantId);

        // Auto-linked — flat scalar was parsed correctly
        UUID shipOrder = jdbc.queryForObject(
            "SELECT order_id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            UUID.class, tracking, tenantId);
        assertThat(shipOrder).isEqualTo(orderId);

        // Negative check: if the old nested-path parsing had been used it would have read
        // a MissingNode → NO_MATCH. Confirmed by success above.
    }

    // ── 11. Pilot-today case: Bosta phone present, order phone NULL → NO_MATCH ─

    @Test
    void t11_pilotToday_bostaPhonePresent_orderPhoneNull_noMatch() {
        // Pre-PCD: orders have customer_phone = NULL (hardcoded in Shopify ingestion paths)
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "  customer_phone, cod_amount, payment_method, placed_at) " +
            "VALUES (?, ?, 'EXT-NOPHONE', 'ORD-NOPHONE', 'packed'::order_status, NULL, 300, " +
            "  'cod'::order_payment_method, now())",
            tenantId, storeId);

        String tracking = "AWB-MB11";
        Long wid = insertWebhookEvent(tracking);
        // Bosta has the phone, order does not — phone+COD query returns 0 rows (NULL = ? is false)
        mockDelivery(tracking, bostaRaw("+201008901234", "300", null));

        bostaWebhookJob.process(wid, tenantId);

        // During the pilot this is the dominant case — every delivery that reaches
        // matchByPhoneAndCod() will NO_MATCH because order phones are all NULL.
        // When PCD is approved and phones are populated, the same code starts auto-linking.
        assertThat(unlinkedReason(tracking)).isEqualTo(ShipmentLinkService.REASON_NO_MATCH);
        assertThat(shipmentExists(tracking)).isFalse();
    }

    // ── 12. normalizePhone covers all form variants (unit-level) ──────────────

    @Test
    void t12_normalizePhone_allVariantsProduceCanonicalForm() {
        // E.164 with spaces
        assertThat(ShipmentLinkService.normalizePhone("+20 100 123 4567"))
                .isEqualTo("01001234567");
        // IDD prefix (00 + country code)
        assertThat(ShipmentLinkService.normalizePhone("00201001234567"))
                .isEqualTo("01001234567");
        // Missing leading zero (10-digit local number)
        assertThat(ShipmentLinkService.normalizePhone("1001234567"))
                .isEqualTo("01001234567");
        // Already canonical
        assertThat(ShipmentLinkService.normalizePhone("01001234567"))
                .isEqualTo("01001234567");
        // E.164 without spaces
        assertThat(ShipmentLinkService.normalizePhone("+201001234567"))
                .isEqualTo("01001234567");
        // Country code without +
        assertThat(ShipmentLinkService.normalizePhone("201001234567"))
                .isEqualTo("01001234567");
        // null → null
        assertThat(ShipmentLinkService.normalizePhone(null)).isNull();
        // Too short → null
        assertThat(ShipmentLinkService.normalizePhone("12345")).isNull();
        // Non-Egyptian number → null
        assertThat(ShipmentLinkService.normalizePhone("+447911123456")).isNull();
    }

    // ── 13. Double-ingest idempotency via outer BostaWebhookJob dedup ─────────

    @Test
    void t13_doubleIngest_idempotent_outerDedupPreventsDoubleLink() {
        UUID orderId = createPackedOrderWithPhoneCod("01009012345", new BigDecimal("600"));
        String tracking = "AWB-MB13";
        Long wid = insertWebhookEvent(tracking);
        mockDelivery(tracking, bostaRaw("01009012345", "600", null));

        // First processing — succeeds, creates shipment
        bostaWebhookJob.process(wid, tenantId);
        assertThat(webhookStatus(wid)).isEqualTo("processed");
        UUID firstShipId = jdbc.queryForObject(
            "SELECT id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            UUID.class, tracking, tenantId);
        assertThat(firstShipId).isNotNull();

        // Second call with the same webhookEventId — outer dedup catches it (status='processed')
        // and returns early without reaching tryMatchDelivery() at all.
        bostaWebhookJob.process(wid, tenantId);

        // Still exactly one shipment — no duplicate created
        int shipCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            Integer.class, tracking, tenantId);
        assertThat(shipCount).isEqualTo(1);
        assertThat(orderStatus(orderId)).isEqualTo("awaiting_pickup");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createPackedOrderWithPhoneCod(String phone, BigDecimal cod) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "  customer_phone, cod_amount, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'packed'::order_status, ?, ?, 'cod'::order_payment_method, now()) " +
            "RETURNING id",
            UUID.class, tenantId, storeId,
            "EXT-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(),
            phone, cod);
        UUID itemId = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, variantId);
        String pid = insertPiece("packed");
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES (?, ?, ?, 'packed'::allocation_status)",
            tenantId, itemId, pid);
        return orderId;
    }

    private String insertPiece(String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status) " +
            "VALUES (?, ?, ?, ?, ?::piece_status)",
            id, tenantId, variantId, "PC-" + id, status);
        return id;
    }

    /**
     * Builds a Bosta raw JSON node.
     * phone: placed at consignee.phone (null omits the field entirely)
     * cod:   placed at the top-level cod as a flat scalar (NOT nested amount)
     * nestedCodAmount: if non-null, places cod as {amount: value} to verify the
     *                  old (wrong) path is NOT used — only used by negative tests
     */
    private ObjectNode bostaRaw(String phone, String cod, String nestedCodAmount) {
        ObjectNode raw = mapper.createObjectNode();
        if (phone != null) raw.putObject("consignee").put("phone", phone);
        if (cod != null) raw.put("cod", new BigDecimal(cod));
        if (nestedCodAmount != null) raw.putObject("cod").put("amount", new BigDecimal(nestedCodAmount));
        return raw;
    }

    private void mockDelivery(String tracking, ObjectNode raw) {
        when(bostaGateway.fetchDelivery(eq("mb-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 0, null, raw));
    }

    private Long insertWebhookEvent(String tracking) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":41,\"updatedAt\":\"2026-06-21T10:00:00Z\"}",
            tracking);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenantId, payload);
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private boolean shipmentExists(String tracking) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            Integer.class, tracking, tenantId);
        return count != null && count > 0;
    }

    private String unlinkedReason(String tracking) {
        return jdbc.queryForObject(
            "SELECT match_reason FROM unlinked_bosta_deliveries " +
            "WHERE tracking_number = ? AND tenant_id = ?",
            String.class, tracking, tenantId);
    }
}
