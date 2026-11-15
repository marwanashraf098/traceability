package com.traceability.jobs.watchdog;

import com.traceability.FlywayTestSupport;
import com.traceability.TestPostgres;
import com.traceability.tenancy.DataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-15 — prod's bosta-discovery-poll (every 2 min) takes ~2.3 min a run, so JobRunr creates one every ~4 min; a run that
 * outlasts the 5-min window ends after its creation fell out of it, and the next is created only at the next slot.
 * Slow but alive must never read as a stall; a stopped server must still be caught.
 *
 *   s1 a run created 5.5 min ago that just finished, no newer run yet → healthy (the gap between runs)
 *   s2 a run created 9 min ago, still PROCESSING with a fresh heartbeat → healthy (unchanged rule)
 *   s3 stopped: last run finished 6 min ago, nothing since, heartbeat stale → fails on BOTH the liveness and the heartbeat
 *   s4 stopped server row gone, last run finished 6 min ago → fails
 */
@Testcontainers
class WatchdogSlowDiscoveryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.newContainer()
        .withDatabaseName("watchdog_slow").withUsername("postgres").withPassword("postgres");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static final UUID TENANT = UUID.randomUUID();
    static final String LIVENESS = "bosta-discovery-poll";

    final UUID serverId = UUID.randomUUID();

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        assertThat(FlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").outOfOrder(true)
            .connectRetries(5).connectRetriesInterval(2).load().migrate().success).isTrue();
        ds = new HikariDataSource();
        ds.setJdbcUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        ds.setMaximumPoolSize(1);
        DataSourceConfig.applyNetworkSafety(ds, 15, 0);
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'WatchdogProbeTenant')", TENANT);
    }

    @AfterAll
    static void close() { ds.close(); }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM shopify_webhook_events");
        jdbc.update("DELETE FROM jobrunr_jobs");
        jdbc.update("DELETE FROM jobrunr_recurring_jobs");
        jdbc.update("DELETE FROM jobrunr_backgroundjobservers");
    }

    WatchdogDbProbe probe() {
        return new WatchdogDbProbe(ds, () -> serverId, Duration.ofMinutes(3), 5, LIVENESS, "*/2 * * * *",
            Duration.ofMinutes(5), Duration.ofSeconds(60));
    }

    void server(UUID id, String heartbeatAge) {
        jdbc.update("""
            INSERT INTO jobrunr_backgroundjobservers (id, workerPoolSize, pollIntervalInSeconds, firstHeartbeat,
                lastHeartbeat, running, systemTotalMemory, systemFreeMemory, systemCpuLoad, processMaxMemory,
                processFreeMemory, processAllocatedMemory, processCpuLoad)
            VALUES (?, 8, 5, (now() AT TIME ZONE 'UTC') - interval '1 hour',
                    (now() AT TIME ZONE 'UTC') - ?::interval, 1, 1, 1, 0, 1, 1, 1, 0)
            """, id.toString(), heartbeatAge);
    }

    void recurring() {
        jdbc.update("INSERT INTO jobrunr_recurring_jobs (id, version, jobAsJson) VALUES (?, 0, '{}')", LIVENESS);
    }

    void job(String state, String createdAge, String updatedAge) {
        jdbc.update("""
            INSERT INTO jobrunr_jobs (id, version, jobAsJson, jobSignature, state, createdAt, updatedAt, recurringJobId)
            VALUES (gen_random_uuid()::text, 0, '{}', 'sig', ?, (now() AT TIME ZONE 'UTC') - ?::interval,
                    (now() AT TIME ZONE 'UTC') - ?::interval, ?)
            """, state, createdAge, updatedAge, LIVENESS);
    }

    void event(String age, boolean processed, String error, int retries) {
        jdbc.update("""
            INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw, received_at,
                processed_at, process_error, retry_count)
            VALUES (?, 'orders/updated', 'x.myshopify.com', ?, '{}'::jsonb, now() - ?::interval,
                    CASE WHEN ? THEN now() END, ?, ?)
            """, TENANT, UUID.randomUUID().toString(), age, processed, error, retries);
    }

    void healthyBaseline() {
        server(serverId, "5 seconds");
        recurring();
        job("SUCCEEDED", "1 minute", "1 minute");
    }

    @Test
    void s1_slowRunJustFinished_isAlive() {
        server(serverId, "5 seconds");
        recurring();
        job("SUCCEEDED", "330 seconds", "30 seconds");
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.healthy()).as(h.problems().toString()).isTrue();
    }

    @Test
    void s2_slowRunStillProcessing_isAlive() {
        server(serverId, "5 seconds");
        recurring();
        job("SUCCEEDED", "20 minutes", "18 minutes");
        job("PROCESSING", "9 minutes", "15 seconds");
        assertThat(probe().check(Instant.now()).healthy()).isTrue();
    }

    @Test
    void s3_stoppedServer_staleHeartbeat_fails() {
        server(serverId, "6 minutes");
        recurring();
        job("SUCCEEDED", "8 minutes", "6 minutes");
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.healthy()).isFalse();
        assertThat(h.problems()).hasSize(2);
        assertThat(h.problems().get(0)).startsWith("no 'bosta-discovery-poll' job created in the last 5 min");
        assertThat(h.problems().get(1)).startsWith("this server's heartbeat is stale");
    }

    @Test
    void s4_stoppedServer_rowGone_fails() {
        recurring();
        job("SUCCEEDED", "8 minutes", "6 minutes");
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.healthy()).isFalse();
        assertThat(h.problems()).anySatisfy(p -> assertThat(p).contains("is not registered"));
        assertThat(h.problems()).anySatisfy(p -> assertThat(p).startsWith("no 'bosta-discovery-poll' job created"));
    }
}
