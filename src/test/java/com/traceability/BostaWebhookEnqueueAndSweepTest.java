package com.traceability;

import com.traceability.integrations.bosta.BostaWebhookPendingSweeper;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Bosta webhook endpoint + pending sweeper (2026-11-05, V205).
 *
 *   bd1 the JobRunr enqueue hangs: 200 on the 2 s guard (not after 8 s), the row stored 'pending'
 *   bd2 the enqueue throws: 200 (was 500), the row stored 'pending'
 *   bd3 normal path unchanged: 200 and exactly one enqueue
 *   sw1 the sweeper re-enqueues exactly the rows pending > 10 min; not: 5-min-old, processed, failed, older than
 *       48 h, waiting on a rate-limit reschedule, already swept 3 times, leased in the last 30 min
 *   sw2 a second sweep right after takes nothing (30-min lease); the claim counts the sweep
 *   sw3 a claimed row whose enqueue throws stays leased (no hot loop) and the sweep carries on with the next
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaWebhookEnqueueAndSweepTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.newContainer()
        .withDatabaseName("traceability_test").withUsername("postgres").withPassword("postgres");

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

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired BostaWebhookPendingSweeper sweeper;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  EmailGateway emailGateway;

    final UUID tenant = UUID.randomUUID();
    final String rawSecret = "bosta-sweep-secret-" + UUID.randomUUID();
    RestTemplate rest;

    @BeforeAll
    void setup() throws Exception {
        rest = new RestTemplate();
        rest.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
        });
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'BostaSweepTenant')", tenant);
        String hash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(rawSecret.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', 'x', ?, 'active')", tenant, hash);
    }

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(jobScheduler);
        jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", tenant);
    }

    ResponseEntity<Void> post(String tracking) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + rawSecret);
        String body = "{\"trackingNumber\":\"" + tracking + "\",\"state\":24,\"updatedAt\":\"2026-11-05T10:00:00Z\"}";
        return rest.exchange("http://localhost:" + port + "/api/v1/webhooks/bosta", HttpMethod.POST,
            new HttpEntity<>(body, h), Void.class);
    }

    String statusOf(String tracking) {
        return jdbc.queryForObject("SELECT status::text FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ?",
            String.class, tenant, tracking);
    }

    @Test
    void bd1_enqueueHangs_200OnTheGuard_rowPending() {
        when(jobScheduler.enqueue(any(JobLambda.class))).thenAnswer(inv -> { Thread.sleep(8_000); return null; });
        long t0 = System.nanoTime();
        ResponseEntity<Void> r = post("1111111111");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(ms).isLessThan(3_500);
        assertThat(statusOf("1111111111")).isEqualTo("pending");
    }

    @Test
    void bd2_enqueueThrows_200_rowPending() {
        when(jobScheduler.enqueue(any(JobLambda.class)))
            .thenThrow(new IllegalStateException("Connection is not available, request timed out after 3000ms"));
        ResponseEntity<Void> r = post("2222222222");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf("2222222222")).isEqualTo("pending");
    }

    @Test
    void bd3_normalPath_oneEnqueue() {
        ResponseEntity<Void> r = post("3333333333");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
    }

    long row(String age, String status, int sweeps, String leasedAgo, String rateLimitedUntil) {
        return jdbc.queryForObject("""
            INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at,
                sweep_count, sweep_enqueued_at, rate_limited_until)
            VALUES ('bosta', ?, 'delivery_update', '{}'::jsonb, ?::webhook_status, now() - ?::interval, ?,
                    CASE WHEN ?::text IS NULL THEN NULL ELSE now() - ?::interval END,
                    CASE WHEN ?::text IS NULL THEN NULL ELSE now() + ?::interval END)
            RETURNING id
            """, Long.class, tenant, status, age, sweeps, leasedAgo, leasedAgo, rateLimitedUntil, rateLimitedUntil);
    }

    @Test
    void sw1_sweepsOnlyRowsLeftPending() {
        long due       = row("11 minutes", "pending",   0, null, null);
        long dueOldRl  = row("40 minutes", "pending",   1, "31 minutes", "-15 minutes");   // rate-limit long past
        row("5 minutes",  "pending",   0, null, null);            // too fresh
        row("11 minutes", "processed", 0, null, null);
        row("11 minutes", "failed",    0, null, null);
        row("49 hours",   "pending",   0, null, null);            // outside the window
        row("20 minutes", "pending",   0, null, "5 minutes");     // waiting on its rate-limit reschedule
        row("20 minutes", "pending",   0, null, "-5 minutes");    // reschedule just due — JobRunr's turn
        row("2 hours",    "pending",   3, "40 minutes", null);    // sweeps exhausted
        row("2 hours",    "pending",   1, "10 minutes", null);    // leased

        sweeper.sweep();

        verify(jobScheduler, times(2)).enqueue(any(JobLambda.class));
        List<Long> swept = jdbc.queryForList(
            "SELECT id FROM webhook_events WHERE tenant_id = ? AND sweep_enqueued_at > now() - interval '1 minute' ORDER BY id",
            Long.class, tenant);
        assertThat(swept).containsExactly(due, dueOldRl);
        assertThat(jdbc.queryForObject("SELECT sweep_count FROM webhook_events WHERE id = ?", Integer.class, due)).isEqualTo(1);
    }

    @Test
    void sw2_secondSweep_takesNothing() {
        row("11 minutes", "pending", 0, null, null);
        sweeper.sweep();
        sweeper.sweep();
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
    }

    @Test
    void sw3_enqueueThrows_rowStaysLeased_sweepContinues() {
        long a = row("30 minutes", "pending", 0, null, null);
        long b = row("20 minutes", "pending", 0, null, null);
        when(jobScheduler.enqueue(any(JobLambda.class)))
            .thenThrow(new IllegalStateException("owner pool exhausted"))
            .thenReturn(null);
        sweeper.sweep();
        verify(jobScheduler, times(2)).enqueue(any(JobLambda.class));
        assertThat(jdbc.queryForList("SELECT id FROM webhook_events WHERE tenant_id = ? AND sweep_enqueued_at IS NOT NULL ORDER BY id",
            Long.class, tenant)).containsExactly(a, b);
        org.mockito.Mockito.reset(jobScheduler);
        sweeper.sweep();
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
    }
}
