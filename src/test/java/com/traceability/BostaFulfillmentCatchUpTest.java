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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fulfillment-link catch-up (2026-10-03).
 *
 *   cu1 dry run writes nothing; verdicts: WOULD_LINK for a type 10 and a type 20 (one from a tracking
 *       row, one only in orders.raw), SKIP + reason for a reference pointing elsewhere; a cancelled
 *       fulfillment, a Wijha fulfillment and an already-linked order are not candidates
 *   cu2 apply links through the Part 2 decision (raw-only fulfillment gets its tracking row first);
 *       the refused row is a conflict, not linked
 *   cu3 rerunning apply is idempotent: nothing new linked, no second link event
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaFulfillmentCatchUpTest {

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
        r.add("bosta.fulfillment-link.catch-up.delay-ms", () -> "0");
    }

    @Autowired JdbcTemplate                   jdbc;
    @Autowired ObjectMapper                   mapper;
    @Autowired EncryptionService              encryptionService;
    @Autowired BostaFulfillmentCatchUpService catchUp;
    @MockBean  BostaGateway                   bostaGateway;
    @MockBean  JobScheduler                   jobScheduler;

    private UUID tenant, store;

    @BeforeEach
    void setUp() {
        reset(bostaGateway);
        tenant = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'BROEK-cu')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, tenant, "cu-" + store + ".myshopify.com",
            Timestamp.from(Instant.parse("2026-09-29T10:51:17Z")));
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryptionService.encrypt("key-cu"));
    }

    @Test
    void cu1_dryRun_writesNothing_reportsVerdicts() {
        String sfx = suffix();
        seed(sfx);
        String before = snapshot();

        List<BostaFulfillmentCatchUpService.Row> rows = catchUp.run(tenant, false);

        assertThat(snapshot()).isEqualTo(before);
        Map<String, BostaFulfillmentCatchUpService.Row> byTn = byTracking(rows);
        assertThat(byTn.keySet()).containsExactlyInAnyOrder("41" + sfx, "42" + sfx, "43" + sfx);
        assertThat(byTn.get("41" + sfx)).satisfies(r -> {
            assertThat(r.verdict()).isEqualTo("WOULD_LINK");
            assertThat(r.typeCode()).isEqualTo(10);
            assertThat(r.source()).isEqualTo("order_fulfillment_tracking");
        });
        assertThat(byTn.get("42" + sfx)).satisfies(r -> {
            assertThat(r.verdict()).isEqualTo("WOULD_LINK");
            assertThat(r.typeCode()).isEqualTo(20);
            assertThat(r.source()).isEqualTo("orders_raw");
        });
        assertThat(byTn.get("43" + sfx)).satisfies(r -> {
            assertThat(r.verdict()).isEqualTo("SKIP");
            assertThat(r.reason()).contains("point at order");
        });
    }

    @Test
    void cu2_apply_linksThroughThePart2Decision() {
        String sfx = suffix();
        seed(sfx);

        List<BostaFulfillmentCatchUpService.Row> rows = catchUp.run(tenant, true);

        Map<String, BostaFulfillmentCatchUpService.Row> byTn = byTracking(rows);
        assertThat(byTn.get("41" + sfx).verdict()).isEqualTo("LINKED");
        assertThat(byTn.get("42" + sfx).verdict()).isEqualTo("LINKED");
        assertThat(byTn.get("43" + sfx).verdict()).isEqualTo("SKIP");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND shipment_leg = 'forward' " +
            "AND tracking_number IN ('41" + sfx + "', '42" + sfx + "')")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM order_fulfillment_tracking WHERE tenant_id = ? " +
            "AND tracking_number = '42" + sfx + "' AND link_status = 'linked'"))
            .as("the raw-only fulfillment got its tracking row and went through Part 2").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_fulfillment_tracking WHERE tenant_id = ? " +
            "AND tracking_number = '43" + sfx + "' AND link_status = 'conflict'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? " +
            "AND source::text = 'shopify_fulfillment'")).isEqualTo(2);
    }

    @Test
    void cu3_rerunningApply_isIdempotent() {
        String sfx = suffix();
        seed(sfx);
        catchUp.run(tenant, true);
        String after = snapshot();

        List<BostaFulfillmentCatchUpService.Row> again = catchUp.run(tenant, true);

        assertThat(again).noneMatch(r -> "LINKED".equals(r.verdict()));
        assertThat(byTracking(again)).doesNotContainKeys("41" + sfx, "42" + sfx);
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tenant_id = ?")).isEqualTo(3);   // 2 linked + 1 pre-existing
        assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? " +
            "AND source::text = 'shopify_fulfillment'")).isEqualTo(2);
        assertThat(snapshotLinks()).isEqualTo(after.substring(0, after.indexOf('#')));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    /**
     *  41… type 10, tracking row                          → WOULD_LINK / LINKED
     *  42… type 20, only in orders.raw (REST fulfillment)  → WOULD_LINK / LINKED
     *  43… reference points at another order               → SKIP / conflict
     *  44… cancelled fulfillment, 45… Wijha, 46… already linked → not candidates
     */
    private void seed(String sfx) {
        UUID o1 = order("BRK-1" + sfx + "-EG", "{}");
        tracking(o1, "41" + sfx, "success", "bosta");
        stub("41" + sfx, 10, "Send", 24, "BRK-1" + sfx + "-EG");

        UUID o2 = order("BRK-2" + sfx + "-EG", rawWith("Bosta", "42" + sfx, "success"));
        stub("42" + sfx, 20, "Return to Origin", 30, "BRK-2" + sfx + "-EG");

        UUID o3 = order("BRK-3" + sfx + "-EG", "{}");
        order("BRK-9" + sfx + "-EG", "{}");
        tracking(o3, "43" + sfx, "success", "bosta");
        stub("43" + sfx, 10, "Send", 24, "BRK-9" + sfx + "-EG");

        UUID o4 = order("BRK-4" + sfx + "-EG", "{}");
        tracking(o4, "44" + sfx, "cancelled", "bosta");
        UUID o5 = order("BRK-5" + sfx + "-EG", rawWith("Wijha", "WJ-45" + sfx, "success"));
        UUID o6 = order("BRK-6" + sfx + "-EG", "{}");
        tracking(o6, "46" + sfx, "success", "bosta");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state)", tenant, o6, "46" + sfx);
    }

    private String rawWith(String company, String tn, String status) {
        ObjectNode raw = mapper.createObjectNode();
        ObjectNode f = raw.putArray("fulfillments").addObject();
        f.put("id", 1);
        f.put("status", status);
        f.put("service", "manual");
        f.put("tracking_company", company);
        f.put("tracking_number", tn);
        f.putArray("tracking_numbers").add(tn);
        if ("Bosta".equals(company)) f.put("tracking_url", "https://bosta.co/tracking-shipments?shipment-number=" + tn);
        return raw.toString();
    }

    private UUID order(String number, String raw) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now(), ?::jsonb) RETURNING id", UUID.class,
            tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number, raw);
    }

    private void tracking(UUID order, String tn, String status, String cls) {
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, " +
            "carrier_class, shopify_fulfillment_id, fulfillment_status) VALUES (?, ?, ?, 'Bosta', ?, '1', ?)",
            tenant, order, tn, cls, status);
    }

    private void stub(String tn, int typeCode, String typeValue, int state, String ref) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tn);
        raw.put("businessReference", ref);
        raw.put("createdAt", "Thu Oct 01 2026 15:20:00 GMT+0000 (Coordinated Universal Time)");
        raw.put("updatedAt", "2026-10-01T18:30:00.000Z");
        raw.putObject("type").put("code", typeCode).put("value", typeValue);
        raw.putObject("state").put("code", state);
        when(bostaGateway.fetchDelivery(eq("key-cu"), eq(tn))).thenReturn(BostaDelivery.fromRaw(tn, raw));
    }

    private String suffix() {
        return String.valueOf(10_000_000 + Math.abs(tenant.hashCode() % 89_999_999));
    }

    private String snapshot() {
        return snapshotLinks() + "#" + jdbc.queryForObject(
            "SELECT (SELECT COUNT(*) FROM order_fulfillment_tracking WHERE tenant_id = ?) || '|' || " +
            "       (SELECT md5(coalesce(string_agg(raw::text || status::text, ',' ORDER BY id), '')) FROM orders WHERE tenant_id = ?) || '|' || " +
            "       (SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ?)",
            String.class, tenant, tenant, tenant);
    }

    private String snapshotLinks() {
        return jdbc.queryForObject(
            "SELECT (SELECT COUNT(*) FROM shipments WHERE tenant_id = ?) || '|' || " +
            "       (SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ?) || '|' || " +
            "       (SELECT md5(coalesce(string_agg(tracking_number || coalesce(link_status, '-'), ',' ORDER BY id), '')) " +
            "          FROM order_fulfillment_tracking WHERE tenant_id = ?)",
            String.class, tenant, tenant, tenant);
    }

    private Map<String, BostaFulfillmentCatchUpService.Row> byTracking(List<BostaFulfillmentCatchUpService.Row> rows) {
        return rows.stream().collect(Collectors.toMap(BostaFulfillmentCatchUpService.Row::trackingNumber, r -> r));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class, tenant);
    }
}
