package com.traceability.tenancy;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Carries the current tenant UUID for the duration of a request thread.
 *
 * THREADING: this is a ThreadLocal — it does NOT propagate across @Async
 * methods, executor-submitted tasks, parallel streams, or CompletableFuture
 * chains. Any background work that touches tenant data must be wrapped in
 * {@link #runAs} so the context is explicitly set and cleared.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        HOLDER.set(tenantId);
    }

    public static UUID get() {
        return HOLDER.get();
    }

    /** Returns the current tenant or throws if no context is set. */
    public static UUID require() {
        UUID id = HOLDER.get();
        if (id == null) throw new IllegalStateException("No tenant context set on this thread");
        return id;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * Sets the tenant context, runs {@code task}, then clears — guaranteed even on exception.
     * Use for background jobs and any code path that does not go through the HTTP filter chain.
     */
    public static <T> T runAs(UUID tenantId, Callable<T> task) {
        set(tenantId);
        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            clear();
        }
    }

    /** Void overload of {@link #runAs}. */
    public static void runAs(UUID tenantId, Runnable task) {
        set(tenantId);
        try {
            task.run();
        } finally {
            clear();
        }
    }
}
