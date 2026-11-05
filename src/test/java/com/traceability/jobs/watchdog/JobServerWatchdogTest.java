package com.traceability.jobs.watchdog;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Watchdog decisions (no Spring, no DB): fake probe / control / alerter / dumper, a clock the test moves.
 *
 *   w1 healthy → silent: no alert, no dump, no restart
 *   w2 failing → exactly ONE HIGH alert, one dump, one restart; nothing more for 5 min; then ONE escalation
 *      (second alert + dump, no second restart); nothing after that
 *   w3 recovered then failing again → a new incident (new alert + restart)
 *   w4 one failed probe (DB blip) → nothing; the second in a row → incident
 *   w5 startup grace → no check at all
 *   w6 a probe that throws doesn't break the watchdog
 */
class JobServerWatchdogTest {

    static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

    static final class MutableClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plus(Duration d) { now = now.plus(d); }
    }

    final MutableClock clock = new MutableClock();
    final AtomicReference<JobServerWatchdog.Health> health = new AtomicReference<>(JobServerWatchdog.Health.ok());
    final AtomicInteger probes = new AtomicInteger();
    final AtomicInteger restarts = new AtomicInteger();
    final AtomicInteger dumps = new AtomicInteger();
    final List<String> alerts = new ArrayList<>();

    JobServerWatchdog watchdog(Duration grace) {
        return new JobServerWatchdog(
            now -> { probes.incrementAndGet(); return health.get(); },
            () -> { restarts.incrementAndGet(); return "restarted"; },
            (subject, body) -> alerts.add(subject),
            () -> { dumps.incrementAndGet(); return "dump"; },
            clock, grace, Duration.ofMinutes(5));
    }

    void minutes(JobServerWatchdog w, int n) {
        for (int i = 0; i < n; i++) { clock.plus(Duration.ofMinutes(1)); w.tick(); }
    }

    @Test
    void w1_healthy_isSilent() {
        JobServerWatchdog w = watchdog(Duration.ZERO);
        minutes(w, 30);
        assertThat(probes.get()).isEqualTo(30);
        assertThat(alerts).isEmpty();
        assertThat(dumps.get()).isZero();
        assertThat(restarts.get()).isZero();
    }

    @Test
    void w2_failing_oneAlertDumpRestart_thenOneEscalationAfterFiveMinutes() {
        JobServerWatchdog w = watchdog(Duration.ZERO);
        minutes(w, 2);
        health.set(JobServerWatchdog.Health.failing(List.of("3 Shopify webhook event(s) unprocessed")));

        minutes(w, 1);
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled");
        assertThat(dumps.get()).isEqualTo(1);
        assertThat(restarts.get()).isEqualTo(1);

        minutes(w, 4);                                   // +4 min after the restart: still the same incident
        assertThat(alerts).hasSize(1);
        assertThat(restarts.get()).isEqualTo(1);

        minutes(w, 1);                                   // +5 min: escalation
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled",
                                           "HIGH · Traced job server still stalled after restart");
        assertThat(dumps.get()).isEqualTo(2);
        assertThat(restarts.get()).isEqualTo(1);         // no second restart

        minutes(w, 30);                                  // de-duplicated: nothing more while it lasts
        assertThat(alerts).hasSize(2);
        assertThat(dumps.get()).isEqualTo(2);
        assertThat(restarts.get()).isEqualTo(1);
    }

    @Test
    void w2b_restartFixesIt_noEscalation() {
        JobServerWatchdog w = watchdog(Duration.ZERO);
        health.set(JobServerWatchdog.Health.failing(List.of("this server's heartbeat is stale")));
        minutes(w, 1);
        health.set(JobServerWatchdog.Health.ok());
        minutes(w, 20);
        assertThat(alerts).hasSize(1);
        assertThat(restarts.get()).isEqualTo(1);
    }

    @Test
    void w3_recoveredThenFailingAgain_isANewIncident() {
        JobServerWatchdog w = watchdog(Duration.ZERO);
        health.set(JobServerWatchdog.Health.failing(List.of("no 'bosta-discovery-poll' job created")));
        minutes(w, 1);
        health.set(JobServerWatchdog.Health.ok());
        minutes(w, 3);
        health.set(JobServerWatchdog.Health.failing(List.of("no 'bosta-discovery-poll' job created")));
        minutes(w, 1);
        assertThat(alerts).containsExactly("HIGH · Traced job server stalled", "HIGH · Traced job server stalled");
        assertThat(restarts.get()).isEqualTo(2);
    }

    @Test
    void w4_oneCheckError_isIgnored_twoInARow_openTheIncident() {
        JobServerWatchdog w = watchdog(Duration.ZERO);
        health.set(JobServerWatchdog.Health.checkFailed("watchdog DB check failed"));
        minutes(w, 1);
        assertThat(alerts).isEmpty();
        assertThat(restarts.get()).isZero();

        health.set(JobServerWatchdog.Health.ok());       // blip over
        minutes(w, 1);
        health.set(JobServerWatchdog.Health.checkFailed("watchdog DB check failed"));
        minutes(w, 1);
        assertThat(alerts).isEmpty();                    // count restarted after the healthy tick

        minutes(w, 1);                                   // second in a row
        assertThat(alerts).hasSize(1);
        assertThat(restarts.get()).isEqualTo(1);
    }

    @Test
    void w5_startupGrace_noCheck() {
        JobServerWatchdog w = watchdog(Duration.ofMinutes(5));
        health.set(JobServerWatchdog.Health.failing(List.of("anything")));
        minutes(w, 4);
        assertThat(probes.get()).isZero();
        assertThat(alerts).isEmpty();
        minutes(w, 1);
        assertThat(alerts).hasSize(1);
    }

    @Test
    void w6_throwingProbe_countsAsCheckError_andNeverEscapes() {
        JobServerWatchdog w = new JobServerWatchdog(
            now -> { throw new IllegalStateException("pool timeout"); },
            () -> { restarts.incrementAndGet(); return "restarted"; },
            (subject, body) -> alerts.add(subject),
            () -> "dump", clock, Duration.ZERO, Duration.ofMinutes(5));
        minutes(w, 1);
        assertThat(alerts).isEmpty();
        minutes(w, 1);
        assertThat(alerts).hasSize(1);
        assertThat(restarts.get()).isEqualTo(1);
    }
}
