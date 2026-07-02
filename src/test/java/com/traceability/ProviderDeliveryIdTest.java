package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShipmentLinkService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-4.6 prerequisite: shipments.provider_delivery_id population on both link paths.
 *
 * Matrix:
 *   t1 — Mode-B auto-match: provider_delivery_id set from raw._id; no gateway fetch
 *   t2 — AWB scan (new shipment): gateway called once; _id stored
 *   t3 — AWB scan fetch failure: link still succeeds; _id NULL; flag set; exception detected
 *   t4 — Mode-B re-link (existing, NULL _id): idempotent backfill from raw
 *   t5 — V23 schema: provider_id_fetch_failed column exists with correct default
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderDeliveryIdTest {

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

    @Autowired ShipmentLinkService linkSvc;
    @Autowired ExceptionService    exceptionSvc;
    @Autowired BostaStateMapper    stateMapper;
    @Autowired JdbcTemplate        jdbc;
    @Autowired EncryptionService   encryptionService;
    @Autowired ObjectMapper        mapper;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    UUID tenantId, actorId, storeId, variantId;

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        actorId   = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PdTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'owner@pd.local', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'pd.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-PD', 'Widget', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-PD', 'Blue', 'WGT-PD')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'test-hash', 'active')",
                    tenantId, encryptionService.encrypt("pd-api-key"));
    }

    @BeforeEach void ctx() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        reset(bostaGateway);
        jdbc.update("DELETE FROM exception_resolutions      WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events               WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations                WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments                  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items                WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders                     WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries  WHERE tenant_id = ?", tenantId);
    }

    // ── t1: Mode-B auto-match sets provider_delivery_id from raw._id; no fetch call ──

    @Test
    void t1_modeB_setsProviderDeliveryIdFromRaw_noGatewayFetch() {
        UUID orderId = packedOrder("ORD-PD1");

        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "BOSTA-ID-001");
        raw.put("trackingNumber", "AWB-MB001");
        BostaDelivery delivery = new BostaDelivery("AWB-MB001", 41, "SEND", 0, "ORD-PD1", null, raw);
        BostaStateMapper.MappedState mapped = stateMapper.map(41, "SEND");

        ShipmentLinkService.LinkResult result = linkSvc.tryMatchDelivery(tenantId, "AWB-MB001", delivery, mapped);
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(providerDeliveryId("AWB-MB001")).isEqualTo("BOSTA-ID-001");

        // Mode-B extracts _id from raw — must never call fetchDelivery
        verify(bostaGateway, never()).fetchDelivery(any(), any());
    }

    // ── t2: AWB scan triggers exactly one fetchDelivery call; _id stored ──────────

    @Test
    void t2_awbScan_triggersExactlyOneFetch_storesId() {
        UUID orderId = packedOrder("ORD-PD2");

        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "BOSTA-ID-002");
        when(bostaGateway.fetchDelivery(eq("pd-api-key"), eq("AWB-SC002")))
            .thenReturn(new BostaDelivery("AWB-SC002", 41, "SEND", 0, null, null, raw));

        linkSvc.linkByAwbScan(orderId, "AWB-SC002", actorId);

        verify(bostaGateway, times(1)).fetchDelivery("pd-api-key", "AWB-SC002");
        assertThat(providerDeliveryId("AWB-SC002")).isEqualTo("BOSTA-ID-002");
        assertThat(providerIdFetchFailed("AWB-SC002")).isFalse();
    }

    // ── t3: fetch throws → link succeeds; _id NULL; flag set; exception detected ─

    @Test
    void t3_awbScan_fetchFailure_linkSucceeds_flagSet_exceptionDetected() {
        UUID orderId = packedOrder("ORD-PD3");

        when(bostaGateway.fetchDelivery(eq("pd-api-key"), eq("AWB-SC003")))
            .thenThrow(new BostaTransientException("network timeout"));

        Map<String, Object> result = linkSvc.linkByAwbScan(orderId, "AWB-SC003", actorId);

        // Link is the primary action — it must succeed regardless of fetch outcome
        assertThat(result.get("orderStatus")).isEqualTo("awaiting_pickup");
        assertThat(providerDeliveryId("AWB-SC003")).isNull();
        assertThat(providerIdFetchFailed("AWB-SC003")).isTrue();

        // Exception detector surfaces this for operator retry
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>)
            exceptionSvc.listExceptions("missing_provider_id", null, 0, 10).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("type")).isEqualTo("missing_provider_id");
        assertThat(items.get(0).get("tracking_number")).isEqualTo("AWB-SC003");
    }

    // ── t4: Mode-B re-link backfills NULL provider_delivery_id on existing shipment ─

    @Test
    void t4_modeB_reLinkBackfillsNullProviderId() {
        UUID orderId = packedOrder("ORD-PD4");
        // Pre-insert a shipment with no provider_delivery_id (simulates existing Mode-B row
        // that was created before V23 backfill or before _id extraction was added)
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, 'bosta', 'AWB-MB004', 'created')",
            tenantId, orderId);
        assertThat(providerDeliveryId("AWB-MB004")).isNull();

        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "BOSTA-ID-004");
        raw.put("trackingNumber", "AWB-MB004");
        BostaDelivery delivery = new BostaDelivery("AWB-MB004", 41, "SEND", 0, "ORD-PD4", null, raw);
        BostaStateMapper.MappedState mapped = stateMapper.map(41, "SEND");

        // Re-link: createOrFindShipment finds existing, backfills _id
        ShipmentLinkService.LinkResult result = linkSvc.tryMatchDelivery(tenantId, "AWB-MB004", delivery, mapped);
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(providerDeliveryId("AWB-MB004")).isEqualTo("BOSTA-ID-004");

        // No scan-path fetch for Mode-B path
        verify(bostaGateway, never()).fetchDelivery(any(), any());
    }

    // ── t5: V23 schema — provider_id_fetch_failed column exists and defaults to false ─

    @Test
    void t5_v23_providerIdFetchFailedColumnExistsAndDefaultsFalse() {
        int colCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND table_name = 'shipments' " +
            "  AND column_name = 'provider_id_fetch_failed'",
            Integer.class);
        assertThat(colCount).as("V23 must add provider_id_fetch_failed to shipments").isOne();

        // Newly inserted shipment defaults to false (not null, not true)
        UUID orderId = packedOrder("ORD-PD5");
        UUID sid = jdbc.queryForObject(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, 'bosta', 'AWB-PD5', 'created') RETURNING id",
            UUID.class, tenantId, orderId);
        Boolean flag = jdbc.queryForObject(
            "SELECT provider_id_fetch_failed FROM shipments WHERE id = ?", Boolean.class, sid);
        assertThat(flag).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID packedOrder(String number) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, " +
            "    cod_amount, placed_at) " +
            "VALUES (?, ?, ?, ?, 'packed'::order_status, 'cod', 0, now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + number, number);
    }

    private String providerDeliveryId(String trackingNumber) {
        return jdbc.query(
            "SELECT provider_delivery_id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            trackingNumber, tenantId);
    }

    private boolean providerIdFetchFailed(String trackingNumber) {
        Boolean flag = jdbc.query(
            "SELECT provider_id_fetch_failed FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getBoolean(1) : null,
            trackingNumber, tenantId);
        return Boolean.TRUE.equals(flag);
    }
}
