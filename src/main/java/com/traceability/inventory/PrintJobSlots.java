package com.traceability.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Merged-PDF slice B — at most {@code traced.print-jobs.max-running} (2) print jobs run at once on this
 * server, across all tenants, so big prints can't crowd out other Bosta calls (each print sends its requests
 * one after another). A job that finds no free slot stays 'queued' ("Waiting for another print to finish")
 * and is rescheduled. One JVM in prod (like BostaRateLimiter); a restart frees every slot.
 */
@Component
public class PrintJobSlots {

    private final Semaphore slots;

    public PrintJobSlots(@Value("${traced.print-jobs.max-running:2}") int maxRunning) {
        this.slots = new Semaphore(Math.max(1, maxRunning));
    }

    public boolean tryAcquire() { return slots.tryAcquire(); }

    public void release() { slots.release(); }

    public int available() { return slots.availablePermits(); }
}
