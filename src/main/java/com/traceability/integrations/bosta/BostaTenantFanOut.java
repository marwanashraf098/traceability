package com.traceability.integrations.bosta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The polls' per-tenant loop (2026-11-15): tenants processed by up to {@code parallelism} threads at once, so one
 * run's wall time is no longer the sum of every tenant's Bosta round-trips. The shared {@link BostaRateLimiter} still
 * paces every call (per key and globally), so this never sends faster than before — it only stops a run from waiting
 * on one tenant's HTTP latency while the budget sits idle. Each task sets its own tenant context (the callers' work
 * already wraps everything in TenantContext.runAs); nothing is inherited from the job thread. A task's exception is
 * logged and never stops the others. Parallelism 1 = the old sequential loop, on the calling thread.
 *
 * Returns {Bosta calls, limiter wait ms} summed over the tasks (BostaRateLimiter's per-thread counters).
 */
final class BostaTenantFanOut {

    private static final Logger log = LoggerFactory.getLogger(BostaTenantFanOut.class);

    private BostaTenantFanOut() {}

    static <T> long[] forEach(List<T> items, int parallelism, String threadName, Consumer<T> work) {
        AtomicLong calls = new AtomicLong();
        AtomicLong waitNanos = new AtomicLong();
        Consumer<T> counted = item -> {
            BostaRateLimiter.startCounting();
            try {
                work.accept(item);
            } catch (RuntimeException e) {
                log.warn("{}: a tenant's work failed: {}", threadName, e.toString());
            } finally {
                long[] c = BostaRateLimiter.stopCounting();
                calls.addAndGet(c[0]);
                waitNanos.addAndGet(c[1]);
            }
        };
        if (parallelism <= 1 || items.size() <= 1) {
            for (T item : items) {
                if (Thread.currentThread().isInterrupted()) break;
                counted.accept(item);
            }
            return new long[]{calls.get(), waitNanos.get() / 1_000_000L};
        }
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, items.size()), r -> {
            Thread t = new Thread(r, threadName + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (T item : items) futures.add(pool.submit(() -> counted.accept(item)));
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    log.warn("{}: a tenant's work failed: {}", threadName, String.valueOf(e.getCause()));
                }
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
        return new long[]{calls.get(), waitNanos.get() / 1_000_000L};
    }
}
