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
 * The watchdog's DB checks on a real migrated schema (connects as postgres — the watchdog pool uses the owner
 * credentials; no RLS question here, it reads aggregates across tenants by design).
 *
 *   p1 healthy: no stale event, liveness job created recently, fresh heartbeat → ok
 *   p2 a Shopify event unprocessed for 4 min fails it; processed / failed / 2-min-old / 49-h-old / exhausted don't
 *   p3 liveness job: none created in the window → fails; one PROCESSING with a fresh updatedAt → ok;
 *      recurring job not registered → check skipped
 *   p4 heartbeat: stale → fails; server row missing (JobRunr stopped itself) → fails
 *   p5 liveness window = max(configured, 2 × cron interval + 1 min)
 */
@Testcontainers
class WatchdogDbProbeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.newContainer()
        .withDatabaseName("watchdog_probe").withUsername("postgres").withPassword("postgres");

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
    void p1_healthy() {
        healthyBaseline();
        event("1 minute", false, null, 0);
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.healthy()).as(h.problems().toString()).isTrue();
    }

    @Test
    void p2_staleShopifyEvent() {
        healthyBaseline();
        event("2 minutes", false, null, 0);              // not stale yet
        event("4 minutes", true, null, 0);               // processed
        event("4 minutes", false, "boom", 1);            // failed (retry sweeper's business)
        event("49 hours", false, null, 0);               // outside the 48 h window
        event("10 minutes", false, null, 5);             // attempts exhausted
        assertThat(probe().check(Instant.now()).healthy()).isTrue();

        event("4 minutes", false, null, 0);
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.healthy()).isFalse();
        assertThat(h.checkError()).isFalse();
        assertThat(h.problems()).containsExactly("1 Shopify webhook event(s) unprocessed for more than 3 min");
    }

    @Test
    void p3_livenessJob() {
        server(serverId, "5 seconds");
        assertThat(probe().check(Instant.now()).healthy()).as("not registered → skipped").isTrue();

        recurring();
        job("SUCCEEDED", "10 minutes", "10 minutes");
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.healthy()).isFalse();
        assertThat(h.problems()).singleElement().asString()
            .startsWith("no 'bosta-discovery-poll' job created in the last 5 min");

        job("PROCESSING", "9 minutes", "20 seconds");   // a long run is still heartbeating
        assertThat(probe().check(Instant.now()).healthy()).isTrue();
    }

    @Test
    void p4_heartbeat() {
        recurring();
        job("SUCCEEDED", "1 minute", "1 minute");
        server(serverId, "2 minutes");
        JobServerWatchdog.Health stale = probe().check(Instant.now());
        assertThat(stale.problems()).singleElement().asString().startsWith("this server's heartbeat is stale");

        jdbc.update("DELETE FROM jobrunr_backgroundjobservers");
        server(UUID.randomUUID(), "1 second");           // another server is alive, ours is gone
        JobServerWatchdog.Health gone = probe().check(Instant.now());
        assertThat(gone.problems()).singleElement().asString().contains("is not registered");
    }

    @Test
    void p5_livenessWindow() {
        assertThat(WatchdogDbProbe.livenessWindow("*/2 * * * *", Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(5));
        assertThat(WatchdogDbProbe.livenessWindow("*/20 * * * *", Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(41));
        assertThat(WatchdogDbProbe.livenessWindow("not a cron", Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(5));
    }
}
