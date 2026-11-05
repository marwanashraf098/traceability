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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    /**
     * The version of TOPICS. Bump it whenever a topic is added: stores stamped with an older version get
     * the missing topics from ShopifyWebhookTopicsBackfill at the next startup (stores.webhook_topics_version).
     * 1 = slice 10's inventory_items/update + inventory_levels/update.
     * 2 = Shopify-native returns S1: returns/*, reverse_fulfillment_orders/dispose, fulfillment_orders/*,
     *     app/scopes_update. The returns / fulfillment-order topics need their scope (ShopifyReturnsScopes) — a store
     *     that hasn't approved it yet gets them DEFERRED (not attempted, not a failure); app/scopes_update adds them
     *     once it does.
     */
    public static final int TOPICS_VERSION = 2;

    static final List<String> TOPICS = List.of(
        "orders/create",
        "orders/updated",
        "orders/cancelled",
        "products/create",
        "products/update",
        // Analytics slice 10: Shopify cost + stock, READ columns only (needs read_inventory, implied by write_inventory)
        "inventory_items/update",
        "inventory_levels/update",
        "app/uninstalled",
        // Shopify-native returns S1 (handlers in ShopifyReturnsWebhooks / ShopifyScopesUpdateHandler — they shipped
        // with these topics: an unknown topic would be marked processed with nothing done).
        "app/scopes_update",
        "returns/request",
        "returns/approve",
        "returns/decline",
        "returns/cancel",
        "returns/close",
        "returns/reopen",
        "returns/process",
        "returns/update",
        "reverse_fulfillment_orders/dispose",
        "fulfillment_orders/placed_on_hold",
        "fulfillment_orders/hold_released",
        "fulfillment_orders/cancelled",
        "fulfillment_orders/order_routing_complete",
        "fulfillment_orders/split",
        "fulfillment_orders/merged",
        "fulfillment_orders/moved"
    );

    /**
     * The topics this store can't subscribe yet: their scope (ShopifyReturnsScopes.requiredScope) isn't granted.
     * Decided on the token's LIVE scopes when they can be read, else the stored access_token_scopes.
     */
    static java.util.Set<String> deferredTopics(String grantedScopesCsv) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String topic : TOPICS) {
            String scope = ShopifyReturnsScopes.requiredScope(topic);
            if (scope != null && !ShopifyGateway.isScopeGranted(scope, grantedScopesCsv)) out.add(topic);
        }
        return out;
    }

    private String grantedScopes(UUID storeId, String shopDomain, String rawToken) {
        String live = null;
        try {
            live = shopifyGateway.fetchGrantedScopes(shopDomain, rawToken);
        } catch (RuntimeException e) {
            log.debug("Could not read live scopes for {}: {}", shopDomain, e.getMessage());
        }
        if (live != null) return live;
        return tx.execute(s -> jdbc.query("SELECT access_token_scopes FROM stores WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : null, storeId));
    }

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

            // Delete all existing subscriptions pointing to our callback URL before re-registering.
            // This evicts stale subscriptions owned by a prior app install (OAuth or an older
            // custom-app version) that would be signed with a different secret, causing HMAC
            // mismatches on every delivery. Idempotent: list → delete owned → register fresh.
            String callbackBase = webhookBaseUrl + "/webhooks/shopify/";
            try {
                List<ShopifyGateway.WebhookSubscription> existing =
                    shopifyGateway.listWebhookSubscriptions(shopDomain, rawToken);
                for (ShopifyGateway.WebhookSubscription sub : existing) {
                    if (sub.callbackUrl().startsWith(callbackBase)) {
                        try {
                            shopifyGateway.deleteWebhookSubscription(shopDomain, rawToken, sub.gid());
                            log.info("Deleted stale webhook subscription gid={} topic={} shop={}",
                                sub.gid(), sub.topic(), shopDomain);
                        } catch (ShopifyException e) {
                            log.warn("Failed to delete webhook subscription gid={} shop={}: {}",
                                sub.gid(), shopDomain, e.getMessage());
                        }
                    }
                }
            } catch (ShopifyException e) {
                log.warn("Could not list existing webhook subscriptions for store {} — will attempt registration anyway: {}",
                    storeId, e.getMessage());
            }

            boolean allRegistered = true;
            java.util.Set<String> deferred = deferredTopics(grantedScopes(storeId, shopDomain, rawToken));
            if (!deferred.isEmpty()) {
                log.info("Webhook registration for store {}: {} topic(s) deferred until their scope is approved",
                    storeId, deferred.size());
            }
            for (String topic : TOPICS) {
                if (deferred.contains(topic)) continue;
                String callbackUrl = callbackBase + topic;
                try {
                    shopifyGateway.registerWebhook(shopDomain, rawToken, topic, callbackUrl);
                } catch (ShopifyException e) {
                    allRegistered = false;
                    log.error("Failed to register webhook topic={} for store {}: {}", topic, storeId, e.getMessage());
                    // Continue registering remaining topics — partial failure is better than no registration
                }
            }
            if (allRegistered) stampTopicsVersion(storeId);

            log.info("Webhook registration complete for store {} ({})", storeId, shopDomain);
        });
    }

    /**
     * What {@link #addMissingTopics} did for one store. {@code skipped} is set when nothing was attempted;
     * {@code deferred} = topics whose scope the store hasn't approved yet (not attempted, not a failure).
     */
    public record TopicsOutcome(List<String> added, List<String> present, List<String> failed, String skipped,
                                List<String> deferred) {
        public TopicsOutcome(List<String> added, List<String> present, List<String> failed, String skipped) {
            this(added, present, failed, skipped, List.of());
        }
    }

    /**
     * ADDITIVE registration (the one-time topic backfill): lists the store's subscriptions and registers
     * only the topics Traced isn't subscribed to yet. Never deletes a subscription — unlike {@link #run},
     * which evicts and re-creates them all. Stamps stores.webhook_topics_version only when no topic failed,
     * so a store with a failure is tried again at the next startup. Never throws.
     */
    public TopicsOutcome addMissingTopics(UUID storeId, UUID tenantId) {
        return TenantContext.runAs(tenantId, () -> {
            String shopDomain = tx.execute(s -> jdbc.query(
                "SELECT shop_domain FROM stores WHERE id = ? AND status = 'connected'",
                rs -> rs.next() ? rs.getString(1) : null, storeId));
            if (shopDomain == null) return new TopicsOutcome(List.of(), List.of(), List.of(), "store not connected");
            String rawToken;
            try {
                rawToken = tokenProvider.getValidToken(storeId);
            } catch (RuntimeException e) {
                return new TopicsOutcome(List.of(), List.of(), List.of(), "no usable token: " + e.getMessage());
            }
            String callbackBase = webhookBaseUrl + "/webhooks/shopify/";
            Set<String> subscribed = new HashSet<>();
            try {
                for (ShopifyGateway.WebhookSubscription sub : shopifyGateway.listWebhookSubscriptions(shopDomain, rawToken)) {
                    if (sub.callbackUrl() != null && sub.callbackUrl().startsWith(callbackBase)) {
                        subscribed.add(sub.topic().toUpperCase(Locale.ROOT));
                    }
                }
            } catch (RuntimeException e) {
                // Without the list we can't tell what's missing; registering blind is still safe
                // (Shopify answers "already taken" for an existing topic + address, treated as success).
                log.warn("Webhook topics: could not list subscriptions for store {} — registering every topic: {}",
                    storeId, e.getMessage());
            }
            List<String> added = new ArrayList<>(), present = new ArrayList<>(), failed = new ArrayList<>();
            java.util.Set<String> deferred = deferredTopics(grantedScopes(storeId, shopDomain, rawToken));
            for (String topic : TOPICS) {
                if (deferred.contains(topic)) continue;
                if (subscribed.contains(topic.replace("/", "_").toUpperCase(Locale.ROOT))) {
                    present.add(topic);
                    continue;
                }
                try {
                    shopifyGateway.registerWebhook(shopDomain, rawToken, topic, callbackBase + topic);
                    added.add(topic);
                } catch (RuntimeException e) {
                    failed.add(topic + " (" + e.getMessage() + ")");
                }
            }
            if (failed.isEmpty()) stampTopicsVersion(storeId);
            return new TopicsOutcome(added, present, failed, null, List.copyOf(deferred));
        });
    }

    private void stampTopicsVersion(UUID storeId) {
        try {
            tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE stores SET webhook_topics_version = ? WHERE id = ? AND webhook_topics_version < ?",
                TOPICS_VERSION, storeId, TOPICS_VERSION));
        } catch (RuntimeException e) {
            log.warn("Could not stamp webhook_topics_version for store {}: {}", storeId, e.getMessage());
        }
    }
}
