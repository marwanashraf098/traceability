package com.traceability.portal;

import org.jobrunr.jobs.annotations.Job;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Shopify-native returns S5a — one request's next Shopify step. NO JobRunr retry: every write is one attempt. */
@Component
public class ShopifyReturnSyncJob {

    private final ShopifyReturnSyncService service;

    public ShopifyReturnSyncJob(ShopifyReturnSyncService service) {
        this.service = service;
    }

    @Job(name = "Shopify return sync", retries = 0)
    public void sync(UUID requestId, UUID tenantId) {
        service.sync(tenantId, requestId);
    }
}
