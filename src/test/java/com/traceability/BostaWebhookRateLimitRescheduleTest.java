package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.security.EncryptionService;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A Bosta rate limit reschedules a webhook event, never fails it (2026-10-04, V135).
 *
 *   rs1 Bosta 429s the verify-by-fetch: the event stays pending, rate_limit_retries = 1, and process()
 *       is scheduled again no earlier than the retry-after (300 s); when it runs and Bosta answers, the
 *       event is processed and the delivery links — it recovers
 *   rs2 the shared limiter's own refusal (a wait over max-wait) is handled the same way
 *   rs3 bounded: after rate-limit-max-retries reschedules the event is marked failed, naming the cause;
 *       the delay backs off (60 s × 2^(n−1), at least the retry-after)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaWebhookRateLimitRescheduleTest {

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
        r.add("bosta.webhook.rate-limit-max-retries",   () -> "2");
        r.add("bosta.webhook.rate-limit-jitter-seconds", () -> "0");
    }

    @Autowired JdbcTemplate      jdbc;
    @Autowired ObjectMapper      mapper;
    @Autowired EncryptionService encryptionService;
    @Autowired BostaWebhookJob   webhookJob;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    private UUID tenantId;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        reset(bostaGateway, jobScheduler);
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RescheduleTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            storeId, tenantId, "rs-" + tenantId + ".myshopify.com");
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'h', 'active')", tenantId, encryptionService.encrypt("rs-key"));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", tenantId);
    }

    @Test
    void rs1_bosta429_eventStaysPending_rescheduledAfterRetryAfter_thenRecoversAndLinks() {
        String tn = "9214743303";
        order("BRK-44898-EG");
        long id = pendingEvent(tn);
        when(bostaGateway.fetchDelivery(anyString(), eq(tn)))
            .thenThrow(new BostaRateLimitException(300))
            .thenReturn(send(tn, "BRK-44898-EG"));

        Instant before = Instant.now();
        webhookJob.process(id, tenantId);

        Map<String, Object> row = event(id);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(((Number) row.get("rate_limit_retries")).intValue()).isEqualTo(1);
        assertThat((String) row.get("error")).startsWith("rate_limited: retry 1 of 2");
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(jobScheduler, times(1)).schedule(at.capture(), any(JobLambda.class));
        assertThat(at.getValue()).as("not before the retry-after").isAfterOrEqualTo(before.plusSeconds(300));

        webhookJob.process(id, tenantId);   // what JobRunr runs at that time

        assertThat(event(id).get("status")).isEqualTo("processed");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id = s.order_id " +
            "WHERE s.tenant_id = ? AND s.tracking_number = ? AND o.number = 'BRK-44898-EG'",
            Integer.class, tenantId, tn)).as("the delivery linked").isEqualTo(1);
    }

    @Test
    void rs2_limiterRefusal_isRescheduledTheSameWay() {
        String tn = "5829813860";
        long id = pendingEvent(tn);
        when(bostaGateway.fetchDelivery(anyString(), eq(tn))).thenThrow(new BostaRateLimitException(45));

        webhookJob.process(id, tenantId);

        assertThat(event(id).get("status")).isEqualTo("pending");
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(jobScheduler).schedule(at.capture(), any(JobLambda.class));
        assertThat(at.getValue()).as("first backoff step is 60 s, above the 45 s left").isAfter(Instant.now().plusSeconds(55));
    }

    @Test
    void rs3_bounded_failedAfterMaxRetries_withBackoff() {
        String tn = "2251220237";
        long id = pendingEvent(tn);
        when(bostaGateway.fetchDelivery(anyString(), eq(tn))).thenThrow(new BostaRateLimitException(30));

        webhookJob.process(id, tenantId);   // reschedule 1: max(30, 60)  = 60 s
        webhookJob.process(id, tenantId);   // reschedule 2: max(30, 120) = 120 s
        webhookJob.process(id, tenantId);   // 3rd rate limit > max 2 → failed

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(jobScheduler, times(2)).schedule(at.capture(), any(JobLambda.class));
        long gap = at.getAllValues().get(1).getEpochSecond() - at.getAllValues().get(0).getEpochSecond();
        assertThat(gap).as("the second delay is longer (backoff)").isGreaterThanOrEqualTo(55);
        Map<String, Object> row = event(id);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat((String) row.get("error")).contains("Bosta rate limit — gave up after 2 reschedules");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long pendingEvent(String tn) {
        String payload = "{\"trackingNumber\":\"" + tn + "\",\"state\":10,\"type\":\"SEND\",\"updatedAt\":\"2026-10-04T00:28:30.000Z\"}";
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, external_event_id) " +
            "VALUES ('bosta_poll_discovery'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now(), ?) RETURNING id",
            Long.class, tenantId, payload, BostaWebhookJob.sha256(tn + ":10:2026-10-04T00:28:30.000Z"));
    }

    private Map<String, Object> event(long id) {
        return jdbc.queryForMap("SELECT status::text AS status, error, rate_limit_retries, rate_limited_until " +
            "FROM webhook_events WHERE id = ?", id);
    }

    private void order(String number) {
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status)", tenantId, storeId, "gid://shopify/Order/" + UUID.randomUUID(), number);
    }

    private BostaDelivery send(String tracking, String reference) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tracking);
        raw.put("businessReference", reference);
        raw.put("updatedAt", "2026-10-04T00:28:30.000Z");
        raw.put("createdAt", Timestamp.from(Instant.now()).toInstant().toString());
        raw.putObject("type").put("code", 10).put("value", "Send");
        raw.putObject("state").put("code", 10).put("value", "Pickup requested");
        return new BostaDelivery(tracking, 10, "SEND", 0, reference, null, raw);
    }
}
