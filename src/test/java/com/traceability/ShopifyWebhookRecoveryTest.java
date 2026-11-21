package com.traceability;

import com.traceability.integrations.shopify.*;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Incident 2026-11-21 — Shopify webhook recovery, Part 3 (a) and (b).
 *
 *   ra1 a transient DB failure (full pool) during processing is recorded like any failure — process_error
 *       "transient DB failure: …", retry_count 1, next_retry_at ≈ 1 min — and the job ends normally (no JobRunr retry);
 *       once due the sweeper re-enqueues it and the retry applies it
 *   rb1 an event unprocessed for > 3 min with no error and no live job is re-enqueued (once — leased); a 2-min-old one
 *       is not
 *   rb2 … but not while a JobRunr job for it is still live (ENQUEUED / SCHEDULED / PROCESSING); a FAILED / SUCCEEDED
 *       job is not live → re-enqueued (today's lost events: their job had FAILED)
 *   rb3 sweepNow() (the watchdog's entry) returns how many it re-enqueued
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyWebhookRecoveryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    @Autowired JdbcTemplate               jdbc;
    @Autowired ShopifyWebhookProcessorJob processor;
    @Autowired ShopifyWebhookRetrySweeper sweeper;
    @SpyBean   ShopifySyncService         syncService;
    @MockBean  JobScheduler               jobScheduler;
    @MockBean  ShopifyGateway             shopifyGateway;
    @MockBean  EmailGateway               emailGateway;

    UUID tenant;
    String shop;

    @BeforeEach
    void setUp() {
        reset(jobScheduler, syncService);
        jdbc.update("UPDATE shopify_webhook_events SET processed_at = now() WHERE processed_at IS NULL");   // isolate tests
        jdbc.update("DELETE FROM jobrunr_jobs WHERE jobsignature LIKE '%ShopifyWebhookProcessorJob%'");
        tenant = UUID.randomUUID();
        shop = "rc-" + tenant.toString().substring(0, 8) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RecoveryTenant')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, platform, status) VALUES (?, ?, ?, 'shopify', 'connected')",
            UUID.randomUUID(), tenant, shop);
    }

    @Test
    void ra1_transientFailure_recordedForTheSweeper_thenRetriedAndApplied() {
        UUID e = event(order("#R1", 7001), 0);
        doThrow(new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                new SQLTransientConnectionException("app-pool - Connection is not available, request timed out after 30000ms")))
            .when(syncService).ingestOrderWebhook(any(), any(), any());

        assertThatCode(() -> processor.process(e, tenant)).as("recorded → no JobRunr retry").doesNotThrowAnyException();

        Map<String, Object> r = row(e);
        assertThat((String) r.get("process_error")).startsWith("transient DB failure: CannotCreateTransactionException");
        assertThat(r.get("processed_at")).isNull();
        assertThat(r.get("retry_count")).isEqualTo(1);
        assertThat(bool("SELECT next_retry_at BETWEEN now() + INTERVAL '50 seconds' AND now() + INTERVAL '70 seconds' " +
            "FROM shopify_webhook_events WHERE id = ?", e)).isTrue();

        reset(syncService);
        jdbc.update("UPDATE shopify_webhook_events SET next_retry_at = now() - INTERVAL '1 second' WHERE id = ?", e);
        assertThat(sweeper.sweepNow()).isEqualTo(1);
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
        processor.process(e, tenant);   // what the enqueued job runs
        assertThat(row(e).get("processed_at")).isNotNull();
        assertThat(row(e).get("process_error")).isNull();
    }

    @Test
    void rb1_noErrorEventOlderThan3Min_reEnqueuedOnce() {
        UUID stale = event(order("#R2", 7002), -4 * 60);
        UUID fresh = event(order("#R3", 7003), -2 * 60);

        assertThat(sweeper.sweepNow()).isEqualTo(1);
        assertThat(sweeper.sweepNow()).as("leased").isZero();
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
        assertThat(row(stale).get("retry_count")).isEqualTo(1);
        assertThat(row(fresh).get("retry_count")).isEqualTo(0);
    }

    @Test
    void rb2_liveJob_notReEnqueued_finishedJob_is() {
        UUID e = event(order("#R4", 7004), -10 * 60);
        String jobId = job(e, "ENQUEUED");
        assertThat(sweeper.sweepNow()).isZero();
        jdbc.update("UPDATE jobrunr_jobs SET state = 'PROCESSING' WHERE id = ?", jobId);
        assertThat(sweeper.sweepNow()).isZero();
        jdbc.update("UPDATE jobrunr_jobs SET state = 'SCHEDULED' WHERE id = ?", jobId);
        assertThat(sweeper.sweepNow()).isZero();
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
        assertThat(row(e).get("retry_count")).isEqualTo(0);

        jdbc.update("UPDATE jobrunr_jobs SET state = 'FAILED' WHERE id = ?", jobId);   // retries ran out (11-21)
        assertThat(sweeper.sweepNow()).isEqualTo(1);
        verify(jobScheduler, times(1)).enqueue(any(JobLambda.class));
    }

    @Test
    void rb3_anotherEventsLiveJob_doesNotHideThisOne() {
        UUID e = event(order("#R5", 7005), -10 * 60);
        job(UUID.randomUUID(), "ENQUEUED");
        assertThat(sweeper.sweepNow()).isEqualTo(1);
        assertThat(row(e).get("retry_count")).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A processor job row shaped like JobRunr's: the event id is its first argument in the JSON. */
    String job(UUID eventId, String state) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO jobrunr_jobs (id, version, jobAsJson, jobSignature, state, createdAt, updatedAt, scheduledAt)
            VALUES (?, 0, ?, 'com.traceability.integrations.shopify.ShopifyWebhookProcessorJob.process(java.util.UUID,java.util.UUID)',
                    ?, now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')
            """, id, "{\"jobDetails\":{\"className\":\"com.traceability.integrations.shopify.ShopifyWebhookProcessorJob\"," +
                "\"methodName\":\"process\",\"jobParameters\":[{\"className\":\"java.util.UUID\",\"object\":\"" + eventId + "\"}," +
                "{\"className\":\"java.util.UUID\",\"object\":\"" + tenant + "\"}]}}", state);
        return id;
    }

    String order(String name, long id) {
        return "{\"id\":" + id + ",\"admin_graphql_api_id\":\"gid://shopify/Order/" + id + Math.abs(tenant.hashCode()) +
            "\",\"name\":\"" + name + "\",\"created_at\":\"2026-11-21T08:00:00+02:00\",\"updated_at\":\"2026-11-21T09:00:00+02:00\"" +
            ",\"financial_status\":\"pending\",\"current_total_price\":\"100.00\",\"payment_gateway_names\":[\"Cash on Delivery (COD)\"]" +
            ",\"line_items\":[]}";
    }

    UUID event(String payload, int receivedOffsetSeconds) {
        return jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw, received_at) " +
            "VALUES (?, 'orders/create', ?, ?, ?::jsonb, now() + (? * INTERVAL '1 second')) RETURNING id",
            UUID.class, tenant, shop, "wh-" + UUID.randomUUID(), payload, receivedOffsetSeconds);
    }

    Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT processed_at, process_error, retry_count, next_retry_at FROM shopify_webhook_events WHERE id = ?", id);
    }

    boolean bool(String sql, Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args)); }
}
