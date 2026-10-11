package com.traceability.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Returns portal P4c — the on-demand lookup's own Bosta budget, IN FRONT of the shared
 * BostaRateLimiter: one fetch per {@code portal.preconnect.bosta-interval-ms} (5 s) across every
 * tenant, so customers typing old order numbers can never eat the budget polls, prints and bookings
 * share. A caller that would wait longer than its max wait gets {@code false} (the portal answers
 * BUSY) and takes no slot. One JVM — prod runs one instance, like BostaRateLimiter.
 */
@Component
public class PortalBostaBudget {

    private final long intervalNanos;
    private long nextFreeNanos = System.nanoTime();

    public PortalBostaBudget(@Value("${portal.preconnect.bosta-interval-ms:5000}") long intervalMs) {
        this.intervalNanos = Math.max(0, intervalMs) * 1_000_000L;
    }

    /** Takes the next slot if it comes within {@code maxWaitMs} (sleeping until then); false otherwise. */
    public boolean tryAcquire(long maxWaitMs) {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextFreeNanos);
            wait = slot - now;
            if (wait > maxWaitMs * 1_000_000L) return false;
            nextFreeNanos = slot + intervalNanos;
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * P4d — would {@link #tryAcquire} get a slot within {@code maxWaitMs}? Takes nothing (a peek: another
     * lookup may take the slot first — the caller then answers its padded generic not-found).
     */
    public synchronized boolean hasSlotWithin(long maxWaitMs) {
        long wait = Math.max(0, nextFreeNanos - System.nanoTime());
        return wait <= Math.max(0, maxWaitMs) * 1_000_000L;
    }

    /** Test seam: the next call gets a slot at once. */
    public synchronized void reset() {
        nextFreeNanos = System.nanoTime();
    }
}
