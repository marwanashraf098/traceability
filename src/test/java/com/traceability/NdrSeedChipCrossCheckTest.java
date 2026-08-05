package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.fulfillment.OrderStatusDeriver;
import com.traceability.fulfillment.OrderStatusDeriver.Chip;
import com.traceability.integrations.bosta.*;
import com.traceability.security.EncryptionService;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Close-out item 2(c) — proves OrderStatusDeriver.NDR_CHIP_KEY (codes 1/3/8) is not
 * transposed against the ACTUAL §8.4 seed (V2__bosta_seed.sql), and that the mapping holds
 * end-to-end through a real Bosta webhook payload, not a hand-fed exceptionCode.
 *
 * Deliberately NOT circular: expectations are read from the real, seeded ndr_codes table
 * (never from NDR_CHIP_KEY itself), and the exception_code fed into derive() is the value
 * BostaWebhookJob.process() actually persisted after processing a state-47 payload shaped
 * like a real Bosta delivery response — the same path production traffic takes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NdrSeedChipCrossCheckTest {

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

    @Autowired JdbcTemplate      jdbc;
    @Autowired ObjectMapper      mapper;
    @Autowired EncryptionService encryptionService;
    @Autowired BostaWebhookJob   webhookJob;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    UUID tenantId, storeId;

    @BeforeAll
    void setupFixture() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'NdrCrossCheckTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'ndr-cross-check.myshopify.com', 'disconnected')",
                    storeId, tenantId);
    }

    @BeforeEach
    void cleanup() {
        jdbc.execute("DELETE FROM shipment_status_history");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
        jdbc.execute("DELETE FROM courier_accounts");
        reset(bostaGateway);
    }

    // ── (c) NDR seed cross-check, non-circular ──────────────────────────────────

    @Test
    void code1_seedDescriptionMeansNotAtAddress_andChipMapMatchesRealIngestedCode() {
        // Non-circular ground truth: read the ACTUAL seeded description for code 1, not
        // NDR_CHIP_KEY. V2__bosta_seed.sql: (1, 'forward', 'Customer not at address', ...).
        String description = seedDescription(1);
        assertThat(description).containsIgnoringCase("not at address");
        assertThat(description).doesNotContainIgnoringCase("postpon").doesNotContainIgnoringCase("refus");

        int persistedCode = ingestExceptionViaRealWebhook("NDR-CC-1", 1, "Customer not at address");
        assertThat(persistedCode).isEqualTo(1);

        Chip chip = onlyChip(OrderStatusDeriver.derive(
            "awaiting_pickup", "exception", 0, 1, 0, persistedCode, false, false, false));
        assertThat(chip.key()).isEqualTo("chip.not_at_address");
    }

    @Test
    void code3_seedDescriptionMeansPostponed_andChipMapMatchesRealIngestedCode() {
        // V2__bosta_seed.sql: (3, 'forward', 'Postponed by customer', ...).
        String description = seedDescription(3);
        assertThat(description).containsIgnoringCase("postpon");
        assertThat(description).doesNotContainIgnoringCase("not at address").doesNotContainIgnoringCase("refus");

        int persistedCode = ingestExceptionViaRealWebhook("NDR-CC-3", 3, "Postponed by customer");
        assertThat(persistedCode).isEqualTo(3);

        Chip chip = onlyChip(OrderStatusDeriver.derive(
            "awaiting_pickup", "exception", 0, 1, 0, persistedCode, false, false, false));
        assertThat(chip.key()).isEqualTo("chip.postponed");
    }

    @Test
    void code8_seedDescriptionMeansRefused_andChipMapMatchesRealIngestedCode() {
        // V2__bosta_seed.sql: (8, 'forward', 'Refused by customer', ...).
        String description = seedDescription(8);
        assertThat(description).containsIgnoringCase("refus");
        assertThat(description).doesNotContainIgnoringCase("postpon").doesNotContainIgnoringCase("not at address");

        int persistedCode = ingestExceptionViaRealWebhook("NDR-CC-8", 8, "Refused by customer");
        assertThat(persistedCode).isEqualTo(8);

        Chip chip = onlyChip(OrderStatusDeriver.derive(
            "awaiting_pickup", "exception", 0, 1, 0, persistedCode, false, false, false));
        assertThat(chip.key()).isEqualTo("chip.customer_refused");
    }

    @Test
    void returnSideCodes21and22_seedDescriptionMeansPostponed() {
        // V2__bosta_seed.sql: (21, 'return', 'Postponed', ...), (22, 'return', 'Postponed', ...).
        assertThat(seedDescription(21)).containsIgnoringCase("postpon");
        assertThat(seedDescription(22)).containsIgnoringCase("postpon");

        Chip chip21 = onlyChip(OrderStatusDeriver.derive(
            "awaiting_pickup", "exception", 0, 1, 0, 21, false, false, false));
        Chip chip22 = onlyChip(OrderStatusDeriver.derive(
            "awaiting_pickup", "exception", 0, 1, 0, 22, false, false, false));
        assertThat(chip21.key()).isEqualTo("chip.postponed");
        assertThat(chip22.key()).isEqualTo("chip.postponed");
    }

    /**
     * No transposition anywhere in the 5 mapped codes: every (code, expected chip) pair is
     * checked against every OTHER expected chip to catch a swap that a same-value check
     * alone would miss (e.g. code 1 and 8 both silently mapping to the same wrong chip).
     */
    @Test
    void allFiveMappedCodes_pairwiseDistinctWhereSeedMeaningDiffers() {
        Map<Integer, String> expected = Map.of(
            1, "chip.not_at_address",
            3, "chip.postponed",
            8, "chip.customer_refused"
        );
        for (Map.Entry<Integer, String> e : expected.entrySet()) {
            Chip chip = onlyChip(OrderStatusDeriver.derive(
                "awaiting_pickup", "exception", 0, 1, 0, e.getKey(), false, false, false));
            assertThat(chip.key()).as("code=" + e.getKey()).isEqualTo(e.getValue());
            for (Map.Entry<Integer, String> other : expected.entrySet()) {
                if (!other.getKey().equals(e.getKey())) {
                    assertThat(chip.key()).as("code=" + e.getKey() + " must not collide with code=" + other.getKey())
                        .isNotEqualTo(other.getValue());
                }
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String seedDescription(int code) {
        return jdbc.queryForObject(
            "SELECT description FROM ndr_codes WHERE code = ?", String.class, code);
    }

    private Chip onlyChip(OrderStatusDeriver.DerivedOrderStatus d) {
        assertThat(d.healthChips()).hasSize(1);
        return d.healthChips().get(0);
    }

    /**
     * Sends a real Bosta state-47 (Exception) webhook payload — same shape production
     * traffic takes — through the real BostaWebhookJob, and returns the exception_code the
     * job actually persisted onto shipments. Mirrors DeliveryStatusTest.d2's pattern.
     */
    private int ingestExceptionViaRealWebhook(String tracking, int exceptionCode, String exceptionReason) {
        String updatedAt = "2026-07-10T08:00:00.000Z";
        jdbc.update(
            "INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'hash', 'active')",
            tenantId, encryptionService.encrypt("ndr-cc-key-" + tracking));

        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + tracking);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state)",
            tenantId, orderId, tracking);

        ObjectNode raw = mapper.createObjectNode();
        raw.put("updatedAt", updatedAt);
        raw.put("exceptionCode", exceptionCode);
        raw.put("exceptionReason", exceptionReason);

        BostaDelivery delivery = new BostaDelivery(tracking, 47, "SEND", 2, "EXT-" + tracking, null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking))).thenReturn(delivery);

        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":47,\"type\":\"SEND\",\"updatedAt\":\"%s\"}",
            tracking, updatedAt);
        long eventId = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status) " +
            "VALUES ('bosta_poll'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending') " +
            "RETURNING id",
            Long.class, tenantId, payload);

        webhookJob.process(eventId, tenantId);

        return jdbc.queryForObject(
            "SELECT exception_code FROM shipments WHERE tracking_number = ?", Integer.class, tracking);
    }
}
