package com.traceability.integrations.bosta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Bosta request budget, shared by EVERY Bosta caller in this JVM — discovery, status poll, webhook
 * verify-by-fetch, fulfillment link jobs, catch-up, visibility check, AWB / waybill printing, pickup
 * and return / exchange booking. Two layers, both token buckets:
 *
 *   per key (2026-10-03)  {@code bosta.rate-limit.per-second} (1) with a burst of
 *                         {@code bosta.rate-limit.burst} (2) for each API key (= tenant);
 *   global (2026-10-04)   {@code bosta.rate-limit.global-per-second} (0.75) with a burst of
 *                         {@code bosta.rate-limit.global-burst} (3) for ALL keys together. Prod showed
 *                         Bosta's limit is per server, not per key (2026-10-04 00:28:59: two keys 429'd in
 *                         the same second at ~1.5 req/s combined, two idle keys were refused on their
 *                         first call, and everyone was let back in together 300 s later).
 *
 * A request takes a token from its key's bucket, then from the global bucket.
 * {@link Priority#USER_FACING} callers (waybill print, booking, pickup, connect) go ahead of waiting
 * {@link Priority#BACKGROUND} callers in both buckets, and background work only takes a global token
 * while at least {@code bosta.rate-limit.background-reserve} (20%) of the global burst stays behind —
 * so a print never queues behind a poll. A 429 on ANY key ({@link #onRateLimited}) blocks that key AND
 * the global bucket — every key — for its retry-after, and is logged with it. A caller that would wait
 * longer than {@code bosta.rate-limit.max-wait-ms} (60 s) gets a {@link BostaRateLimitException} with the
 * remaining wait instead — the same signal a real 429 gives; jobs reschedule on it, never fail.
 *
 * Keys are held as SHA-256 hashes, never raw (logs show the first 8 hex). One JVM: two app instances
 * would each have their own budget (prod runs one).
 */
@Component
public class BostaRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(BostaRateLimiter.class);

    public enum Priority { USER_FACING, BACKGROUND }

    private final double perSecond;
    private final double burst;
    private final long   maxWaitMs;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    /** Null = no global layer (the 3-argument constructor, used by hand-wired tests). */
    private final Bucket global;

    /** Per-key layer only — no global budget. */
    public BostaRateLimiter(double perSecond, double burst, long maxWaitMs) {
        this(perSecond, burst, maxWaitMs, 0, 1, 0);
    }

    @Autowired
    public BostaRateLimiter(@Value("${bosta.rate-limit.per-second:1}") double perSecond,
                            @Value("${bosta.rate-limit.burst:2}") double burst,
                            @Value("${bosta.rate-limit.max-wait-ms:60000}") long maxWaitMs,
                            @Value("${bosta.rate-limit.global-per-second:0.75}") double globalPerSecond,
                            @Value("${bosta.rate-limit.global-burst:3}") double globalBurst,
                            @Value("${bosta.rate-limit.background-reserve:0.2}") double backgroundReserve) {
        this.perSecond = perSecond;
        this.burst = Math.max(1, burst);
        this.maxWaitMs = maxWaitMs;
        double gBurst = Math.max(1, globalBurst);
        this.global = globalPerSecond > 0
            ? new Bucket(globalPerSecond, gBurst, Math.max(0, backgroundReserve) * gBurst)
            : null;
    }

    /** No limit at all — for hand-wired gateways in tests. */
    public static BostaRateLimiter unlimited() {
        return new BostaRateLimiter(0, 1, 0);
    }

    /** Blocks until this key — and the server as a whole — may send one request. */
    public void acquire(String apiKey, Priority priority) {
        if (apiKey == null) return;
        long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;
        if (perSecond > 0) bucket(apiKey).acquire(priority, deadline);
        if (global != null) global.acquire(priority, deadline);
    }

    /** Bosta answered 429 (with any key): nobody sends — with this key or any other — until retry-after has passed. */
    public void onRateLimited(String apiKey, long retryAfterSeconds) {
        onRateLimited(apiKey, retryAfterSeconds, "bosta");
    }

    public void onRateLimited(String apiKey, long retryAfterSeconds, String context) {
        long secs = Math.max(1, retryAfterSeconds);
        if (apiKey != null) bucket(apiKey).block(secs * 1000L);
        if (global != null) global.block(secs * 1000L);
        log.warn("Bosta 429 ({}): retry after {}s — key {} {} blocked until {}", context, secs,
            apiKey == null ? "-" : hash(apiKey).substring(0, 8),
            global != null ? "and ALL keys" : "", Instant.now().plusSeconds(secs));
    }

    private Bucket bucket(String apiKey) {
        return buckets.computeIfAbsent(hash(apiKey), k -> new Bucket(perSecond, burst, 0));
    }

    private static String hash(String apiKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Bucket {
        private final double rate;
        private final double capacity;
        /** Tokens a BACKGROUND caller must leave behind (user-facing headroom). */
        private final double reserve;
        private double tokens;
        private long   lastRefillNanos = System.nanoTime();
        private long   blockedUntilNanos = 0;
        private int    userWaiting = 0;

        Bucket(double rate, double capacity, double reserve) {
            this.rate = rate;
            this.capacity = capacity;
            this.reserve = Math.min(reserve, Math.max(0, capacity - 1));
            this.tokens = capacity;
        }

        synchronized void acquire(Priority priority, long deadline) {
            boolean user = priority == Priority.USER_FACING;
            double need = user ? 1 : 1 + reserve;
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
                    } else if (tokens >= need) {
                        tokens -= 1;
                        return;
                    } else {
                        waitNanos = (long) ((need - tokens) / rate * 1_000_000_000L);
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
            double add = (now - lastRefillNanos) / 1_000_000_000.0 * rate;
            if (add > 0) {
                tokens = Math.min(capacity, tokens + add);
                lastRefillNanos = now;
            }
        }
    }
}
