package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Visibility check enrichment (2026-10-03, read-only).
 *
 *   ve1 a FOUND row carries when / how / from where Bosta created the delivery (createdAt,
 *       updatedAt, creationSrc, sender, pickup location …) and no customer PII
 *   ve2 the run ends with page 1 of the delivery list exactly as discovery fetches it (page 1,
 *       discovery's page size, the tenant's own key): Bosta's count + per-item fields; writes nothing
 *   ve3 a failing list call is reported as the sample's error, the rest of the report intact
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaVisibilityEnrichmentTest {

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
        r.add("bosta.visibility-check.delay-ms", () -> "0");
    }

    @Autowired JdbcTemplate                jdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired EncryptionService           encryptionService;
    @Autowired BostaVisibilityCheckService service;
    @MockBean  BostaGateway                bostaGateway;
    @MockBean  JobScheduler                jobScheduler;

    private UUID tenant;

    @BeforeEach
    void setUp() {
        reset(bostaGateway);
        tenant = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'BROEK-ve')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', ?, 'connected')", store, tenant, "ve-" + store + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryptionService.encrypt("key-ve-" + tenant));
        UUID order = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, raw) " +
            "VALUES (?, ?, ?, 'BRK-44841-EG', 'new'::order_status, '{}'::jsonb) RETURNING id", UUID.class,
            tenant, store, "gid://shopify/Order/" + UUID.randomUUID());
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, " +
            "carrier_class, fulfillment_status) VALUES (?, ?, ?, 'Bosta', 'bosta', 'success')", tenant, order,
            "94321" + Math.abs(tenant.hashCode() % 100000));
    }

    @Test
    void ve1_foundRow_carriesCreationDetails_noPii() {
        String tn = trackingNumber();
        when(bostaGateway.fetchDelivery(eq("key-ve-" + tenant), eq(tn))).thenReturn(BostaDelivery.fromRaw(tn, delivery(tn)));

        BostaVisibilityCheckService.Report report = service.check(tenant);

        Map<String, Object> d = report.rows().get(0).details();
        assertThat(d).containsEntry("createdAt", "Thu Oct 01 2026 15:20:00 GMT+0000 (Coordinated Universal Time)")
            .containsEntry("updatedAt", "2026-10-02T09:00:00.000Z")
            .containsEntry("creationSrc", "SHOPIFY")
            .containsEntry("senderId", "NCA2zf5f7jDmqlogLk1Kz")
            .containsEntry("senderName", "Broek")
            .containsEntry("pickupCity", "Gharbia")
            .containsEntry("pickupRequestId", "PR-1")
            .containsEntry("isExternalFulfillmentOrder", false)
            .containsEntry("hasShopifyInfo", true);
        assertThat(mapper.valueToTree(d).toString())
            .doesNotContain("Mona", "01001234567", "Street 9");
    }

    @Test
    void ve2_listSample_isPage1AsDiscoveryFetchesIt_ownKey_writesNothing() {
        String tn = trackingNumber();
        when(bostaGateway.fetchDelivery(anyString(), eq(tn))).thenReturn(null);
        ObjectNode body = mapper.createObjectNode();
        body.put("count", 1234);
        ArrayNode items = body.putArray("deliveries");
        items.add(delivery("5829813860"));
        items.add(delivery("4694716795"));
        when(bostaGateway.listDeliveriesPageRaw(eq("key-ve-" + tenant), eq(1), eq(50))).thenReturn(body);
        String before = snapshot();

        BostaVisibilityCheckService.Report report = service.check(tenant);

        assertThat(snapshot()).isEqualTo(before);
        verify(bostaGateway).listDeliveriesPageRaw("key-ve-" + tenant, 1, 50);
        assertThat(report.listSample().reportedCount()).isEqualTo(1234);
        assertThat(report.listSample().items()).hasSize(2);
        assertThat(report.listSample().items().get(0))
            .containsEntry("position", 1)
            .containsEntry("trackingNumber", "5829813860")
            .containsEntry("typeCode", 10)
            .containsEntry("state", 24)
            .containsEntry("businessReference", "BRK-44871-EG")
            .containsEntry("creationSrc", "SHOPIFY")
            .containsEntry("createdAt", "Thu Oct 01 2026 15:20:00 GMT+0000 (Coordinated Universal Time)");
    }

    @Test
    void ve3_failingListCall_isReportedNotThrown() {
        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenReturn(null);
        when(bostaGateway.listDeliveriesPageRaw(anyString(), anyInt(), anyInt()))
            .thenThrow(new BostaException("Bosta list deliveries error (400 BAD_REQUEST): bad"));

        BostaVisibilityCheckService.Report report = service.check(tenant);

        assertThat(report.rows()).hasSize(1);
        assertThat(report.listSample().error()).contains("400");
    }

    private String trackingNumber() {
        return jdbc.queryForObject("SELECT tracking_number FROM order_fulfillment_tracking WHERE tenant_id = ?",
            String.class, tenant);
    }

    private ObjectNode delivery(String tn) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tn);
        raw.put("businessReference", "BRK-44871-EG");
        raw.put("createdAt", "Thu Oct 01 2026 15:20:00 GMT+0000 (Coordinated Universal Time)");
        raw.put("updatedAt", "2026-10-02T09:00:00.000Z");
        raw.put("creationSrc", "SHOPIFY");
        raw.put("pickupRequestId", "PR-1");
        raw.put("isExternalFulfillmentOrder", false);
        raw.putObject("shopifyInfo").put("orderId", "18912387137815");
        raw.putObject("sender").put("_id", "NCA2zf5f7jDmqlogLk1Kz").put("name", "Broek");
        raw.putObject("pickupAddress").putObject("city").put("name", "Gharbia");
        raw.putObject("receiver").put("firstName", "Mona").put("phone", "01001234567");
        raw.putObject("dropOffAddress").put("firstLine", "Street 9");
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 24);
        return raw;
    }

    private String snapshot() {
        return jdbc.queryForObject(
            "SELECT (SELECT COUNT(*) FROM shipments) || '|' || (SELECT COUNT(*) FROM webhook_events) || '|' || " +
            "(SELECT COUNT(*) FROM unlinked_bosta_deliveries) || '|' || " +
            "(SELECT md5(coalesce(string_agg(t::text, ',' ORDER BY id), '')) FROM order_fulfillment_tracking t)",
            String.class);
    }
}
