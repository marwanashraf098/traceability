package com.traceability.portal;

import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Shopify-native returns S5a — one request's next Shopify step. NO JobRunr retry: every write is one attempt. */
@Component
public class ShopifyReturnSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReturnSyncJob.class);

    private final ShopifyReturnSyncService service;

    public ShopifyReturnSyncJob(ShopifyReturnSyncService service) {
        this.service = service;
    }

    @Job(name = "Shopify return sync", retries = 0)
    public void sync(UUID requestId, UUID tenantId) {
        // One line per nudge (approve, Try again, …) — a job that ran is visible even when nothing was due.
        log.info("Shopify return sync job tenant={} request={}", tenantId, requestId);
        service.sync(tenantId, requestId);
    }
}
