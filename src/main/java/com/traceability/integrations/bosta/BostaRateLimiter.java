package com.traceability.integrations.bosta;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One request budget per Bosta API key (= per tenant), shared by EVERY Bosta caller in this JVM —
 * discovery, status poll, webhook verify-by-fetch, fulfillment link jobs, catch-up, visibility
 * check, AWB / waybill printing, pickup and return / exchange booking (2026-10-03).
 *
 * Token bucket: {@code bosta.rate-limit.per-second} requests per second (default 1) with a burst of
 * {@code bosta.rate-limit.burst} (default 2). A 429's retry-after blocks the key for everyone until it
 * passes ({@link #onRateLimited}). {@link Priority#USER_FACING} callers (waybill print, booking,
 * pickup, connect) go ahead of waiting {@link Priority#BACKGROUND} callers (polls, link jobs). A caller
 * that would wait longer than {@code bosta.rate-limit.max-wait-ms} (default 60 s) gets a
 * {@link BostaRateLimitException} instead — the same signal a real 429 gives, handled the same way.
 *
 * Keys are held as SHA-256 hashes, never raw. One JVM: two app instances would each have their own
 * budget (prod runs one).
 */
@Component
public class BostaRateLimiter {

    public enum Priority { USER_FACING, BACKGROUND }

    private final double perSecond;
    private final double burst;
    private final long   maxWaitMs;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public BostaRateLimiter(@Value("${bosta.rate-limit.per-second:1}") double perSecond,
                            @Value("${bosta.rate-limit.burst:2}") double burst,
                            @Value("${bosta.rate-limit.max-wait-ms:60000}") long maxWaitMs) {
        this.perSecond = perSecond;
        this.burst = Math.max(1, burst);
        this.maxWaitMs = maxWaitMs;
    }

    /** No limit at all — for hand-wired gateways in tests. */
    public static BostaRateLimiter unlimited() {
        return new BostaRateLimiter(0, 1, 0);
    }

    /** Blocks until this key may send one request. */
    public void acquire(String apiKey, Priority priority) {
        if (perSecond <= 0 || apiKey == null) return;
        bucket(apiKey).acquire(priority);
    }

    /** Bosta answered 429: nobody sends with this key until retry-after has passed. */
    public void onRateLimited(String apiKey, long retryAfterSeconds) {
        if (apiKey == null) return;
        bucket(apiKey).block(Math.max(1, retryAfterSeconds) * 1000L);
    }

    private Bucket bucket(String apiKey) {
        return buckets.computeIfAbsent(hash(apiKey), k -> new Bucket());
    }

    private static String hash(String apiKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final class Bucket {
        private double tokens = burst;
        private long   lastRefillNanos = System.nanoTime();
        private long   blockedUntilNanos = 0;
        private int    userWaiting = 0;

        synchronized void acquire(Priority priority) {
            long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;
            boolean user = priority == Priority.USER_FACING;
            if (user) userWaiting++;
            try {
                while (true) {
                    long now = System.nanoTime();
                    refill(now);
                    long waitNanos;
                    if (now < blockedUntilNanos) {
                        waitNanos = blockedUntilNanos - now;
                    } else if (!user && userWaiting > 0) {
                        waitNanos = 20_000_000L;   // let user-facing callers go first
                    } else if (tokens >= 1) {
                        tokens -= 1;
                        return;
                    } else {
                        waitNanos = (long) ((1 - tokens) / perSecond * 1_000_000_000L);
                    }
                    if (now + waitNanos > deadline) {
                        long retryAfterSec = Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
                        throw new BostaRateLimitException(retryAfterSec);
                    }
                    try {
                        long ms = Math.max(1, waitNanos / 1_000_000L);
                        wait(ms, 0);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BostaRateLimitException(1);
                    }
                }
            } finally {
                if (user) userWaiting--;
                notifyAll();
            }
        }

        synchronized void block(long millis) {
            long until = System.nanoTime() + millis * 1_000_000L;
            if (until > blockedUntilNanos) blockedUntilNanos = until;
            tokens = 0;
            notifyAll();
        }

        private void refill(long now) {
            double add = (now - lastRefillNanos) / 1_000_000_000.0 * perSecond;
            if (add > 0) {
                tokens = Math.min(burst, tokens + add);
                lastRefillNanos = now;
            }
        }
    }
}
