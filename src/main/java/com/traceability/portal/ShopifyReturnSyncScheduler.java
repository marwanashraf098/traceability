package com.traceability.portal;

import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Shopify-native returns S5a — "something changed on this request; do its next Shopify step". Enqueues
 * {@link ShopifyReturnSyncJob#sync} AFTER the caller's transaction commits (a rollback means no job), only for a
 * tenant listed in TRACED_SHOPIFY_RETURNS_SYNC_TENANTS — for every other tenant it does nothing at all (golden).
 * The 2-minute sweep catches anything a nudge missed.
 */
@Component
public class ShopifyReturnSyncScheduler {

    private final JobScheduler jobScheduler;
    private final ShopifyReturnSyncRollout rollout;

    public ShopifyReturnSyncScheduler(JobScheduler jobScheduler, ShopifyReturnSyncRollout rollout) {
        this.jobScheduler = jobScheduler;
        this.rollout = rollout;
    }

    public void nudge(UUID tenantId, UUID requestId) {
        if (tenantId == null || requestId == null || !rollout.listed(tenantId)) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(tenantId, requestId);
                }
            });
        } else {
            enqueue(tenantId, requestId);
        }
    }

    private void enqueue(UUID tenantId, UUID requestId) {
        jobScheduler.<ShopifyReturnSyncJob>enqueue(job -> job.sync(requestId, tenantId));
    }
}
