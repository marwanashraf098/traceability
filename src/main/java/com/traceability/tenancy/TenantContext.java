package com.traceability.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Carries the current tenant UUID for the duration of a request thread.
 *
 * THREADING: this is a ThreadLocal — it does NOT propagate across @Async
 * methods, executor-submitted tasks, parallel streams, or CompletableFuture
 * chains. Any background work that touches tenant data must be wrapped in
 * {@link #runAs} so the context is explicitly set and cleared.
 *
 * RUNAS RESTORES (2026-10-04): runAs saves the tenant already on the thread, sets its own, and
 * restores the saved one when it finishes (removes it when there was none) — even on an
 * exception. It used to CLEAR, so any runAs nested in a request, a job or another runAs left the
 * rest of that work with no tenant: new transactions had no GUC (RLS returned nothing, UPDATEs
 * hit no rows) and require() threw. That broke the S2 print-batch recording (worked around) and
 * the Bosta fulfillment link in production (2026-10-03, hotfixed).
 *
 * TENANT SWITCH GUARD: the database tenant (the GUC) is fixed when a transaction begins
 * (TenantAwareConnection), so a runAs that switches to a DIFFERENT tenant while a transaction is
 * already active cannot take effect for that transaction's statements. That is reported per
 * {@link SwitchPolicy}: WARN (production default — a log line, then carry on) or THROW (tests).
 * Set from {@code tenancy.runas-switch-guard} (warn | throw) by {@link TenantContextSettings}.
 * Re-setting the same tenant is a no-op either way.
 */
public final class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);

    private static final ThreadLocal<UUID> HOLDER = new ThreadLocal<>();

    /** What runAs does when it switches tenant inside an active transaction. */
    public enum SwitchPolicy { WARN, THROW }

    private static volatile SwitchPolicy switchPolicy = SwitchPolicy.WARN;

    public static void setSwitchPolicy(SwitchPolicy policy) {
        switchPolicy = policy == null ? SwitchPolicy.WARN : policy;
    }

    public static SwitchPolicy switchPolicy() {
        return switchPolicy;
    }

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
     * Runs {@code task} with {@code tenantId} as the tenant, then restores the tenant that was
     * there before (or none) — guaranteed even on exception. RuntimeExceptions propagate as they
     * are; a checked exception is wrapped in a RuntimeException. Use for background jobs and any
     * code path that does not go through the HTTP filter chain.
     */
    public static <T> T runAs(UUID tenantId, Callable<T> task) {
        UUID previous = enter(tenantId);
        try {
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            restore(previous);
        }
    }

    /** Void overload of {@link #runAs}. */
    public static void runAs(UUID tenantId, Runnable task) {
        UUID previous = enter(tenantId);
        try {
            task.run();
        } finally {
            restore(previous);
        }
    }

    private static UUID enter(UUID tenantId) {
        UUID previous = HOLDER.get();
        if (previous != null && !previous.equals(tenantId)
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            String msg = "TenantContext.runAs switched tenant " + shortId(previous) + " -> " + shortId(tenantId)
                + " inside an active transaction (its GUC stays " + shortId(previous) + ") at " + caller();
            if (switchPolicy == SwitchPolicy.THROW) throw new IllegalStateException(msg);
            log.warn(msg);
        }
        HOLDER.set(tenantId);
        return previous;
    }

    private static void restore(UUID previous) {
        if (previous == null) HOLDER.remove();
        else HOLDER.set(previous);
    }

    private static String shortId(UUID id) {
        return id == null ? "none" : id.toString().substring(0, 8);
    }

    /** The first stack frame outside this class — class.method:line. */
    private static String caller() {
        return StackWalker.getInstance().walk(frames -> frames
            .filter(f -> !f.getClassName().equals(TenantContext.class.getName()))
            .findFirst()
            .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
                + "." + f.getMethodName() + ":" + f.getLineNumber())
            .orElse("unknown"));
    }
}
