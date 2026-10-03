package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.security.EncryptionService;
import org.jobrunr.jobs.lambdas.JobLambda;
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

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * One-time re-process of webhook events that failed on a Bosta rate limit (2026-10-04).
 *
 *   rp1 dry run writes nothing (every webhook_events row identical before / after, no Bosta call) and
 *       reports WOULD_REPROCESS / SKIP superseded per row, with tenant + tracking number
 *   rp2 apply runs the failed event through the normal path: it is processed and the delivery links
 *       (REPROCESSED); a superseded one is skipped; a failure that isn't a rate limit is never touched
 *   rp3 idempotent: a second apply finds nothing to do and writes nothing
 *   rp4 cross-tenant isolation: tenant A's run never reads or touches tenant B's failed events
 *   rp5 the ops startup trigger enqueues the run (BOSTA_REPROCESS_RATE_LIMITED_ON_STARTUP)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaRateLimitedReprocessTest {

    static final String TRIGGER_TENANT = "0d1c0c38-5a0b-4f7e-9a39-3f1e2a5b7c11";

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
        r.add("bosta.reprocess-rate-limited.on-startup", () -> TRIGGER_TENANT);
    }

    @Autowired JdbcTemplate                     jdbc;
    @Autowired ObjectMapper                     mapper;
    @Autowired EncryptionService                encryptionService;
    @Autowired BostaRateLimitedReprocessService reprocess;
    @Autowired BostaVisibilityCheckTrigger      trigger;
    @MockBean  BostaGateway                     bostaGateway;
    @MockBean  JobScheduler                     jobScheduler;

    private UUID a, b, storeA;

    @BeforeEach
    void setUp() {
        reset(bostaGateway, jobScheduler);
        a = tenant("ReprocessA");
        b = tenant("ReprocessB");
        storeA = jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ?", UUID.class, a);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id IN (?, ?)", a, b);
    }

    @Test
    void rp1_dryRun_writesNothing_andReportsPerRow() {
        Fixture f = fixture();
        List<Map<String, Object>> before = snapshot();

        List<BostaRateLimitedReprocessService.Row> rows = reprocess.run(a, false);

        assertThat(snapshot()).as("dry run writes nothing").isEqualTo(before);
        verifyNoInteractions(bostaGateway);
        assertThat(rows).extracting(BostaRateLimitedReprocessService.Row::trackingNumber,
                BostaRateLimitedReprocessService.Row::verdict)
            .containsExactly(tuple(f.failed, "WOULD_REPROCESS"), tuple(f.superseded, "SKIP"));
        assertThat(rows).allSatisfy(r -> assertThat(r.tenant()).isEqualTo("ReprocessA"));
        assertThat(rows.get(1).detail()).startsWith("superseded by event");
    }

    @Test
    void rp2_apply_recoversThroughTheNormalPath() {
        Fixture f = fixture();
        when(bostaGateway.fetchDelivery(anyString(), eq(f.failed))).thenReturn(send(f.failed, "BRK-44898-EG"));

        List<BostaRateLimitedReprocessService.Row> rows = reprocess.run(a, true);

        assertThat(rows).extracting(BostaRateLimitedReprocessService.Row::trackingNumber,
                BostaRateLimitedReprocessService.Row::verdict)
            .containsExactly(tuple(f.failed, "REPROCESSED"), tuple(f.superseded, "SKIP"));
        assertThat(status(f.failedId)).isEqualTo("processed");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id = s.order_id " +
            "WHERE s.tenant_id = ? AND s.tracking_number = ? AND o.number = 'BRK-44898-EG'",
            Integer.class, a, f.failed)).as("linked").isEqualTo(1);
        assertThat(status(f.supersededId)).as("superseded left as it was").isEqualTo("failed");
        assertThat(status(f.otherFailureId)).as("not a rate limit — untouched").isEqualTo("failed");
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq(f.superseded));
    }

    @Test
    void rp3_secondApply_isIdempotent() {
        Fixture f = fixture();
        when(bostaGateway.fetchDelivery(anyString(), eq(f.failed))).thenReturn(send(f.failed, "BRK-44898-EG"));
        reprocess.run(a, true);
        List<Map<String, Object>> before = snapshot();
        reset(bostaGateway);

        List<BostaRateLimitedReprocessService.Row> rows = reprocess.run(a, true);

        assertThat(rows).extracting(BostaRateLimitedReprocessService.Row::verdict).containsOnly("SKIP");
        assertThat(snapshot()).isEqualTo(before);
        verifyNoInteractions(bostaGateway);
    }

    @Test
    void rp4_crossTenantIsolation() {
        Fixture f = fixture();
        when(bostaGateway.fetchDelivery(anyString(), anyString())).thenReturn(send(f.failed, "BRK-44898-EG"));

        List<BostaRateLimitedReprocessService.Row> rows = reprocess.run(a, true);

        assertThat(rows).extracting(BostaRateLimitedReprocessService.Row::trackingNumber).doesNotContain(f.otherTenant);
        assertThat(status(f.otherTenantId)).as("tenant B's failed event untouched").isEqualTo("failed");
        verify(bostaGateway, never()).fetchDelivery(anyString(), eq(f.otherTenant));
        assertThat(reprocess.run(b, false)).extracting(BostaRateLimitedReprocessService.Row::trackingNumber)
            .containsExactly(f.otherTenant);
    }

    @Test
    void rp5_startupTrigger_enqueuesTheRun() {
        trigger.onReady();
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private record Fixture(String failed, long failedId, String superseded, long supersededId,
                           long otherFailureId, String otherTenant, long otherTenantId) {}

    private Fixture fixture() {
        String suffix = String.valueOf(Math.abs(new Random().nextInt(900_000)) + 100_000);
        String failed = "92" + suffix, superseded = "93" + suffix, other = "94" + suffix, otherTenant = "95" + suffix;
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
            "VALUES (?, ?, ?, 'BRK-44898-EG', 'new'::order_status)", a, storeA, "gid://shopify/Order/" + suffix);
        long failedId     = event(a, failed, "failed", "Bosta fetch error: Bosta rate limit — retry after 300s", 1);
        long supersededId = event(a, superseded, "failed", "Bosta fetch error: Bosta rate limit — retry after 299s", 1);
        event(a, superseded, "processed", "unlinked: no match", 2);
        long otherId      = event(a, other, "failed", "Delivery not found: " + other, 1);
        long otherTenantId = event(b, otherTenant, "failed", "Bosta fetch error: Bosta rate limit — retry after 300s", 1);
        return new Fixture(failed, failedId, superseded, supersededId, otherId, otherTenant, otherTenantId);
    }

    private long event(UUID tenant, String tn, String status, String error, int state) {
        String upd = "2026-10-04T00:28:3" + state + ".000Z";
        String payload = "{\"trackingNumber\":\"" + tn + "\",\"state\":" + (state == 1 ? 10 : 24)
            + ",\"type\":\"SEND\",\"updatedAt\":\"" + upd + "\"}";
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, processed_at, error, external_event_id) " +
            "VALUES ('bosta_poll_discovery'::webhook_source, ?, 'delivery_update', ?::jsonb, ?::webhook_status, now(), now(), ?, ?) RETURNING id",
            Long.class, tenant, payload, status, error, BostaWebhookJob.sha256(tn + ":" + (state == 1 ? 10 : 24) + ":" + upd));
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            UUID.randomUUID(), id, name.toLowerCase() + "-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", id, encryptionService.encrypt("key-" + name));
        return id;
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("SELECT id, status::text AS status, error, processed_at, rate_limit_retries, " +
            "rate_limited_until, external_event_id FROM webhook_events WHERE tenant_id IN (?, ?) ORDER BY id", a, b);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status::text FROM webhook_events WHERE id = ?", String.class, id);
    }

    private BostaDelivery send(String tracking, String reference) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tracking);
        raw.put("businessReference", reference);
        raw.put("updatedAt", "2026-10-04T00:40:00.000Z");
        raw.put("createdAt", Instant.now().toString());
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 10).put("value", "Pickup requested");
        return new BostaDelivery(tracking, 10, "SEND", 0, reference, null, raw);
    }

    private static org.assertj.core.groups.Tuple tuple(Object... v) {
        return org.assertj.core.groups.Tuple.tuple(v);
    }
}
