package com.traceability.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs work after the current transaction commits, on a virtual thread (2026-10-04).
 *
 * Spring calls afterCommit() while the transaction's DB connection is still bound to the thread, so a
 * JobRunr enqueue made there (a write on the owner pool) held an app connection for as long as the owner
 * pool made it wait. Handing it to a virtual thread lets the caller's connection go back to the pool at
 * once. No transaction → runs now, on a virtual thread too. A failure goes to {@code onFailure} (the
 * caller records it so the work isn't silently lost).
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private AfterCommit() {}

    public static void runAsync(Runnable task, Consumer<Throwable> onFailure) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.warn("After-commit task failed: {}", t.toString());
                try { onFailure.accept(t); }
                catch (Throwable t2) { log.warn("After-commit failure handler failed too: {}", t2.toString()); }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { EXECUTOR.submit(guarded); }
            });
        } else {
            EXECUTOR.submit(guarded);
        }
    }
}
