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
 * Incident 2026-11-21 — the probe's new answers on a real schema (postgres connection, like WatchdogDbProbeTest).
 *
 *   q1 only the webhook check fails → stuckWebhooks; othersProcessing true when another event was processed recently
 *   q2 … false when none was
 *   q3 webhook check AND the heartbeat fail → a plain stall (failing), not stuckWebhooks
 *   q4 registeredSince: row with a heartbeat after the restart → true; heartbeat from before it → false; no row → false
 */
@Testcontainers
class WatchdogRecoveryProbeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.newContainer()
        .withDatabaseName("watchdog_recovery").withUsername("postgres").withPassword("postgres");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static final UUID TENANT = UUID.randomUUID();

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
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'WatchdogRecoveryTenant')", TENANT);
    }

    @AfterAll
    static void close() { ds.close(); }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM shopify_webhook_events");
        jdbc.update("DELETE FROM jobrunr_backgroundjobservers");
    }

    WatchdogDbProbe probe() {
        return new WatchdogDbProbe(ds, () -> serverId, Duration.ofMinutes(3), 5, "not-registered-liveness", "*/2 * * * *",
            Duration.ofMinutes(5), Duration.ofSeconds(60));
    }

    void server(String heartbeatAge) {
        jdbc.update("""
            INSERT INTO jobrunr_backgroundjobservers (id, workerPoolSize, pollIntervalInSeconds, firstHeartbeat,
                lastHeartbeat, running, systemTotalMemory, systemFreeMemory, systemCpuLoad, processMaxMemory,
                processFreeMemory, processAllocatedMemory, processCpuLoad)
            VALUES (?, 8, 5, (now() AT TIME ZONE 'UTC') - interval '1 hour',
                    (now() AT TIME ZONE 'UTC') - ?::interval, 1, 1, 1, 0, 1, 1, 1, 0)
            """, serverId.toString(), heartbeatAge);
    }

    void event(String age, boolean processed) {
        jdbc.update("""
            INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw, received_at, processed_at)
            VALUES (?, 'orders/updated', 'x.myshopify.com', ?, '{}'::jsonb, now() - ?::interval, CASE WHEN ? THEN now() END)
            """, TENANT, UUID.randomUUID().toString(), age, processed);
    }

    @Test
    void q1_onlyWebhooks_othersProcessing() {
        server("5 seconds");
        event("10 minutes", false);
        event("1 minute", true);
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.healthy()).isFalse();
        assertThat(h.webhooksOnly()).isTrue();
        assertThat(h.othersProcessing()).isTrue();
        assertThat(h.problems()).containsExactly("1 Shopify webhook event(s) unprocessed for more than 3 min");
    }

    @Test
    void q2_onlyWebhooks_nothingElseProcessed() {
        server("5 seconds");
        event("10 minutes", false);
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.webhooksOnly()).isTrue();
        assertThat(h.othersProcessing()).isFalse();
    }

    @Test
    void q3_webhooksAndHeartbeat_isAStall() {
        server("5 minutes");
        event("10 minutes", false);
        event("1 minute", true);
        JobServerWatchdog.Health h = probe().check(Instant.now());
        assertThat(h.healthy()).isFalse();
        assertThat(h.webhooksOnly()).isFalse();
        assertThat(h.problems()).hasSize(2);
    }

    @Test
    void q4_registeredSince() {
        WatchdogDbProbe p = probe();
        assertThat(p.registeredSince(Instant.now().minusSeconds(60))).as("no row").isFalse();
        server("1 second");
        assertThat(p.registeredSince(Instant.now().minusSeconds(30))).as("heartbeat after the restart").isTrue();
        assertThat(p.registeredSince(Instant.now().plusSeconds(30))).as("heartbeat before the restart").isFalse();
    }
}
