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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pre-connect filter (2026-10-02) in BostaWebhookJob — the point every ingest source
 * (webhook, status poll, discovery, backfill) passes through.
 *
 *   pf1  pre-connect SEND (createdAt before the cutoff, BROEK reference) → ignored
 *   pf2  pre-connect SEND booked AFTER the cutoff for a plain-numbered pre-connect order
 *        (Femine "70348" < lowest ingested "70370") → ignored
 *   pf3  pre-connect CRP (type 25, plain reference) → ignored, no return leg, no unlinked row
 *   pf4  pre-connect EXCHANGE with a ':' reference ("BRK-44719-EG:BRK-44719-EG-R1"), booked
 *        after the cutoff → ignored: no exchanges row, no EXC- order, no unlinked row
 *   pf5  post-connect delivery with a NULL reference → still stored as unlinked
 *   pf6  post-connect delivery whose reference matches → still links
 *   pf7  Jumi-style tenant (NULL orders_ingest_from) → never filtered
 *   pf8  Traced-booked legs are untouched: a type 25 booked by a return request and a type 30
 *        attached to a return request, even with a pre-cutoff createdAt and no reference
 *   pf9  cross-tenant: tenant B's order BRK-44719-EG never makes tenant A's delivery resolve
 *        (A ignores it); same-tenant positive control — B links its own
 *   pf10 a redelivery of an ignored event is deduplicated
 *   pf11 blnco-shaped reference "blncoeg:#515960" resolves through the part after the ':' and
 *        is kept even with a pre-cutoff createdAt
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreConnectFilterTest {

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

    // BROEK connected 2026-09-29 13:51 Cairo = 10:51 UTC; Femine 2026-09-30 23:39 Cairo = 20:39 UTC.
    static final Instant BROEK_CUTOFF  = Instant.parse("2026-09-29T10:51:17Z");
    static final Instant FEMINE_CUTOFF = Instant.parse("2026-09-30T20:39:08Z");
    static final String BEFORE_BROEK   = "Tue Sep 29 2026 07:11:01 GMT+0000 (Coordinated Universal Time)";
    static final String AFTER_BROEK    = "Thu Oct 01 2026 11:02:45 GMT+0000 (Coordinated Universal Time)";
    static final String AFTER_FEMINE   = "Thu Oct 01 2026 06:01:01 GMT+0000 (Coordinated Universal Time)";

    @Autowired JdbcTemplate      jdbc;
    @Autowired ObjectMapper      mapper;
    @Autowired EncryptionService encryptionService;
    @Autowired BostaWebhookJob   webhookJob;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    private UUID broek, broekStore, femine, femineStore, jumi, jumiStore, other, otherStore;

    @BeforeEach
    void setUp() {
        reset(bostaGateway);
        broek  = tenant("BROEK-test");  broekStore  = store(broek, BROEK_CUTOFF);
        femine = tenant("Femine-test"); femineStore = store(femine, FEMINE_CUTOFF);
        jumi   = tenant("Jumi-test");   jumiStore   = store(jumi, null);
        other  = tenant("Other-test");  otherStore  = store(other, BROEK_CUTOFF);

        // Post-connect orders, in the stored shapes: BROEK "BRK-<n>-EG", Femine plain digits,
        // Jumi "#<n>".
        order(broek, broekStore, "BRK-44803-EG", "2026-09-29T11:06:45Z");
        order(broek, broekStore, "BRK-44841-EG", "2026-09-30T00:46:23Z");
        order(femine, femineStore, "70370", "2026-09-30T20:57:43Z");
        order(femine, femineStore, "70371", "2026-09-30T21:10:00Z");
        order(jumi, jumiStore, "#385330159470", "2026-09-29T09:00:00Z");
        order(other, otherStore, "BRK-44719-EG", "2026-09-29T12:00:00Z");
    }

    @Test
    void pf1_preConnectSend_createdBeforeCutoff_isIgnored() {
        long ev = process(broek, delivery("3851493678", 10, "Send", 10, "BRK-44799-EG", BEFORE_BROEK));
        assertIgnored(broek, ev, "3851493678");
    }

    @Test
    void pf2_preConnectOrderBookedAfterCutoff_plainNumberBelowLowest_isIgnored() {
        long ev = process(femine, delivery("5277444491", 10, "Send", 10, "70348", AFTER_FEMINE));
        assertIgnored(femine, ev, "5277444491");
    }

    @Test
    void pf3_preConnectCrp_isIgnored() {
        long ev = process(femine, delivery("2407690225", 25, "Customer Return Pickup", 10, "69751", AFTER_FEMINE));
        assertIgnored(femine, ev, "2407690225");
    }

    @Test
    void pf4_preConnectExchange_colonReference_isIgnored_noExchangeRowNoExcOrder() {
        ObjectNode raw = delivery("1010896713", 30, "Exchange", 10, "BRK-44736-EG:BRK-44736-EG-R1", AFTER_BROEK);
        raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Shirt M");
        raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Shirt L");
        long ev = process(broek, raw);
        assertIgnored(broek, ev, "1010896713");
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            broek, "1010896713")).isZero();
        assertThat(count("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND number = ?",
            broek, "EXC-1010896713")).isZero();
    }

    @Test
    void pf5_postConnectNullReference_isStillStoredAsUnlinked() {
        long ev = process(broek, delivery("5771688496", 10, "Send", 10, null, AFTER_BROEK));
        assertThat(eventNote(ev)).isEqualTo("unlinked: 5771688496");
        assertThat(unlinked(broek, "5771688496")).isEqualTo(1);
    }

    @Test
    void pf6_postConnectMatchingReference_stillLinks() {
        long ev = process(broek, delivery("9432163061", 10, "Send", 10, "BRK-44841-EG", AFTER_BROEK));
        assertThat(eventNote(ev)).isNull();
        assertThat(count("SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id = s.order_id " +
            "WHERE s.tenant_id = ? AND s.tracking_number = ? AND o.number = 'BRK-44841-EG'",
            broek, "9432163061")).isEqualTo(1);
    }

    @Test
    void pf7_nullCutoffTenant_isNeverFiltered() {
        long ev = process(jumi, delivery("4818277658", 10, "Send", 10, "#385329349470",
            "Tue Jun 02 2026 10:00:00 GMT+0000 (Coordinated Universal Time)"));
        assertThat(eventNote(ev)).isEqualTo("unlinked: 4818277658");
        assertThat(unlinked(jumi, "4818277658")).isEqualTo(1);
    }

    @Test
    void pf8_tracedBookedLegs_areNeverIgnored() {
        UUID orderId = jdbc.queryForObject(
            "SELECT id FROM orders WHERE tenant_id = ? AND number = 'BRK-44841-EG'", UUID.class, broek);

        // Type 25 booked by a return request (claim written, no shipment yet).
        jdbc.update("INSERT INTO return_requests (tenant_id, order_id, reference, bosta_tracking_number) " +
            "VALUES (?, ?, 'RR-PF8A', '7577553206')", broek, orderId);
        long ev25 = process(broek, delivery("7577553206", 25, "Customer Return Pickup", 10, null, BEFORE_BROEK));
        assertThat(eventNote(ev25)).doesNotStartWith("ignored_pre_connect");

        // Type 30 attached to a return request.
        UUID rr = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, reference, type) " +
            "VALUES (?, ?, 'RR-PF8B', 'exchange') RETURNING id", UUID.class, broek, orderId);
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, raw, return_request_id) " +
            "VALUES (?, '6544364210', 'needs_mapping', '{}'::jsonb, ?)", broek, rr);
        long ev30 = process(broek, delivery("6544364210", 30, "Exchange", 10, null, BEFORE_BROEK));
        assertThat(eventNote(ev30)).doesNotStartWith("ignored_pre_connect");
        assertThat(eventNote(ev30)).startsWith("exchange_own:");
    }

    @Test
    void pf9_crossTenant_otherTenantsOrderNeverResolves_sameTenantControlLinks() {
        // Tenant A (BROEK) — its lowest BRK number is 44803; B's BRK-44719-EG must not count.
        long evA = process(broek, delivery("9651000477", 10, "Send", 10, "BRK-44719-EG", AFTER_BROEK));
        assertIgnored(broek, evA, "9651000477");

        // Same-tenant positive control: tenant B links the same reference to its own order.
        long evB = process(other, delivery("1785841805", 10, "Send", 10, "BRK-44719-EG", AFTER_BROEK));
        assertThat(eventNote(evB)).isNull();
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND tracking_number = ?",
            other, "1785841805")).isEqualTo(1);
    }

    @Test
    void pf10_redeliveryOfIgnoredEvent_isDeduplicated() {
        ObjectNode raw = delivery("6138414003", 10, "Send", 10, "BRK-44797-EG", BEFORE_BROEK);
        long first  = process(broek, raw);
        long second = process(broek, raw);
        assertThat(eventNote(first)).isEqualTo("ignored_pre_connect: 6138414003");
        assertThat(eventNote(second)).isEqualTo("duplicate: already processed");
        verify(bostaGateway, times(1)).fetchDelivery(eq("key-" + broek), eq("6138414003"));
    }

    @Test
    void pf11_shopHandleColonReference_resolvesThroughTheTail_isKept() {
        order(broek, broekStore, "#515960", "2026-09-30T10:00:00Z");
        long ev = process(broek, delivery("713968132", 10, "Send", 10, "blncoeg:#515960", BEFORE_BROEK));
        assertThat(eventNote(ev)).doesNotStartWith("ignored_pre_connect");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void assertIgnored(UUID tenant, long eventId, String tracking) {
        assertThat(jdbc.queryForObject("SELECT status::text FROM webhook_events WHERE id = ?", String.class, eventId))
            .isEqualTo("processed");
        assertThat(eventNote(eventId)).isEqualTo("ignored_pre_connect: " + tracking);
        assertThat(unlinked(tenant, tracking)).as("no unlinked row").isZero();
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND tracking_number = ?", tenant, tracking))
            .as("no shipment").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", tenant, tracking))
            .as("no exchanges row").isZero();
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt("key-" + id));
        return id;
    }

    private UUID store(UUID tenant, Instant cutoff) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
            id, tenant, "pf-" + id + ".myshopify.com", cutoff == null ? null : Timestamp.from(cutoff));
        return id;
    }

    private void order(UUID tenant, UUID store, String number, String placedAt) {
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, ?)",
            tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number,
            Timestamp.from(Instant.parse(placedAt)));
    }

    private ObjectNode delivery(String tracking, int typeCode, String typeValue, int state,
                                String reference, String createdAt) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tracking);
        raw.put("trackingNumber", tracking);
        if (reference != null) raw.put("businessReference", reference); else raw.putNull("businessReference");
        raw.put("createdAt", createdAt);
        raw.put("updatedAt", "2026-10-01T15:00:00.000Z");
        raw.putObject("type").put("code", typeCode).put("value", typeValue);
        raw.putObject("state").put("code", state).put("value", "Pickup requested");
        return raw;
    }

    /** Inserts a webhook event for the delivery and runs BostaWebhookJob on it. */
    private long process(UUID tenant, ObjectNode raw) {
        String tracking = raw.path("trackingNumber").asText();
        BostaDelivery d = BostaDelivery.fromRaw(tracking, raw);
        when(bostaGateway.fetchDelivery(eq("key-" + tenant), eq(tracking))).thenReturn(d);
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tracking);
        payload.put("state", raw.path("state").path("code").asInt());
        payload.put("updatedAt", raw.path("updatedAt").asText());
        Long id = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenant, payload.toString());
        webhookJob.process(id, tenant);
        return id;
    }

    private String eventNote(long id) {
        return jdbc.queryForObject("SELECT error FROM webhook_events WHERE id = ?", String.class, id);
    }

    private int unlinked(UUID tenant, String tracking) {
        return count("SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ?",
            tenant, tracking);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
