package com.traceability.portal;

import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Returns portal Step 4c-3 — enqueues {@link ReturnPickupBookingJob#book} for one request.
 *
 * {@link #enqueueAfterCommit}: inside a Spring-managed transaction the job is enqueued only
 * after that transaction COMMITS (JobRunr writes its own storage on the owner pool, so a plain
 * enqueue inside the transaction would survive a rollback, or run before the approval is
 * visible). A rollback therefore means no job. Outside a transaction it enqueues immediately.
 */
@Component
public class PickupBookingScheduler {

    private final JobScheduler jobScheduler;

    public PickupBookingScheduler(JobScheduler jobScheduler) {
        this.jobScheduler = jobScheduler;
    }

    public void enqueue(UUID requestId, UUID tenantId) {
        jobScheduler.<ReturnPickupBookingJob>enqueue(job -> job.book(requestId, tenantId));
    }

    public void enqueueAfterCommit(UUID requestId, UUID tenantId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(requestId, tenantId);
                }
            });
        } else {
            enqueue(requestId, tenantId);
        }
    }
}
