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
 * <p>Incident 2026-11-21 (prod wiring — with a {@link Requeuer} and a {@link RegistrationCheck}):
 * <ul>
 *   <li>stuck webhook events while the heartbeat and the liveness job are fine are NOT a stall: the watchdog
 *       re-enqueues them ({@link Requeuer}, at most every {@code requeueEvery}) instead of restarting. Only when
 *       they are still stuck {@code requeueGrace} after the first re-enqueue AND no other webhook was processed
 *       meanwhile ({@link Health#othersProcessing}) is it treated as a stall (step 1 below);</li>
 *   <li>after the stop + start, the server must be back in jobrunr_backgroundjobservers (heartbeat after the
 *       restart began) within {@code reregisterTimeout}; if not, the escalation happens at once (alert, and the
 *       exit when exit-on-escalation is on) instead of {@code escalateAfter} later.</li>
 * </ul>
 * Without them (the older constructors) the behaviour is exactly the pre-incident one.
 *
 * <p>Plain class (no Spring), so the decisions are unit-tested with fakes and a fixed clock.
 */
public final class JobServerWatchdog {

    private static final Logger log = LoggerFactory.getLogger(JobServerWatchdog.class);

    /** The probe's answer. {@code checkError} = the watchdog could not check (its own DB call failed). */
    /**
     * {@code webhooksOnly}: the ONLY failing check is "Shopify webhook events unprocessed" (heartbeat and liveness
     * job fine). {@code othersProcessing}: some other webhook event was processed in the last few minutes.
     */
    public record Health(boolean healthy, List<String> problems, boolean checkError,
                         boolean webhooksOnly, boolean othersProcessing) {
        public Health(boolean healthy, List<String> problems, boolean checkError) {
            this(healthy, problems, checkError, false, false);
        }
        public static Health ok() { return new Health(true, List.of(), false); }
        public static Health stuckWebhooks(List<String> problems, boolean othersProcessing) {
            return new Health(false, List.copyOf(problems), false, true, othersProcessing);
        }
        public static Health failing(List<String> problems) { return new Health(false, List.copyOf(problems), false); }
        public static Health checkFailed(String why) { return new Health(false, List.of(why), true); }
    }

    public interface Probe { Health check(Instant now); }

    /** Stops and starts the BackgroundJobServer; returns a one-line outcome for the log / alert. */
    public interface ServerControl { String restart(); }

    public interface Alerter { void alert(String subject, String body); }

    /** Re-enqueues stuck Shopify webhook events (prod: ShopifyWebhookRetrySweeper.sweepNow); returns how many. */
    public interface Requeuer { int requeue(); }

    /** This server is in jobrunr_backgroundjobservers with a heartbeat at/after {@code since}. */
    public interface RegistrationCheck { boolean registeredSince(Instant since); }

    public interface Sleeper { void sleep(Duration d) throws InterruptedException; }

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

    private Requeuer requeuer;
    private RegistrationCheck registration;
    private Sleeper sleeper = d -> Thread.sleep(d.toMillis());
    private Duration requeueGrace = Duration.ofMinutes(5);
    private Duration requeueEvery = Duration.ofMinutes(2);
    private Duration reregisterTimeout = Duration.ofSeconds(30);
    private Duration reregisterPoll = Duration.ofSeconds(1);
    private Instant firstRequeueAt;
    private Instant lastRequeueAt;

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

    /** Prod wiring (incident 2026-11-21): requeue stuck webhooks instead of restarting; verify re-registration. */
    public JobServerWatchdog withRecovery(Requeuer requeuer, RegistrationCheck registration, Sleeper sleeper,
                                          Duration requeueGrace, Duration requeueEvery,
                                          Duration reregisterTimeout, Duration reregisterPoll) {
        this.requeuer = requeuer;
        this.registration = registration;
        if (sleeper != null) this.sleeper = sleeper;
        this.requeueGrace = requeueGrace;
        this.requeueEvery = requeueEvery;
        this.reregisterTimeout = reregisterTimeout;
        this.reregisterPoll = reregisterPoll;
        return this;
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
            firstRequeueAt = null;
            lastRequeueAt = null;
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

        if (incidentStart == null && requeuer != null && h.webhooksOnly() && !h.checkError()) {
            if (h.othersProcessing()) {
                // Jobs are running (heartbeat, liveness job, other webhooks): a few lost events, not a stall.
                firstRequeueAt = null;
                requeue(now, h, "other webhooks and the liveness job are being processed — not a stall");
                return;
            }
            if (firstRequeueAt == null) firstRequeueAt = now;
            if (now.isBefore(firstRequeueAt.plus(requeueGrace))) {
                requeue(now, h, "nothing else is being processed — restarting if still stuck "
                    + requeueGrace.toMinutes() + " min after the first re-enqueue");
                return;
            }
            log.warn("JobRunr watchdog: webhook events still stuck {} min after the first re-enqueue and no other "
                + "webhook was processed — treating it as a stall", Duration.between(firstRequeueAt, now).toMinutes());
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

    private void requeue(Instant now, Health h, String why) {
        if (lastRequeueAt != null && now.isBefore(lastRequeueAt.plus(requeueEvery))) {
            log.warn("JobRunr watchdog: {} ({}); re-enqueued {} s ago — waiting", h.problems(), why,
                Duration.between(lastRequeueAt, now).toSeconds());
            return;
        }
        lastRequeueAt = now;
        int n;
        try {
            n = requeuer.requeue();
        } catch (RuntimeException e) {
            log.warn("JobRunr watchdog: {} ({}); re-enqueue failed: {}", h.problems(), why, e.toString());
            return;
        }
        log.warn("JobRunr watchdog: {} ({}); re-enqueued {} event(s), no restart", h.problems(), why, n);
    }

    /** Polls {@link RegistrationCheck} until {@code reregisterTimeout}; true once registered. */
    private boolean awaitRegistration(Instant since) {
        long polls = Math.max(1, reregisterTimeout.toMillis() / Math.max(1, reregisterPoll.toMillis()));
        for (long i = 0; i <= polls; i++) {
            try {
                if (registration.registeredSince(since)) return true;
            } catch (RuntimeException e) {
                log.warn("JobRunr watchdog: registration check failed: {}", e.toString());
            }
            if (i == polls) break;
            try {
                sleeper.sleep(reregisterPoll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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
        if (registration == null) return;
        if (awaitRegistration(now)) {
            log.warn("JobRunr watchdog: the server re-registered in jobrunr_backgroundjobservers after the restart");
            return;
        }
        // The 2026-11-21 shape: stop + start returned, but the server never came back. Nothing runs until the app
        // restarts — escalate now instead of escalateAfter later.
        escalated = true;
        log.error("JobRunr watchdog: the server did NOT re-register in jobrunr_backgroundjobservers within {} s of "
            + "the restart ({})", reregisterTimeout.toSeconds(), outcome);
        alerter.alert("HIGH · Traced job server did not come back after restart",
            body("The watchdog restarted the job server, but it did not re-register within "
                    + reregisterTimeout.toSeconds() + " seconds — no background job runs.", incidentStart, h.problems(),
                exitOnEscalation
                    ? "The app now exits so Docker restarts the container."
                    : "No further automatic action. Restart the app container: "
                        + "cd ~/traceability && docker compose -f deploy/docker-compose.yml restart app"));
        if (exitOnEscalation) {
            log.error("JobRunr watchdog: EXITING THE JVM (code 1) — the server did not re-register after the restart "
                + "(incident since {}); Docker's restart policy restarts the container. Final thread dump:\n{}",
                incidentStart, dumper.dump());
            exiter.exit(1);
        }
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
