package com.traceability.jobs.watchdog;

import org.jobrunr.scheduling.cron.CronExpression;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The watchdog's three checks, on the watchdog's OWN one-connection pool (owner credentials, read-only,
 * statement timeout + socket timeout — {@link JobServerWatchdogConfig}). Reads aggregates only (a count, a few
 * timestamps); never tenant rows.
 * <ol>
 *   <li>Shopify webhook events received, not processed, not failed, older than {@code staleAfter} (3 min) —
 *       within the retry sweeper's 48 h window and below its attempt cap (index
 *       {@code shopify_webhook_events_unprocessed_idx} has exactly this predicate).</li>
 *   <li>The liveness recurring job ({@code bosta-discovery-poll}): a job created for it within the window, or
 *       one of its jobs PROCESSING with a fresh updatedAt (a long run skips the next creation — that's
 *       not a stall). Window = max(configured, 2 × the job's own cron interval + 1 min), so a slower cron
 *       never reads as a stall. Skipped when the recurring job isn't registered.</li>
 *   <li>This server's row in {@code jobrunr_backgroundjobservers} exists and its lastHeartbeat is fresh.</li>
 * </ol>
 * JobRunr stores its timestamps as UTC wall time in {@code timestamp} columns, so they are read with a UTC
 * calendar and compared with this JVM's clock (the same clock that wrote them).
 */
public final class WatchdogDbProbe implements JobServerWatchdog.Probe {

    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;
    private final Supplier<UUID> serverId;
    private final Duration staleAfter;
    private final int maxAttempts;
    private final String livenessJobId;
    private final Duration livenessWindow;
    private final Duration heartbeatMaxAge;

    public WatchdogDbProbe(DataSource ds, Supplier<UUID> serverId, Duration staleAfter, int maxAttempts,
                           String livenessJobId, String livenessCron, Duration livenessMaxAge,
                           Duration heartbeatMaxAge) {
        this.jdbc = new JdbcTemplate(ds);
        this.serverId = serverId;
        this.staleAfter = staleAfter;
        this.maxAttempts = maxAttempts;
        this.livenessJobId = livenessJobId;
        this.livenessWindow = livenessWindow(livenessCron, livenessMaxAge);
        this.heartbeatMaxAge = heartbeatMaxAge;
    }

    /** max(configured, 2 × cron interval + 1 min). An unparsable cron keeps the configured window. */
    static Duration livenessWindow(String cron, Duration configured) {
        try {
            CronExpression c = CronExpression.create(cron);
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            Instant a = c.next(base, base, ZoneOffset.UTC);
            Instant b = c.next(base, a, ZoneOffset.UTC);
            Duration twice = Duration.between(a, b).multipliedBy(2).plusMinutes(1);
            return twice.compareTo(configured) > 0 ? twice : configured;
        } catch (RuntimeException e) {
            return configured;
        }
    }

    Duration livenessWindow() { return livenessWindow; }

    @Override
    public JobServerWatchdog.Health check(Instant now) {
        List<String> problems = new ArrayList<>();

        Long stale = jdbc.queryForObject("""
            SELECT count(*) FROM shopify_webhook_events
             WHERE processed_at IS NULL AND process_error IS NULL
               AND received_at < now() - make_interval(secs => ?)
               AND received_at > now() - interval '48 hours'
               AND retry_count < ?
            """, Long.class, (double) staleAfter.toSeconds(), maxAttempts);
        if (stale != null && stale > 0) {
            problems.add(stale + " Shopify webhook event(s) unprocessed for more than " + staleAfter.toMinutes() + " min");
        }

        Boolean registered = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM jobrunr_recurring_jobs WHERE id = ?)", Boolean.class, livenessJobId);
        if (Boolean.TRUE.equals(registered)) {
            Instant[] last = jdbc.query("""
                SELECT max(createdAt) AS created,
                       max(updatedAt) FILTER (WHERE state = 'PROCESSING') AS processing
                  FROM jobrunr_jobs WHERE recurringJobId = ?
                """, rs -> {
                    rs.next();
                    return new Instant[] { instant(rs.getTimestamp("created", UTC)),
                                           instant(rs.getTimestamp("processing", UTC)) };
                }, livenessJobId);
            Instant cutoff = now.minus(livenessWindow);
            boolean created = last[0] != null && last[0].isAfter(cutoff);
            boolean running = last[1] != null && last[1].isAfter(now.minus(Duration.ofMinutes(2)));
            if (!created && !running) {
                problems.add("no '" + livenessJobId + "' job created in the last " + livenessWindow.toMinutes()
                    + " min (last: " + (last[0] == null ? "none" : last[0]) + ")");
            }
        }

        UUID id = serverId.get();
        if (id != null) {
            List<Timestamp> hb = jdbc.query(
                "SELECT lastHeartbeat FROM jobrunr_backgroundjobservers WHERE id = ?",
                (rs, i) -> rs.getTimestamp(1, UTC), id.toString());
            if (hb.isEmpty()) {
                problems.add("this server (" + id + ") is not registered in jobrunr_backgroundjobservers — stopped");
            } else {
                Instant beat = instant(hb.get(0));
                if (beat == null || beat.isBefore(now.minus(heartbeatMaxAge))) {
                    problems.add("this server's heartbeat is stale (last: " + beat + ")");
                }
            }
        }

        return problems.isEmpty() ? JobServerWatchdog.Health.ok() : JobServerWatchdog.Health.failing(problems);
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
