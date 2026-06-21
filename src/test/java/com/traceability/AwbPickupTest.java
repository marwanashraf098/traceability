package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.*;
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
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for AWB print (FR-9.5) and pickup handling (FR-10.1/10.2).
 *
 * Test matrix:
 *   AWB Print:
 *     1.  ≤50 shipments → inline PDF path → pdfBase64List has one entry
 *     2.  Email-path response → emailMessage set, pdfBase64List empty
 *     3.  Terminal state (delivered) → excluded as NON_PRINTABLE_STATE, exceptions list
 *         populated, awb_print_failed_reason written to shipments table
 *     4.  CRP delivery type → excluded as NON_PRINTABLE_TYPE:CRP
 *     5.  Bosta rejects a tracking number → whole batch → BOSTA_REJECTED in exceptions
 *     6.  Mixed batch: 1 printable + 1 non-printable → PDF returned + exception for excluded
 *
 *   Pickup:
 *     7.  BOSTA_MANAGED: createPickup never called; manifest returned with correct COD total
 *     8.  TRACED_MANAGED happy path: createPickup called once, providerPickupId in manifest
 *     9.  TRACED_MANAGED "already exists" (code 1078): alreadyExistsMessage set, no crash
 *     10. Past date → 400 (no Bosta call)
 *     11. Friday → 400 (no Bosta call, Bosta error 1080)
 *     12. Manifest COD total = sum of awaiting_pickup shipment COD amounts
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwbPickupTest {

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

    @Autowired BostaAwbService    awbService;
    @Autowired BostaPickupService pickupService;
    @Autowired JdbcTemplate       jdbc;
    @Autowired EncryptionService  encryptionService;
    @Autowired ObjectMapper       mapper;
    @MockBean  BostaGateway       bostaGateway;
    @MockBean  JobScheduler       jobScheduler;

    UUID tenantId;
    UUID storeId;
    UUID variantId;

    static final byte[] FAKE_PDF = "FAKEPDF".getBytes();

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        UUID userId    = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AwbTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'owner@awb.local', 'h', 'owner')", userId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'awb.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-AWB', 'Widget', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-AWB', 'Blue M', 'WID-BLUE-M')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
                    " pickup_mode, awb_format, awb_lang, pickup_business_location_id) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'test-hash', 'active', " +
                    "        'BOSTA_MANAGED', 'A4', 'ar', 'loc-123')",
                    tenantId, encryptionService.encrypt("awb-api-key"));
    }

    @BeforeEach void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM pickup_shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pickups        WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments      WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items    WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders         WHERE tenant_id = ?", tenantId);
        // Reset pickup_mode to default after each test to prevent cross-test leakage
        jdbc.update("UPDATE courier_accounts SET pickup_mode = 'BOSTA_MANAGED' " +
                    "WHERE tenant_id = ? AND provider = 'bosta'", tenantId);
    }

    // ── 1. Inline PDF path ───────────────────────────────────────────────────

    @Test
    void t01_awbPrint_inlinePdf_lessOrEqual50() {
        UUID shipmentId = createShipmentWithOrder("AWB-T01", "created", null, new BigDecimal("150"));

        when(bostaGateway.printMassAwb(eq("awb-api-key"), anyList(), eq("A4"), eq("ar")))
            .thenReturn(new AwbPrintResult(FAKE_PDF, null));

        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(tenantId, List.of(shipmentId), null, null);

        assertThat(result.pdfBase64List()).hasSize(1);
        assertThat(Base64.getDecoder().decode(result.pdfBase64List().get(0))).isEqualTo(FAKE_PDF);
        assertThat(result.emailMessage()).isNull();
        assertThat(result.exceptions()).isEmpty();

        verify(bostaGateway).printMassAwb("awb-api-key", List.of("AWB-T01"), "A4", "ar");
    }

    // ── 2. Email-path response ───────────────────────────────────────────────

    @Test
    void t02_awbPrint_emailPath_surfacedAsMessage() {
        UUID shipmentId = createShipmentWithOrder("AWB-T02", "created", null, new BigDecimal("100"));

        when(bostaGateway.printMassAwb(any(), anyList(), any(), any()))
            .thenReturn(new AwbPrintResult(null, "AWB has been exported to your email"));

        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(tenantId, List.of(shipmentId), null, null);

        assertThat(result.pdfBase64List()).isEmpty();
        assertThat(result.emailMessage()).isEqualTo("AWB has been exported to your email");
        assertThat(result.exceptions()).isEmpty();
    }

    // ── 3. Terminal state → NON_PRINTABLE_STATE, awb_print_failed_reason set ─

    @Test
    void t03_awbPrint_terminalState_excluded() {
        UUID shipmentId = createShipmentWithOrder("AWB-T03", "delivered", null, new BigDecimal("200"));

        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(tenantId, List.of(shipmentId), null, null);

        assertThat(result.pdfBase64List()).isEmpty();
        assertThat(result.exceptions()).hasSize(1);
        assertThat(result.exceptions().get(0).reason()).startsWith("NON_PRINTABLE_STATE:delivered");
        assertThat(result.exceptions().get(0).trackingNumber()).isEqualTo("AWB-T03");

        // awb_print_failed_reason must be written to the shipments row for exceptions center
        String failedReason = jdbc.queryForObject(
            "SELECT awb_print_failed_reason FROM shipments WHERE id = ?",
            String.class, shipmentId);
        assertThat(failedReason).isEqualTo("NON_PRINTABLE_STATE:delivered");

        verifyNoInteractions(bostaGateway);
    }

    // ── 4. CRP delivery type → NON_PRINTABLE_TYPE ────────────────────────────

    @Test
    void t04_awbPrint_crpType_excluded() {
        UUID shipmentId = createShipmentWithOrder("AWB-T04", "with_courier", "CRP", new BigDecimal("0"));

        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(tenantId, List.of(shipmentId), null, null);

        assertThat(result.pdfBase64List()).isEmpty();
        assertThat(result.exceptions()).hasSize(1);
        assertThat(result.exceptions().get(0).reason()).isEqualTo("NON_PRINTABLE_TYPE:CRP");
        verifyNoInteractions(bostaGateway);
    }

    // ── 5. Bosta rejects the batch → BOSTA_REJECTED in exceptions ────────────

    @Test
    void t05_awbPrint_bostaRejects_exception() {
        UUID shipmentId = createShipmentWithOrder("AWB-T05", "created", null, new BigDecimal("99"));

        when(bostaGateway.printMassAwb(any(), anyList(), any(), any()))
            .thenThrow(new BostaException("Invalid tracking number AWB-T05"));

        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(tenantId, List.of(shipmentId), null, null);

        assertThat(result.pdfBase64List()).isEmpty();
        assertThat(result.exceptions()).hasSize(1);
        assertThat(result.exceptions().get(0).reason()).startsWith("BOSTA_REJECTED:");
        assertThat(result.exceptions().get(0).trackingNumber()).isEqualTo("AWB-T05");

        String failedReason = jdbc.queryForObject(
            "SELECT awb_print_failed_reason FROM shipments WHERE id = ?",
            String.class, shipmentId);
        assertThat(failedReason).startsWith("BOSTA_REJECTED:");
    }

    // ── 6. Mixed batch: 1 printable + 1 non-printable ────────────────────────

    @Test
    void t06_awbPrint_mixed_pdfForPrintable_exceptionForNonPrintable() {
        UUID printable    = createShipmentWithOrder("AWB-T06A", "created", null, new BigDecimal("50"));
        UUID nonPrintable = createShipmentWithOrder("AWB-T06B", "returned",        null, new BigDecimal("50"));

        when(bostaGateway.printMassAwb(any(), eq(List.of("AWB-T06A")), any(), any()))
            .thenReturn(new AwbPrintResult(FAKE_PDF, null));

        BostaAwbService.AwbBatchResult result =
            awbService.printAwb(tenantId, List.of(printable, nonPrintable), null, null);

        assertThat(result.pdfBase64List()).hasSize(1);
        assertThat(result.exceptions()).hasSize(1);
        assertThat(result.exceptions().get(0).trackingNumber()).isEqualTo("AWB-T06B");
    }

    // ── 7. BOSTA_MANAGED: createPickup never called ──────────────────────────

    @Test
    void t07_pickup_bostaManagedMode_noApiCall() {
        createPickupReadyShipment("AWB-P07A", new BigDecimal("150"));
        createPickupReadyShipment("AWB-P07B", new BigDecimal("200"));

        BostaPickupService.PickupManifest manifest =
            pickupService.schedulePickup(tenantId, LocalDate.now().plusDays(1));

        assertThat(manifest.mode()).isEqualTo("BOSTA_MANAGED");
        assertThat(manifest.providerPickupId()).isNull();
        assertThat(manifest.parcelCount()).isEqualTo(2);
        assertThat(manifest.totalCod()).isEqualByComparingTo("350.00");
        assertThat(manifest.shipments()).hasSize(2);

        verifyNoInteractions(bostaGateway);

        // Pickup row persisted
        int pickupCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pickups WHERE tenant_id = ?", Integer.class, tenantId);
        assertThat(pickupCount).isEqualTo(1);
    }

    // ── 8. TRACED_MANAGED happy path: createPickup called ────────────────────

    @Test
    void t08_pickup_tracedManagedMode_callsBostaApi() {
        setPickupMode("TRACED_MANAGED");
        createPickupReadyShipment("AWB-P08", new BigDecimal("300"));

        when(bostaGateway.createPickup(eq("awb-api-key"), any(), eq("loc-123"), any(), eq(1)))
            .thenReturn("BOSTA-PID-001");

        BostaPickupService.PickupManifest manifest =
            pickupService.schedulePickup(tenantId, LocalDate.now().plusDays(1));

        assertThat(manifest.providerPickupId()).isEqualTo("BOSTA-PID-001");
        assertThat(manifest.mode()).isEqualTo("TRACED_MANAGED");
        assertThat(manifest.parcelCount()).isEqualTo(1);
        assertThat(manifest.totalCod()).isEqualByComparingTo("300.00");

        verify(bostaGateway).createPickup(
            eq("awb-api-key"), any(), eq("loc-123"), any(), eq(1));

        // provider_pickup_id persisted on the pickup row
        String pid = jdbc.queryForObject(
            "SELECT provider_pickup_id FROM pickups WHERE tenant_id = ?",
            String.class, tenantId);
        assertThat(pid).isEqualTo("BOSTA-PID-001");
    }

    // ── 9. "Already exists today" → alreadyExistsMessage set, no crash ───────

    @Test
    void t09_pickup_alreadyExists_surfacedAsMessage() {
        setPickupMode("TRACED_MANAGED");
        createPickupReadyShipment("AWB-P09", new BigDecimal("100"));

        when(bostaGateway.createPickup(any(), any(), any(), any(), anyInt()))
            .thenThrow(new BostaPickupAlreadyExistsException(1078, "Pickup already exists for today"));

        BostaPickupService.PickupManifest manifest =
            pickupService.schedulePickup(tenantId, LocalDate.now().plusDays(1));

        assertThat(manifest.alreadyExistsMessage()).isNotNull();
        assertThat(manifest.alreadyExistsMessage()).contains("already exists");
        assertThat(manifest.parcelCount()).isEqualTo(1);
        assertThat(manifest.providerPickupId()).isNull();

        // Pickup row was still created (for the manifest)
        int pickupCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pickups WHERE tenant_id = ?", Integer.class, tenantId);
        assertThat(pickupCount).isEqualTo(1);
    }

    // ── 10. Past date → 400 ──────────────────────────────────────────────────

    @Test
    void t10_pickup_pastDate_400() {
        assertThatThrownBy(() ->
            pickupService.schedulePickup(tenantId, LocalDate.now().minusDays(1)))
            .hasMessageContaining("past");

        verifyNoInteractions(bostaGateway);
    }

    // ── 11. Friday → 400 (Bosta error 1080 pre-empted) ───────────────────────

    @Test
    void t11_pickup_friday_400() {
        LocalDate friday = LocalDate.now();
        // Roll forward to the next Friday
        while (friday.getDayOfWeek() != java.time.DayOfWeek.FRIDAY) {
            friday = friday.plusDays(1);
        }
        final LocalDate f = friday;
        assertThatThrownBy(() -> pickupService.schedulePickup(tenantId, f))
            .hasMessageContaining("Friday");

        verifyNoInteractions(bostaGateway);
    }

    // ── 12. Manifest COD total = sum of awaiting_pickup shipments ─────────────

    @Test
    void t12_manifest_codTotalCorrect() {
        createPickupReadyShipment("AWB-P12A", new BigDecimal("175.50"));
        createPickupReadyShipment("AWB-P12B", new BigDecimal("224.50"));
        // Shipment already with courier: order is 'with_courier' — must NOT appear in manifest
        createShipmentWithOrder("AWB-P12C", "with_courier", null, new BigDecimal("999.00"));

        BostaPickupService.PickupManifest manifest =
            pickupService.schedulePickup(tenantId, LocalDate.now().plusDays(2));

        assertThat(manifest.parcelCount()).isEqualTo(2);
        assertThat(manifest.totalCod()).isEqualByComparingTo("400.00");
        assertThat(manifest.shipments()).extracting(BostaPickupService.ManifestLine::trackingNumber)
            .containsExactlyInAnyOrder("AWB-P12A", "AWB-P12B");

        verifyNoInteractions(bostaGateway);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates an order in 'awaiting_pickup' status + shipment in 'created' state.
     * This is the "ready for Bosta pickup" state: AWB scanned, order pending handover.
     */
    private UUID createPickupReadyShipment(String trackingNumber, BigDecimal cod) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "  cod_amount, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'awaiting_pickup'::order_status, ?, 'cod'::order_payment_method, now()) " +
            "RETURNING id",
            UUID.class, tenantId, storeId,
            "EXT-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(), cod);
        return jdbc.queryForObject(
            "INSERT INTO shipments " +
            "  (tenant_id, order_id, provider, tracking_number, internal_state, cod_amount, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, ?, '{}') " +
            "RETURNING id",
            UUID.class, tenantId, orderId, trackingNumber, cod);
    }

    /**
     * Creates an order + shipment in the given shipment internal_state (for AWB filter tests).
     * deliveryType stored in shipments.raw->>type if non-null (for CRP/CASH_COLLECTION tests).
     */
    private UUID createShipmentWithOrder(String trackingNumber, String state,
                                          String deliveryType, BigDecimal cod) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "  cod_amount, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::order_status, ?, 'cod'::order_payment_method, now()) " +
            "RETURNING id",
            UUID.class, tenantId, storeId,
            "EXT-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(), cod);

        String rawJson = deliveryType != null
            ? "{\"type\":\"" + deliveryType + "\"}"
            : "{}";

        return jdbc.queryForObject(
            "INSERT INTO shipments " +
            "  (tenant_id, order_id, provider, tracking_number, internal_state, cod_amount, raw) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, ?::jsonb) " +
            "RETURNING id",
            UUID.class,
            tenantId, orderId, trackingNumber, state, cod, rawJson);
    }

    /** Switches the tenant's courier_account pickup_mode for per-test overrides. */
    private void setPickupMode(String mode) {
        jdbc.update(
            "UPDATE courier_accounts SET pickup_mode = ? WHERE tenant_id = ? AND provider = 'bosta'",
            mode, tenantId);
    }
}
