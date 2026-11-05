package com.traceability.jobs.watchdog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Job-server watchdog (2026-11-05, after the 2026-10-29 and 2026-11-04 stalls). Runs OFF JobRunr — on its own
 * Spring TaskScheduler thread ({@link JobServerWatchdogConfig}) with its own one-connection DB pool — because
 * the thing it watches is JobRunr: JobRunr 7.3.0 OSS stops its BackgroundJobServer for good after one storage
 * exception in its heartbeat ({@code ServerZooKeeper.run}) or after more than 5 in a row in a task
 * ({@code JobHandler.run}), and never starts it again.
 *
 * <p>Every tick: {@link Probe#check} (unprocessed Shopify webhook events older than 3 min; a job created / still
 * heartbeating for the liveness recurring job; this server's heartbeat). One incident = the first failing tick
 * until the first healthy one:
 * <ol>
 *   <li>first failing tick: full thread dump to the log, ONE HIGH alert, then stop + start the
 *       BackgroundJobServer through its API ({@link ServerControl}), logged;</li>
 *   <li>still failing {@code escalateAfter} (5 min) after that restart: ONE more alert (escalation) and a second
 *       thread dump — nothing else (no second restart, no exit);</li>
 *   <li>a healthy tick closes the incident (logged); the next failure is a new incident.</li>
 * </ol>
 * A failed probe (the watchdog's own DB check threw) counts only from its 2nd consecutive time, so one DB blip
 * never restarts the job server. No check runs in the first {@code startupGrace} after start.
 *
 * <p>Plain class (no Spring), so the decisions are unit-tested with fakes and a fixed clock.
 */
public final class JobServerWatchdog {

    private static final Logger log = LoggerFactory.getLogger(JobServerWatchdog.class);

    /** The probe's answer. {@code checkError} = the watchdog could not check (its own DB call failed). */
    public record Health(boolean healthy, List<String> problems, boolean checkError) {
        public static Health ok() { return new Health(true, List.of(), false); }
        public static Health failing(List<String> problems) { return new Health(false, List.copyOf(problems), false); }
        public static Health checkFailed(String why) { return new Health(false, List.of(why), true); }
    }

    public interface Probe { Health check(Instant now); }

    /** Stops and starts the BackgroundJobServer; returns a one-line outcome for the log / alert. */
    public interface ServerControl { String restart(); }

    public interface Alerter { void alert(String subject, String body); }

    public interface ThreadDumper { String dump(); }

    /** Ends the JVM (prod: {@link #halting}) so Docker's restart policy restarts the container. */
    public interface Exiter { void exit(int code); }

    /**
     * System.exit on its own thread (never inside the scheduler's tick, so no shutdown hook can wait on the
     * caller), plus a daemon that halts the JVM {@code haltAfter} later in case a shutdown hook hangs — e.g.
     * the BackgroundJobServer's stop() stuck on the very lock that stalled it.
     */
    public static Exiter halting(Duration haltAfter) {
        return code -> {
            Thread halt = new Thread(() -> {
                try { Thread.sleep(haltAfter.toMillis()); } catch (InterruptedException ignored) { }
                Runtime.getRuntime().halt(code);
            }, "jobrunr-watchdog-halt");
            halt.setDaemon(true);
            halt.start();
            new Thread(() -> System.exit(code), "jobrunr-watchdog-exit").start();
        };
    }

    private final Probe probe;
    private final ServerControl control;
    private final Alerter alerter;
    private final ThreadDumper dumper;
    private final Clock clock;
    private final Instant startedAt;
    private final Duration startupGrace;
    private final Duration escalateAfter;
    private final boolean exitOnEscalation;
    private final Exiter exiter;

    private Instant incidentStart;
    private Instant restartedAt;
    private boolean escalated;
    private int consecutiveCheckErrors;

    public JobServerWatchdog(Probe probe, ServerControl control, Alerter alerter, ThreadDumper dumper,
                             Clock clock, Duration startupGrace, Duration escalateAfter) {
        this(probe, control, alerter, dumper, clock, startupGrace, escalateAfter, false, code -> { });
    }

    /**
     * {@code exitOnEscalation} (TRACED_WATCHDOG_EXIT_ON_ESCALATION, default false): at the escalation — and
     * only there, once per incident — after the escalation alert, log a final line + a thread dump and end the
     * JVM through {@code exiter}. Compose's {@code restart: unless-stopped} starts a fresh container.
     */
    public JobServerWatchdog(Probe probe, ServerControl control, Alerter alerter, ThreadDumper dumper,
                             Clock clock, Duration startupGrace, Duration escalateAfter,
                             boolean exitOnEscalation, Exiter exiter) {
        this.exitOnEscalation = exitOnEscalation;
        this.exiter = exiter;
        this.probe = probe;
        this.control = control;
        this.alerter = alerter;
        this.dumper = dumper;
        this.clock = clock;
        this.startedAt = clock.instant();
        this.startupGrace = startupGrace;
        this.escalateAfter = escalateAfter;
    }

    /** One check. Never throws: an exception would cancel the scheduler's periodic task. */
    public synchronized void tick() {
        try {
            doTick();
        } catch (Throwable t) {
            log.error("JobRunr watchdog: tick failed (the watchdog keeps running)", t);
        }
    }

    private void doTick() {
        Instant now = clock.instant();
        if (now.isBefore(startedAt.plus(startupGrace))) return;

        Health h;
        try {
            h = probe.check(now);
        } catch (RuntimeException e) {
            h = Health.checkFailed("watchdog DB check failed: " + e);
        }

        if (h.healthy()) {
            if (incidentStart != null) {
                log.warn("JobRunr watchdog: RECOVERED — incident open since {} ({} min) is closed",
                    incidentStart, Duration.between(incidentStart, now).toMinutes());
            }
            incidentStart = null;
            restartedAt = null;
            escalated = false;
            consecutiveCheckErrors = 0;
            return;
        }

        if (h.checkError()) {
            consecutiveCheckErrors++;
            if (incidentStart == null && consecutiveCheckErrors < 2) {
                log.warn("JobRunr watchdog: could not check ({}); acting only if the next check fails too",
                    h.problems());
                return;
            }
        } else {
            consecutiveCheckErrors = 0;
        }

        if (incidentStart == null) {
            openIncident(now, h);
            return;
        }

        if (!escalated && restartedAt != null && !now.isBefore(restartedAt.plus(escalateAfter))) {
            escalated = true;
            String dump = dumper.dump();
            log.error("JobRunr watchdog: STILL STALLED {} min after the restart — problems: {}\n{}",
                Duration.between(restartedAt, now).toMinutes(), h.problems(), dump);
            alerter.alert("HIGH · Traced job server still stalled after restart",
                body("The job server is still stalled " + Duration.between(restartedAt, now).toMinutes()
                        + " minutes after the watchdog restarted it.", incidentStart, h.problems(),
                    exitOnEscalation
                        ? "The app now exits so Docker restarts the container."
                        : "No further automatic action. Restart the app container: "
                            + "cd ~/traceability && docker compose -f deploy/docker-compose.yml restart app"));
            if (exitOnEscalation) {
                log.error("JobRunr watchdog: EXITING THE JVM (code 1) — job server still stalled {} min after the restart "
                        + "(incident since {}); Docker's restart policy restarts the container. Final thread dump:\n{}",
                    Duration.between(restartedAt, now).toMinutes(), incidentStart, dumper.dump());
                exiter.exit(1);
            }
            return;
        }

        log.warn("JobRunr watchdog: incident open since {} — still failing: {}", incidentStart, h.problems());
    }

    private void openIncident(Instant now, Health h) {
        incidentStart = now;
        escalated = false;
        String dump = dumper.dump();
        log.error("JobRunr watchdog: JOB SERVER STALLED — problems: {}\n{}", h.problems(), dump);
        alerter.alert("HIGH · Traced job server stalled",
            body("The background job server (JobRunr) looks stalled.", now, h.problems(),
                "The watchdog is restarting the job server now (stop + start). "
                    + "If it is still stalled in " + escalateAfter.toMinutes() + " minutes you will get a second email."));
        log.error("JobRunr watchdog: restarting the BackgroundJobServer (stop + start)");
        String outcome;
        try {
            outcome = control.restart();
        } catch (RuntimeException e) {
            outcome = "restart failed: " + e;
        }
        restartedAt = clock.instant();
        log.error("JobRunr watchdog: BackgroundJobServer restart done — {}", outcome);
    }

    private static String body(String headline, Instant since, List<String> problems, String action) {
        StringBuilder b = new StringBuilder();
        b.append("<p><strong>").append(esc(headline)).append("</strong></p>");
        b.append("<p>Since: ").append(since).append(" (UTC)</p><ul>");
        for (String p : problems) b.append("<li>").append(esc(p)).append("</li>");
        b.append("</ul><p>").append(esc(action)).append("</p>");
        b.append("<p>A full thread dump is in the app log (search \"JobRunr watchdog\").</p>");
        return b.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
