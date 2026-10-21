package com.traceability.integrations.shopify;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Daily at 04:40 Cairo: {@link ShopifyWebhookRetention#run()} — clear of the 03:30 / 03:45 / 03:50 / 04:00 / 05:15
 * nightly jobs. Registered only where the JobRunr server runs (tests call the service directly).
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ShopifyWebhookRetentionJob {

    private final ShopifyWebhookRetention retention;

    public ShopifyWebhookRetentionJob(ShopifyWebhookRetention retention) {
        this.retention = retention;
    }

    @Recurring(id = "shopify-webhook-retention", cron = "40 4 * * *", zoneId = "Africa/Cairo")
    @Job(name = "Shopify webhook events retention", retries = 0)
    public void run() {
        retention.run();
    }
}
