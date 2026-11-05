package com.traceability.jobs.watchdog;

import com.traceability.FlywayTestSupport;
import com.traceability.TestPostgres;
import com.zaxxer.hikari.HikariDataSource;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.server.JobActivator;
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jobrunr.server.BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration;

/**
 * The incident and the fix, end to end, on a real JobRunr 7.3.0 BackgroundJobServer over Postgres:
 *
 *   e1 a storage outage of a few seconds (the DataSource refuses connections — what an exhausted / dropped
 *      pool looks like to JobRunr) makes JobRunr stop its own server: ServerZooKeeper.run catches the heartbeat's
 *      StorageException and calls stop(). When the DB is back the server STAYS stopped (the prod symptom).
 *      The watchdog (real DB probe, real thread dump, real JobRunrServerControl) then: ONE HIGH alert, a
 *      thread dump, stop + start → the server announces itself again → the next tick is healthy, no more alerts.
 *   e2 still stuck after the restart (a Shopify event nobody processes) → exactly one escalation alert
 *      5 min after the restart.
 */
@Testcontainers
class JobServerWatchdogEndToEndTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.newContainer()
        .withDatabaseName("watchdog_e2e").withUsername("postgres").withPassword("postgres");

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    static final AtomicBoolean storageDown = new AtomicBoolean();
    static BackgroundJobServer server;

    /** JobRunr's DataSource: refuses connections while {@code storageDown}. */
    static final class FlakyDataSource extends DelegatingDataSource {
        FlakyDataSource(HikariDataSource t) { super(t); }
        @Override public Connection getConnection() throws SQLException {
            if (storageDown.get()) throw new SQLException("Connection is not available, request timed out after 3000ms.");
            return super.getConnection();
        }
    }

    static final class OffsetClock extends Clock {
        final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(offset.get()); }
    }

    @BeforeAll
    static void startServer() {
        POSTGRES.start();
        assertThat(FlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").outOfOrder(true)
            .connectRetries(5).connectRetriesInterval(2).load().migrate().success).isTrue();
        ds = new HikariDataSource();
        ds.setJdbcUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        ds.setMaximumPoolSize(4);
        jdbc = new JdbcTemplate(ds);

        PostgresStorageProvider storage = new PostgresStorageProvider(new FlakyDataSource(ds), DatabaseOptions.SKIP_CREATE);
        storage.setJobMapper(new JobMapper(new JacksonJsonMapper()));
        server = new BackgroundJobServer(storage, new JacksonJsonMapper(), new JobActivator() {
            @Override public <T> T activateJob(Class<T> type) { return null; }
        }, usingStandardBackgroundJobServerConfiguration().andPollIntervalInSeconds(5).andWorkerCount(2));
        server.start();
        await("server announced", Duration.ofSeconds(30), JobServerWatchdogEndToEndTest::serverRowPresent);
    }

    @AfterAll
    static void stop() {
        storageDown.set(false);
        if (server != null) server.stop();
        ds.close();
    }

    static boolean serverRowPresent() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM jobrunr_backgroundjobservers WHERE id = ?",
            Integer.class, server.getId().toString());
        return n != null && n > 0;
    }

    /** Age of this server's heartbeat (row missing = very old). JobRunr stores UTC wall time. */
    static Duration heartbeatAge() {
        java.util.List<java.sql.Timestamp> t = jdbc.query(
            "SELECT lastHeartbeat FROM jobrunr_backgroundjobservers WHERE id = ?",
            (rs, i) -> rs.getTimestamp(1, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))),
            server.getId().toString());
        return t.isEmpty() ? Duration.ofDays(1) : Duration.between(t.get(0).toInstant(), Instant.now());
    }

    static void await(String what, Duration max, BooleanSupplier cond) {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    final List<String> alerts = new ArrayList<>();
    final List<String> dumps = new ArrayList<>();
    final OffsetClock clock = new OffsetClock();

    JobServerWatchdog watchdog() {
        // Heartbeat max age 10 s here (prod 60 s) so the test sees a dead server within two poll intervals.
        WatchdogDbProbe probe = new WatchdogDbProbe(ds, server::getId, Duration.ofMinutes(3), 5,
            "bosta-discovery-poll", "*/2 * * * *", Duration.ofMinutes(5), Duration.ofSeconds(10));
        ThreadDumps real = new ThreadDumps();
        return new JobServerWatchdog(probe,
            new JobRunrServerControl(() -> server, Duration.ofSeconds(60)),
            (subject, body) -> alerts.add(subject),
            () -> { String d = real.dump(); dumps.add(d); return d; },
            clock, Duration.ZERO, Duration.ofMinutes(5));
    }

    @Test
    void e1_jobRunrStopsItselfOnAStorageBlip_watchdogAlertsOnceDumpsAndRestarts() {
        JobServerWatchdog w = watchdog();
        w.tick();
        assertThat(alerts).as("healthy → silent").isEmpty();

        // The incident: a few seconds without connections. One failed heartbeat is enough.
        storageDown.set(true);
        await("JobRunr stopped its own server", Duration.ofSeconds(30), () -> !server.isRunning());
        storageDown.set(false);
        // stop() tried to remove the server row while the DB was down, so the row stays with a heartbeat that
        // never moves again. The prod symptom: the DB is fine again, the server never comes back by itself.
        long end = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < end) {
            assertThat(server.isRunning()).as("JobRunr must not recover by itself").isFalse();
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertThat(heartbeatAge()).isGreaterThan(Duration.ofSeconds(10));

        w.tick();
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled");
        assertThat(dumps).hasSize(1);
        assertThat(dumps.get(0)).contains("===== THREAD DUMP").contains("VIRTUAL THREADS").contains("END THREAD DUMP");
        await("server restarted and heartbeating", Duration.ofSeconds(30),
            () -> server.isRunning() && heartbeatAge().compareTo(Duration.ofSeconds(6)) < 0);

        w.tick();
        assertThat(alerts).as("recovered: no second alert").hasSize(1);
    }

    @Test
    void e2_stillStuckAfterRestart_oneEscalationFiveMinutesLater() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'WatchdogE2ETenant')", tenant);
        jdbc.update("""
            INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw, received_at)
            VALUES (?, 'orders/updated', 'x.myshopify.com', ?, '{}'::jsonb, now() - interval '4 minutes')
            """, tenant, UUID.randomUUID().toString());
        try {
            JobServerWatchdog w = watchdog();
            w.tick();
            assertThat(alerts).containsExactly("HIGH · Traced job server stalled");
            await("server running after restart", Duration.ofSeconds(30),
                () -> server.isRunning() && heartbeatAge().compareTo(Duration.ofSeconds(6)) < 0);

            clock.offset.set(Duration.ofMinutes(4));
            w.tick();
            assertThat(alerts).hasSize(1);

            clock.offset.set(Duration.ofMinutes(5).plusSeconds(5));
            w.tick();
            assertThat(alerts).containsExactly("HIGH · Traced job server stalled",
                                               "HIGH · Traced job server still stalled after restart");
            clock.offset.set(Duration.ofMinutes(20));
            w.tick();
            assertThat(alerts).hasSize(2);
        } finally {
            jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", tenant);
        }
    }
}
