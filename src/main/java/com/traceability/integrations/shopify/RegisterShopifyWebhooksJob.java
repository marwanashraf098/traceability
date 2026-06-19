package com.traceability.integrations.shopify;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Registers all FR-3.3 webhook topics for a newly connected shop.
 * Enqueued by ShopifyOAuthService after every successful link/provision — safe to re-run.
 *
 * TenantContext is set explicitly (TenantContext.runAs) because JobRunr workers run
 * outside the HTTP filter chain and the ThreadLocal is not propagated.
 *
 * Idempotency: Shopify rejects duplicate topic+url with "already taken".
 * ShopifyHttpGateway.registerWebhook() treats that as success.
 */
@Component
public class RegisterShopifyWebhooksJob {

    private static final Logger log = LoggerFactory.getLogger(RegisterShopifyWebhooksJob.class);

    private static final List<String> TOPICS = List.of(
        "orders/create",
        "orders/updated",
        "orders/cancelled",
        "products/create",
        "products/update",
        "app/uninstalled"
    );

    private final JdbcTemplate jdbc;
    private final ShopifyGateway shopifyGateway;
    private final ShopifyTokenProvider tokenProvider;
    private final TransactionTemplate tx;
    private final String webhookBaseUrl;

    public RegisterShopifyWebhooksJob(JdbcTemplate jdbc,
                                       ShopifyGateway shopifyGateway,
                                       ShopifyTokenProvider tokenProvider,
                                       PlatformTransactionManager txm,
                                       @Value("${shopify.webhook-base-url}") String webhookBaseUrl) {
        this.jdbc          = jdbc;
        this.shopifyGateway = shopifyGateway;
        this.tokenProvider  = tokenProvider;
        this.tx             = new TransactionTemplate(txm);
        this.webhookBaseUrl = webhookBaseUrl;
    }

    @Job(name = "Register Shopify webhooks — store %0")
    public void run(UUID storeId, UUID tenantId) {
        TenantContext.runAs(tenantId, (Runnable) () -> {
            String shopDomain = tx.execute(s ->
                jdbc.query(
                    "SELECT shop_domain FROM stores WHERE id = ? AND status IN ('connected','needs_reauth')",
                    rs -> rs.next() ? rs.getString(1) : null,
                    storeId));

            if (shopDomain == null) {
                log.info("Skipping webhook registration: store {} not found or not connected", storeId);
                return;
            }

            String rawToken;
            try {
                rawToken = tokenProvider.getValidToken(storeId);
            } catch (ShopifyStoreNeedsReauthException e) {
                log.warn("Skipping webhook registration for store {} — store requires reauth: {}",
                    storeId, e.getMessage());
                return;
            } catch (ShopifyTransientException e) {
                log.error("Skipping webhook registration for store {} — transient token refresh failure: {}",
                    storeId, e.getMessage());
                return;
            }

            for (String topic : TOPICS) {
                String callbackUrl = webhookBaseUrl + "/webhooks/shopify/" + topic;
                try {
                    shopifyGateway.registerWebhook(shopDomain, rawToken, topic, callbackUrl);
                } catch (ShopifyException e) {
                    log.error("Failed to register webhook topic={} for store {}: {}", topic, storeId, e.getMessage());
                    // Continue registering remaining topics — partial failure is better than no registration
                }
            }

            log.info("Webhook registration complete for store {} ({})", storeId, shopDomain);
        });
    }
}
