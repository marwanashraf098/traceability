package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Read-only Bosta visibility check (2026-10-02).
 *
 *   vc1 FOUND (type / state / reference / Shopify id), NOT_FOUND (404 and Bosta's 400
 *       "Delivery not found"), and a 429 that backs off and continues → FOUND; candidates come
 *       from order_fulfillment_tracking and from REST-shaped orders.raw; orders that already
 *       have a forward shipment, and cancelled fulfillments, are not candidates
 *   vc2 only the tenant's own key is ever used (another tenant's key never)
 *   vc3 writes nothing: shipments, unlinked_bosta_deliveries, orders (incl. raw),
 *       order_fulfillment_tracking and webhook_events unchanged
 *   vc4 a 429 that never clears is reported as ERROR, not dropped
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaVisibilityCheckTest {

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
        r.add("bosta.visibility-check.delay-ms",       () -> "0");
        r.add("bosta.visibility-check.max-backoff-ms", () -> "0");
        r.add("bosta.visibility-check.rate-limit-retries", () -> "2");
    }

    @Autowired JdbcTemplate                jdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired EncryptionService           encryptionService;
    @Autowired BostaVisibilityCheckService service;
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  JobScheduler                jobScheduler;

    private UUID a, aStore, b, bStore;

    @BeforeEach
    void setUp() {
        reset(bostaGateway);
        a = tenant("BROEK-vc"); aStore = store(a);
        b = tenant("Other-vc"); bStore = store(b);
    }

    @Test
    void vc1_foundNotFoundAndRateLimited_reportedCorrectly_fromBothSources() {
        trackedOrder(a, aStore, "BRK-44841-EG", "9432163061", "success");
        trackedOrder(a, aStore, "BRK-44842-EG", "2251220237", "success");
        trackedOrder(a, aStore, "BRK-44843-EG", "9573447153", "success");
        trackedOrder(a, aStore, "BRK-44844-EG", "1282197865", "success");
        rawOrder(a, aStore, "BRK-44855-EG", "563601351", "success");          // raw only, 9 digits
        rawOrder(a, aStore, "BRK-44856-EG", "9214743303", "cancelled");       // cancelled → not a candidate
        UUID linked = trackedOrder(a, aStore, "BRK-44871-EG", "5829813860", "success");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', '5829813860', 'with_courier'::shipment_internal_state)", a, linked);

        when(bostaGateway.fetchDelivery(eq("key-" + a), eq("9432163061")))
            .thenReturn(delivery("9432163061", 10, 24, "BRK-44841-EG", "18912387137815"));
        when(bostaGateway.fetchDelivery(eq("key-" + a), eq("2251220237"))).thenReturn(null);
        when(bostaGateway.fetchDelivery(eq("key-" + a), eq("9573447153")))
            .thenThrow(new DeliveryNotFoundException("9573447153"));
        when(bostaGateway.fetchDelivery(eq("key-" + a), eq("1282197865")))
            .thenThrow(new BostaRateLimitException(30))
            .thenReturn(delivery("1282197865", 10, 10, "BRK-44844-EG", null));
        when(bostaGateway.fetchDelivery(eq("key-" + a), eq("563601351")))
            .thenReturn(delivery("563601351", 10, 45, "BRK-44855-EG", null));

        BostaVisibilityCheckService.Report report = service.check(a);

        assertThat(report.candidatesBySource())
            .containsEntry("order_fulfillment_tracking", 4).containsEntry("orders_raw", 1);
        Map<String, BostaVisibilityCheckService.Row> byTn = new java.util.HashMap<>();
        report.rows().forEach(r -> byTn.put(r.trackingNumber(), r));
        assertThat(byTn).doesNotContainKeys("5829813860", "9214743303");

        assertThat(byTn.get("9432163061")).satisfies(r -> {
            assertThat(r.result()).isEqualTo("FOUND");
            assertThat(r.typeCode()).isEqualTo(10);
            assertThat(r.state()).isEqualTo(24);
            assertThat(r.businessReference()).isEqualTo("BRK-44841-EG");
            assertThat(r.shopifyOrderId()).isEqualTo("18912387137815");
            assertThat(r.orderNumber()).isEqualTo("BRK-44841-EG");
            assertThat(r.carrierRaw()).isEqualTo("Bosta");
            assertThat(r.tenant()).isEqualTo("BROEK-vc");
        });
        assertThat(byTn.get("2251220237").result()).isEqualTo("NOT_FOUND");
        assertThat(byTn.get("9573447153").result()).isEqualTo("NOT_FOUND");
        assertThat(byTn.get("1282197865").result()).as("429 backs off and continues").isEqualTo("FOUND");
        assertThat(byTn.get("563601351")).satisfies(r -> {
            assertThat(r.result()).isEqualTo("FOUND");
            assertThat(r.source()).isEqualTo("orders_raw");
        });
        verify(bostaGateway, times(2)).fetchDelivery(anyString(), eq("1282197865"));
    }

    @Test
    void vc2_onlyTheTenantsOwnKeyIsUsed() {
        trackedOrder(a, aStore, "BRK-44876-EG", "5498300341", "success");
        trackedOrder(b, bStore, "BRK-44878-EG", "5818827013", "success");
        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenReturn(null);

        BostaVisibilityCheckService.Report report = service.check(a);

        assertThat(report.rows()).extracting(BostaVisibilityCheckService.Row::trackingNumber)
            .containsExactly("5498300341");
        verify(bostaGateway).fetchDelivery("key-" + a, "5498300341");
        verify(bostaGateway, never()).fetchDelivery(eq("key-" + b), anyString());
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq("5818827013"));
    }

    @Test
    void vc3_writesNothing() {
        trackedOrder(a, aStore, "BRK-44879-EG", "9520154680", "success");
        rawOrder(a, aStore, "BRK-44880-EG", "1002219850", "success");
        when(bostaGateway.fetchDelivery(anyString(), anyString()))
            .thenAnswer(inv -> delivery(inv.getArgument(1), 10, 24, "BRK-44879-EG", null));

        String before = snapshot();
        service.check(a);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void vc4_persistentRateLimit_isReportedAsError() {
        trackedOrder(a, aStore, "BRK-44881-EG", "4069420256", "success");
        when(bostaGateway.fetchDelivery(anyString(), eq("4069420256")))
            .thenThrow(new BostaRateLimitException(30));

        BostaVisibilityCheckService.Report report = service.check(a);

        assertThat(report.rows()).singleElement().satisfies(r -> {
            assertThat(r.result()).isEqualTo("ERROR");
            assertThat(r.error()).contains("rate limited");
        });
        verify(bostaGateway, times(3)).fetchDelivery(anyString(), eq("4069420256"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String snapshot() {
        return jdbc.queryForObject(
            "SELECT (SELECT COUNT(*) FROM shipments) || '|' || " +
            "       (SELECT COUNT(*) FROM unlinked_bosta_deliveries) || '|' || " +
            "       (SELECT COUNT(*) FROM orders) || '|' || " +
            "       (SELECT md5(string_agg(raw::text || coalesce(status::text, ''), ',' ORDER BY id)) FROM orders) || '|' || " +
            "       (SELECT md5(coalesce(string_agg(t::text, ',' ORDER BY id), '')) FROM order_fulfillment_tracking t) || '|' || " +
            "       (SELECT COUNT(*) FROM webhook_events) || '|' || " +
            "       (SELECT COUNT(*) FROM bosta_discovery_failures)",
            String.class);
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt("key-" + id));
        return id;
    }

    private UUID store(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', ?, 'connected')", id, tenant, "vc-" + id + ".myshopify.com");
        return id;
    }

    private UUID order(UUID tenant, UUID store, String number, String rawJson) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, ?::jsonb) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number, rawJson);
    }

    /** An order whose Bosta tracking is in order_fulfillment_tracking (raw has no fulfillments). */
    private UUID trackedOrder(UUID tenant, UUID store, String number, String tracking, String status) {
        UUID id = order(tenant, store, number, "{}");
        jdbc.update("INSERT INTO order_fulfillment_tracking " +
            "(tenant_id, order_id, tracking_number, carrier_raw, tracking_url, carrier_class, shopify_fulfillment_id, fulfillment_status) " +
            "VALUES (?, ?, ?, 'Bosta', ?, 'bosta', '1', ?)",
            tenant, id, tracking, "https://bosta.co/tracking-shipments?shipment-number=" + tracking, status);
        return id;
    }

    /** An order whose Bosta tracking is only in the stored REST payload. */
    private UUID rawOrder(UUID tenant, UUID store, String number, String tracking, String status) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("name", number);
        ObjectNode f = raw.putArray("fulfillments").addObject();
        f.put("status", status);
        f.put("service", "manual");
        f.put("tracking_company", "Bosta");
        f.put("tracking_number", tracking);
        f.putArray("tracking_numbers").add(tracking);
        f.put("tracking_url", "https://bosta.co/tracking-shipments?shipment-number=" + tracking);
        return order(tenant, store, number, raw.toString());
    }

    private BostaDelivery delivery(String tracking, int typeCode, int state, String ref, String shopifyId) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tracking);
        raw.put("businessReference", ref);
        raw.putObject("type").put("code", typeCode).put("value", "Send");
        raw.putObject("state").put("code", state);
        if (shopifyId != null) raw.putObject("shopifyInfo").put("orderId", shopifyId);
        return BostaDelivery.fromRaw(tracking, raw);
    }
}
