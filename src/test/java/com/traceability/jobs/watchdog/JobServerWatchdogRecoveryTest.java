package com.traceability.jobs.watchdog;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incident 2026-11-21 — watchdog recovery, Part 3 (c) and (d). Fakes + a clock the test moves (no Spring, no DB).
 *
 *   c1 a few stuck webhooks while other webhooks + the liveness job are processed: re-enqueued (every 2 min at most),
 *      NEVER a restart, no alert, no dump — however long it lasts
 *   c2 stuck webhooks and nothing else processed: re-enqueued first; still stuck 5 min after the first re-enqueue →
 *      the normal incident (alert + restart)
 *   c3 stuck webhooks that clear after a re-enqueue → nothing more, and a later episode starts afresh
 *   c4 a real stall (heartbeat / liveness failing) still restarts at once, requeuer or not
 *   d1 after the restart the server never re-registers within 30 s → escalation at once (alert + exit 1), polled
 *      ~1 s apart; no second escalation later
 *   d2 re-registers on the 3rd poll → no escalation; the incident goes on as before
 *   d3 not re-registered, exit-on-escalation off → the alert, no exit
 */
class JobServerWatchdogRecoveryTest {

    static final Instant T0 = Instant.parse("2026-11-21T16:00:00Z");

    static final class MutableClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plus(Duration d) { now = now.plus(d); }
    }

    final MutableClock clock = new MutableClock();
    final AtomicReference<JobServerWatchdog.Health> health = new AtomicReference<>(JobServerWatchdog.Health.ok());
    final AtomicInteger restarts = new AtomicInteger(), dumps = new AtomicInteger(), requeues = new AtomicInteger(),
                        registrationPolls = new AtomicInteger(), sleeps = new AtomicInteger();
    final List<String> alerts = new ArrayList<>();
    final List<Integer> exits = new ArrayList<>();
    volatile int registeredOnPoll = 1;   // 1 = registered at the first poll; 0 = never

    JobServerWatchdog watchdog(boolean exitOnEscalation) {
        return new JobServerWatchdog(
            now -> health.get(),
            () -> { restarts.incrementAndGet(); return "restarted"; },
            (subject, body) -> alerts.add(subject),
            () -> { dumps.incrementAndGet(); return "dump"; },
            clock, Duration.ZERO, Duration.ofMinutes(5), exitOnEscalation, exits::add)
            .withRecovery(
                () -> { requeues.incrementAndGet(); return 2; },
                since -> { int n = registrationPolls.incrementAndGet(); return registeredOnPoll > 0 && n >= registeredOnPoll; },
                d -> sleeps.incrementAndGet(),
                Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(1));
    }

    static JobServerWatchdog.Health stuck(boolean othersProcessing) {
        return JobServerWatchdog.Health.stuckWebhooks(List.of("2 Shopify webhook event(s) unprocessed for more than 3 min"),
            othersProcessing);
    }

    void minutes(JobServerWatchdog w, int n) {
        for (int i = 0; i < n; i++) { clock.plus(Duration.ofMinutes(1)); w.tick(); }
    }

    @Test
    void c1_stuckWhileOthersProcess_requeuedNeverRestarted() {
        JobServerWatchdog w = watchdog(true);
        health.set(stuck(true));
        minutes(w, 60);
        assertThat(restarts.get()).isZero();
        assertThat(alerts).isEmpty();
        assertThat(dumps.get()).isZero();
        assertThat(exits).isEmpty();
        assertThat(requeues.get()).as("every 2 min at most").isEqualTo(30);
    }

    @Test
    void c2_stuckAndNothingElseProcessed_requeueFirst_restartAfter5Min() {
        JobServerWatchdog w = watchdog(true);
        health.set(stuck(false));
        minutes(w, 1);
        assertThat(requeues.get()).isEqualTo(1);
        assertThat(restarts.get()).isZero();
        minutes(w, 4);                          // 4 min after the first re-enqueue
        assertThat(restarts.get()).isZero();
        assertThat(alerts).isEmpty();
        minutes(w, 1);                          // 5 min
        assertThat(restarts.get()).isEqualTo(1);
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled");
        assertThat(exits).as("it re-registered").isEmpty();
    }

    @Test
    void c3_clearsAfterRequeue_laterEpisodeStartsAfresh() {
        JobServerWatchdog w = watchdog(true);
        health.set(stuck(false));
        minutes(w, 3);
        health.set(JobServerWatchdog.Health.ok());
        minutes(w, 10);
        health.set(stuck(false));
        minutes(w, 4);
        assertThat(restarts.get()).as("the grace restarts with the new episode").isZero();
        assertThat(alerts).isEmpty();
    }

    @Test
    void c4_realStall_restartsAtOnce() {
        JobServerWatchdog w = watchdog(true);
        health.set(JobServerWatchdog.Health.failing(List.of("this server's heartbeat is stale (last: …)")));
        minutes(w, 1);
        assertThat(restarts.get()).isEqualTo(1);
        assertThat(requeues.get()).isZero();
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled");
    }

    @Test
    void d1_notReRegisteredIn30s_escalatesAndExitsAtOnce() {
        registeredOnPoll = 0;
        JobServerWatchdog w = watchdog(true);
        health.set(JobServerWatchdog.Health.failing(List.of("this server (x) is not registered in jobrunr_backgroundjobservers — stopped")));
        minutes(w, 1);
        assertThat(restarts.get()).isEqualTo(1);
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled",
                                           "HIGH · Traced job server did not come back after restart");
        assertThat(exits).containsExactly(1);
        assertThat(registrationPolls.get()).isEqualTo(31);
        assertThat(sleeps.get()).isEqualTo(30);
        minutes(w, 10);
        assertThat(alerts).as("no second escalation").hasSize(2);
        assertThat(exits).hasSize(1);
    }

    @Test
    void d2_reRegisteredOnThirdPoll_noEscalation() {
        registeredOnPoll = 3;
        JobServerWatchdog w = watchdog(true);
        health.set(JobServerWatchdog.Health.failing(List.of("this server's heartbeat is stale (last: …)")));
        minutes(w, 1);
        assertThat(registrationPolls.get()).isEqualTo(3);
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled");
        assertThat(exits).isEmpty();
        health.set(JobServerWatchdog.Health.ok());
        minutes(w, 1);
        assertThat(alerts).hasSize(1);
    }

    @Test
    void d3_notReRegistered_exitOff_alertOnly() {
        registeredOnPoll = 0;
        JobServerWatchdog w = watchdog(false);
        health.set(JobServerWatchdog.Health.failing(List.of("this server's heartbeat is stale (last: …)")));
        minutes(w, 1);
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled",
                                           "HIGH · Traced job server did not come back after restart");
        assertThat(exits).isEmpty();
    }
}
