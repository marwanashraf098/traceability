package com.traceability.integrations.shopify;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time webhook topic backfill (analytics slice 10): when RegisterShopifyWebhooksJob.TOPICS grows,
 * every connected store registered under an older topic set (stores.webhook_topics_version below
 * TOPICS_VERSION) gets the missing topics — ADDITIVE only, existing subscriptions are kept
 * (RegisterShopifyWebhooksJob.addMissingTopics). A store is stamped once nothing failed, so later
 * restarts skip it; a store with a failure is tried again at the next startup.
 *
 * Startup enqueues ONE JobRunr job (only when some store is behind) so startup never waits on
 * Shopify; the job takes the stores one at a time. One log line per store: topics added / already
 * present / failed. Stores are listed on the owner pool (as ShopifyReconcileJob does); each store's
 * work runs under TenantContext.runAs. FAIL-SOFT at startup, like BostaDistrictsRefreshJob.
 * Kill switch: shopify.webhook-topics-backfill.enabled.
 */
@Component
public class ShopifyWebhookTopicsBackfill {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookTopicsBackfill.class);

    static final String BEHIND_SQL =
        "SELECT id, tenant_id, shop_domain FROM stores WHERE status = 'connected' AND webhook_topics_version < ? ORDER BY id";

    private final JdbcTemplate ownerJdbc;
    private final RegisterShopifyWebhooksJob register;
    private final JobScheduler jobScheduler;
    private final boolean enabled;

    public ShopifyWebhookTopicsBackfill(@FlywayDataSource DataSource ownerDs, RegisterShopifyWebhooksJob register,
                                        JobScheduler jobScheduler,
                                        @Value("${shopify.webhook-topics-backfill.enabled:true}") boolean enabled) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.register = register;
        this.jobScheduler = jobScheduler;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) return;
        try {
            Integer behind = ownerJdbc.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE status = 'connected' AND webhook_topics_version < ?",
                Integer.class, RegisterShopifyWebhooksJob.TOPICS_VERSION);
            if (behind != null && behind > 0) {
                jobScheduler.<ShopifyWebhookTopicsBackfill>enqueue(job -> job.runAll());
                log.info("Webhook topics: {} connected store(s) behind topic set v{} — backfill enqueued",
                    behind, RegisterShopifyWebhooksJob.TOPICS_VERSION);
            }
        } catch (Exception e) {
            log.error("Could not check or enqueue the webhook topic backfill at startup — continuing", e);
        }
    }

    @Job(name = "Shopify webhook topic backfill", retries = 0)
    public void runAll() {
        List<Map<String, Object>> stores = ownerJdbc.queryForList(BEHIND_SQL, RegisterShopifyWebhooksJob.TOPICS_VERSION);
        for (Map<String, Object> s : stores) {
            UUID storeId = (UUID) s.get("id");
            String shop = (String) s.get("shop_domain");
            try {
                RegisterShopifyWebhooksJob.TopicsOutcome o = register.addMissingTopics(storeId, (UUID) s.get("tenant_id"));
                if (o.skipped() != null) {
                    log.warn("Webhook topics for {} ({}): skipped — {}", shop, storeId, o.skipped());
                } else {
                    log.info("Webhook topics for {} ({}): added {}, already present {}, failed {}",
                        shop, storeId, o.added(), o.present().size(), o.failed().isEmpty() ? "none" : o.failed());
                }
            } catch (RuntimeException e) {
                log.warn("Webhook topics for {} ({}): failed — {}", shop, storeId, e.getMessage());
            }
        }
    }
}
