package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ExceptionService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Discovery marker hardening (2026-10-02, V128 retry list): a delivery whose per-item fetch
 * fails is never silently skipped while the high-water mark moves past it.
 *
 *   dr1 — a failed fetch in the middle of a page is retried on the next run, even though the
 *         mark has moved past it (the item now sits below the mark)
 *   dr2 — after N counted failures the bosta_discovery_failed exception is raised, the mark
 *         has moved on, and the entry is STILL retried — at the slow interval, not every run
 *   dr3 — a 429 doesn't lose the item: the cycle stops, the mark is held, the item is
 *         retried next run (and the 429 doesn't count toward escalation)
 *   dr4 — a success during slow retry ingests the delivery, it links, and the exception clears
 *   dr5 — 48 h after the first failure retries stop; the exception stays open
 *
 * Realistic fixtures: bare numeric tracking numbers, BROEK-style references. Since discovery reads the
 * v2 search (2026-10-03) the failing items' list entries carry no updatedAt, so discovery falls back to
 * one fetch for them — the path that can fail; complete items are ingested without a fetch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaDiscoveryRetryTest {

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
        r.add("bosta.poll.inter-fetch-delay-ms",            () -> "0");
        r.add("bosta.backfill.page-size",                   () -> "50");
        r.add("bosta.poll.discovery-max-items-per-cycle",   () -> "150");
        r.add("bosta.poll.discovery-max-item-failures",     () -> "3");
    }

    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired EncryptionService     encryptionService;
    @Autowired BostaDiscoveryPollJob discoveryPollJob;
    @Autowired ExceptionService      exceptionService;
    @Autowired BostaWebhookJob       webhookJob;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  BostaV2Client         bostaV2;
    @MockBean  JobScheduler          jobScheduler;

    private UUID tenantId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'DiscoveryRetryTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')",
                    storeId, tenantId, "dr-" + tenantId + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', ?, 'test-hash', 'active')",
                    tenantId, encryptionService.encrypt("dr-key"));
        reset(bostaGateway);
        reset(bostaV2);
    }

    @AfterEach
    void tearDown() {
        // Deactivate so the next test's discoverAll() only walks its own tenant.
        jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", tenantId);
    }

    // ── dr1 ────────────────────────────────────────────────────────────────────

    @Test
    void dr1_failedFetchMidPage_isRetriedNextRun_evenOnceTheMarkHasPassedIt() {
        String a = "5829813860";   // newest: ingests fine, then links
        String b = "9432163061";   // mid-page: fetch fails (5xx) on the first run
        String c = "2251220237";   // older: ingests fine

        when(bostaV2.searchDeliveriesPage(anyString(), eq(1), anyInt(), anyString())).thenReturn(BostaSearchItems.page(
            BostaSearchItems.item(a, 10, T0.plusSeconds(3)),
            BostaSearchItems.needsFetch(b, 10, T0.plusSeconds(2)),
            BostaSearchItems.item(c, 10, T0.plusSeconds(1))));
        when(bostaV2.searchDeliveriesPage(anyString(), eq(2), anyInt(), anyString())).thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq(a))).thenReturn(send(a, "BRK-44871-EG"));
        when(bostaGateway.fetchDelivery(anyString(), eq(c))).thenReturn(send(c, "BRK-44842-EG"));
        when(bostaGateway.fetchDelivery(anyString(), eq(b)))
            .thenThrow(new BostaTransientException("Bosta 5xx fetching delivery " + b))
            .thenReturn(send(b, "BRK-44841-EG"));

        discoveryPollJob.discoverAll();
        assertThat(eventCount(b)).as("no event for the failed fetch yet").isZero();

        // A and C link (their webhook jobs ran); the mark moved to A, so B sits below the mark.
        linkShipment(a);
        linkShipment(c);
        assertThat(markAt()).as("V132: a complete walk advances the mark to the newest creation time")
            .isEqualTo(T0.plusSeconds(3));

        discoveryPollJob.discoverAll();

        verify(bostaGateway, times(2)).fetchDelivery(anyString(), eq(b));
        assertThat(eventCount(b)).as("the retried delivery is ingested on the next run").isEqualTo(1);
        assertThat(failureRows(b)).as("a successful retry clears the retry-list row").isZero();
    }

    // ── dr2 ────────────────────────────────────────────────────────────────────

    @Test
    void dr2_afterNFailures_exceptionRaised_andStillRetriedAtTheSlowInterval() {
        String top = "8948149267";
        String bad = "563601351";   // bare 9-digit tracking number

        when(bostaV2.searchDeliveriesPage(anyString(), eq(1), anyInt(), anyString())).thenReturn(BostaSearchItems.page(
            BostaSearchItems.item(top, 10, T0.plusSeconds(2)),
            BostaSearchItems.needsFetch(bad, 10, T0.plusSeconds(1))));
        when(bostaV2.searchDeliveriesPage(anyString(), eq(2), anyInt(), anyString())).thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq(top))).thenReturn(send(top, "BRK-44866-EG"));
        when(bostaGateway.fetchDelivery(anyString(), eq(bad)))
            .thenThrow(new DeliveryNotFoundException(bad));

        discoveryPollJob.discoverAll();
        assertThat(markAt()).as("the mark moves on even though one item failed").isEqualTo(T0.plusSeconds(2));
        discoveryPollJob.discoverAll();
        discoveryPollJob.discoverAll();   // 3rd counted failure → escalated

        Map<String, Object> row = failureRow(bad);
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
        assertThat(row.get("escalated_at")).isNotNull();
        assertThat(row.get("retries_stopped_at")).isNull();
        assertThat(openDiscoveryExceptions()).singleElement().satisfies(e -> {
            assertThat(e.get("tracking_number")).isEqualTo(bad);
            assertThat(e.get("severity")).isEqualTo("HIGH");
        });

        // Within the slow interval: not fetched.
        discoveryPollJob.discoverAll();
        verify(bostaGateway, times(3)).fetchDelivery(anyString(), eq(bad));

        // Once the interval has passed: retried again, still escalated, next retry pushed out.
        jdbc.update("UPDATE bosta_discovery_failures SET next_retry_at = now() - INTERVAL '1 minute' " +
            "WHERE tenant_id = ? AND tracking_number = ?", tenantId, bad);
        discoveryPollJob.discoverAll();
        verify(bostaGateway, times(4)).fetchDelivery(anyString(), eq(bad));
        Boolean pushedOut = jdbc.queryForObject(
            "SELECT next_retry_at > now() + INTERVAL '50 minutes' FROM bosta_discovery_failures " +
            "WHERE tenant_id = ? AND tracking_number = ?", Boolean.class, tenantId, bad);
        assertThat(pushedOut).isTrue();
        assertThat(openDiscoveryExceptions()).hasSize(1);
    }

    // ── dr4 ────────────────────────────────────────────────────────────────────

    @Test
    void dr4_successDuringSlowRetry_linksDelivery_andClearsException() {
        String tn = "9214743303";
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
            "VALUES (?, ?, 'gid://shopify/Order/18914721661207', 'BRK-44898-EG', 'new'::order_status)",
            tenantId, storeId);
        escalatedRow(tn, "now() - INTERVAL '5 hours'", "now() - INTERVAL '1 minute'");
        assertThat(openDiscoveryExceptions()).hasSize(1);

        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq(tn))).thenReturn(send(tn, "BRK-44898-EG"));

        discoveryPollJob.discoverAll();

        assertThat(failureRows(tn)).isZero();
        Long eventId = jdbc.queryForObject(
            "SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ?",
            Long.class, tenantId, tn);
        webhookJob.process(eventId, tenantId);   // what JobRunr runs next
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id = s.order_id " +
            "WHERE s.tenant_id = ? AND s.tracking_number = ? AND o.number = 'BRK-44898-EG'",
            Integer.class, tenantId, tn)).isEqualTo(1);
        assertThat(openDiscoveryExceptions()).isEmpty();
    }

    // ── dr5 ────────────────────────────────────────────────────────────────────

    @Test
    void dr5_after48h_retriesStop_exceptionStaysOpen() {
        String tn = "5360015612";
        escalatedRow(tn, "now() - INTERVAL '49 hours'", "now() - INTERVAL '1 minute'");
        when(bostaV2.searchDeliveriesPage(anyString(), anyInt(), anyInt(), anyString())).thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq(tn))).thenThrow(new DeliveryNotFoundException(tn));

        discoveryPollJob.discoverAll();
        discoveryPollJob.discoverAll();

        verify(bostaGateway, never()).fetchDelivery(anyString(), eq(tn));
        assertThat(failureRow(tn).get("retries_stopped_at")).isNotNull();
        assertThat(openDiscoveryExceptions()).singleElement()
            .satisfies(e -> assertThat(e.get("tracking_number")).isEqualTo(tn));
    }

    // ── dr3 ────────────────────────────────────────────────────────────────────

    @Test
    void dr3_rateLimitedItem_isNotLost_cycleStops_markHeld_retriedNextRun() {
        String n1 = "4069420256";
        String x  = "209760032";    // 429 on the first run
        String n2 = "6212045934";

        when(bostaV2.searchDeliveriesPage(anyString(), eq(1), anyInt(), anyString())).thenReturn(BostaSearchItems.page(
            BostaSearchItems.item(n1, 10, T0.plusSeconds(3)),
            BostaSearchItems.needsFetch(x, 10, T0.plusSeconds(2)),
            BostaSearchItems.item(n2, 10, T0.plusSeconds(1))));
        when(bostaV2.searchDeliveriesPage(anyString(), eq(2), anyInt(), anyString())).thenReturn(List.of());
        when(bostaGateway.fetchDelivery(anyString(), eq(n1))).thenReturn(send(n1, "BRK-44881-EG"));
        when(bostaGateway.fetchDelivery(anyString(), eq(n2))).thenReturn(send(n2, "BRK-44890-EG"));
        when(bostaGateway.fetchDelivery(anyString(), eq(x)))
            .thenThrow(new BostaRateLimitException(30))
            .thenReturn(send(x, "BRK-44851-EG"));

        discoveryPollJob.discoverAll();

        assertThat(markAt()).as("a 429 holds the mark (still the first run's seed, before the batch)")
            .isBefore(T0);
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq(n2));
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT attempts, rate_limited_count FROM bosta_discovery_failures WHERE tenant_id = ? AND tracking_number = ?",
            tenantId, x);
        assertThat(((Number) row.get("attempts")).intValue()).as("a 429 doesn't count toward giving up").isZero();
        assertThat(((Number) row.get("rate_limited_count")).intValue()).isEqualTo(1);

        // n1 links before the next run.
        linkShipment(n1);

        discoveryPollJob.discoverAll();

        verify(bostaGateway, times(2)).fetchDelivery(anyString(), eq(x));
        assertThat(eventCount(x)).isEqualTo(1);
        assertThat(eventCount(n2)).isEqualTo(1);
        assertThat(failureRows(x)).isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private BostaDelivery send(String tracking, String reference) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tracking);
        raw.put("businessReference", reference);
        raw.put("updatedAt", "2026-10-01T15:54:17.000Z");
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 10).put("value", "Pickup requested");
        return new BostaDelivery(tracking, 10, "SEND", 0, reference, null, raw);
    }

    private void linkShipment(String tracking) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/" + tracking, "ORD-" + tracking);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state)",
            tenantId, orderId, tracking);
    }

    /** A batch created "now-ish": newer than the first paged run's seed (now − 7 days). */
    private static final java.time.Instant T0 = java.time.Instant.now().minus(java.time.Duration.ofHours(1))
        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);   // v2 creationTimestamp is epoch ms

    private java.time.Instant markAt() {
        java.sql.Timestamp ts = jdbc.queryForObject(
            "SELECT discovery_mark_at FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta'",
            java.sql.Timestamp.class, tenantId);
        return ts == null ? null : ts.toInstant();
    }

    private String mark() {
        return jdbc.queryForObject(
            "SELECT discovery_high_water_tracking FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta'",
            String.class, tenantId);
    }

    private int eventCount(String tracking) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "AND source::text = 'bosta_poll_discovery'",
            Integer.class, tenantId, tracking);
    }

    private void escalatedRow(String tracking, String firstFailedAt, String nextRetryAt) {
        jdbc.update("INSERT INTO bosta_discovery_failures (tenant_id, tracking_number, attempts, " +
            "first_failed_at, last_failed_at, last_error, escalated_at, next_retry_at) " +
            "VALUES (?, ?, 10, " + firstFailedAt + ", now(), 'DeliveryNotFoundException', " +
            firstFailedAt + " + INTERVAL '20 minutes', " + nextRetryAt + ")", tenantId, tracking);
    }

    private Map<String, Object> failureRow(String tracking) {
        return jdbc.queryForMap(
            "SELECT attempts, escalated_at, next_retry_at, retries_stopped_at FROM bosta_discovery_failures " +
            "WHERE tenant_id = ? AND tracking_number = ?", tenantId, tracking);
    }

    private List<Map<String, Object>> openDiscoveryExceptions() {
        return TenantContext.runAs(tenantId, () -> exceptionService.detectAllOpen()).stream()
            .filter(e -> "bosta_discovery_failed".equals(e.get("type"))).toList();
    }

    private int failureRows(String tracking) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM bosta_discovery_failures WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, tenantId, tracking);
    }
}
