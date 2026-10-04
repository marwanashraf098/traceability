package com.traceability.integrations.bosta;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BACKGROUND callers wait much less than user-facing ones (2026-10-04, B4): a JobRunr worker never
 * sits for a minute on the limiter — it gets the rate-limit signal and reschedules.
 *
 *   bw1 while Bosta's retry-after blocks the server, a BACKGROUND caller is refused after the
 *       background max wait (with the remaining block as retry-after); a USER_FACING caller waits it out
 *   bw2 the per-thread user-facing override gets the user-facing wait
 */
class BostaBackgroundWaitTest {

    @Test
    void bw1_backgroundRefusedFast_userFacingWaits() {
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 5_000, 100, 5, 0.2, 200);
        limiter.onRateLimited("key-femine", 2, "test");

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> limiter.acquire("key-broek", BostaRateLimiter.Priority.BACKGROUND))
            .isInstanceOfSatisfying(BostaRateLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 2L));
        assertThat((System.nanoTime() - t0) / 1_000_000).as("refused at once, not after the user-facing 5 s").isLessThan(500);

        long t1 = System.nanoTime();
        limiter.acquire("key-print", BostaRateLimiter.Priority.USER_FACING);
        assertThat((System.nanoTime() - t1) / 1_000_000).as("user-facing waited the block out").isGreaterThan(1_500);
    }

    @Test
    void bw2_userFacingOverride_getsTheLongWait() {
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 5_000, 100, 5, 0.2, 200);
        limiter.onRateLimited("key-femine", 1, "test");

        long t0 = System.nanoTime();
        BostaRateLimiter.userFacing(() -> { limiter.acquire("key-pack", BostaRateLimiter.Priority.BACKGROUND); return null; });
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThan(700);
    }
}
