package com.traceability.integrations.shopify;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ops trigger for the Shopify webhook re-process (ShopifyWebhookReprocessService), without an owner
 * login: {@code SHOPIFY_WEBHOOK_REPROCESS_ON_STARTUP=<tenant ids>|all} (every tenant with an unprocessed
 * event), dry run unless {@code SHOPIFY_WEBHOOK_REPROCESS_APPLY=true}. One job per tenant, enqueued once
 * at startup. Remove both after the run.
 */
@Component
public class ShopifyWebhookReprocessTrigger {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookReprocessTrigger.class);

    private final JdbcTemplate ownerJdbc;
    private final JobScheduler jobScheduler;
    private final ShopifyWebhookReprocessService service;
    private final String onStartup;
    private final boolean apply;

    public ShopifyWebhookReprocessTrigger(@FlywayDataSource DataSource ownerDs, JobScheduler jobScheduler,
                                          ShopifyWebhookReprocessService service,
                                          @Value("${shopify.webhook.reprocess.on-startup:}") String onStartup,
                                          @Value("${shopify.webhook.reprocess.apply:false}") boolean apply) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.jobScheduler = jobScheduler;
        this.service = service;
        this.onStartup = onStartup;
        this.apply = apply;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        final boolean a = apply;
        for (UUID tenantId : tenants()) {
            final UUID t = tenantId;
            jobScheduler.enqueue(() -> service.runAndLog(t, a));
            log.info("Shopify webhook re-process enqueued for tenant {} (apply={})", t, a);
        }
    }

    List<UUID> tenants() {
        String v = onStartup == null ? "" : onStartup.trim();
        if (v.isEmpty()) return List.of();
        if (v.equalsIgnoreCase("all")) {
            return ownerJdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM shopify_webhook_events WHERE processed_at IS NULL", UUID.class);
        }
        List<UUID> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                out.add(UUID.fromString(p));
            } catch (IllegalArgumentException e) {
                log.warn("shopify.webhook.reprocess.on-startup: '{}' is not a tenant id — skipped", p);
            }
        }
        return out;
    }
}
