package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * app/scopes_update — the merchant approved (or lost) access scopes (managed install: Shopify asks for new scopes the
 * next time the merchant opens the app, then sends this). Called by ShopifyWebhookProcessorJob inside the event's
 * tenant, with no transaction open.
 *
 *   1. stores.access_token_scopes ← the token's live scopes (fetchGrantedScopes), else the payload's "current" list;
 *      the order-PII tier cache is dropped so the next import uses the new scopes; the token re-exchange guard
 *      (scopes_awaiting_*) is cleared so the next embedded open may re-exchange once for the new grant;
 *   2. stores.returns_access ← granted / not_granted from those scopes;
 *   3. webhook registration re-runs for the store — ADDITIVE (addMissingTopics: only topics not yet subscribed, the
 *      returns / fulfillment-order topics now that their scope is there);
 *   4. scopes granted → the store's returns backfill is requested.
 * Never writes to Shopify except the webhook subscriptions of step 3.
 */
@Component
public class ShopifyScopesUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(ShopifyScopesUpdateHandler.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifySyncService syncService;
    private final ShopifyGateway shopifyGateway;
    private final ShopifyReturnsSync returnsSync;
    private final ShopifyReturnsStore returnsStore;
    private final RegisterShopifyWebhooksJob register;
    private final ShopifyReturnsBackfill backfill;

    public ShopifyScopesUpdateHandler(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifySyncService syncService,
                                      ShopifyGateway shopifyGateway, ShopifyReturnsSync returnsSync,
                                      ShopifyReturnsStore returnsStore, RegisterShopifyWebhooksJob register,
                                      ShopifyReturnsBackfill backfill) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.syncService = syncService;
        this.shopifyGateway = shopifyGateway;
        this.returnsSync = returnsSync;
        this.returnsStore = returnsStore;
        this.register = register;
        this.backfill = backfill;
    }

    public void handle(UUID tenantId, String shopDomain, JsonNode payload) {
        UUID storeId = syncService.resolveStore(tenantId, shopDomain);
        if (storeId == null) {
            log.warn("app/scopes_update: store not found or disconnected shop={} tenant={}", shopDomain, tenantId);
            return;
        }
        String scopes = null;
        String token = returnsSync.token(tenantId, storeId);
        if (token != null) scopes = shopifyGateway.fetchGrantedScopes(shopDomain, token);
        if (scopes == null) {
            List<String> current = new ArrayList<>();
            for (JsonNode s : payload.path("current")) if (s.isTextual()) current.add(s.asText());
            if (!current.isEmpty()) scopes = String.join(",", current);
        }
        if (scopes == null) {
            log.warn("app/scopes_update for {}: scopes unreadable (no token / no payload list) — nothing changed", shopDomain);
            return;
        }
        String finalScopes = scopes;
        TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.update(
            "UPDATE stores SET access_token_scopes = ?, scopes_awaiting_approval = NULL, scopes_awaiting_since = NULL " +
            "WHERE id = ? AND tenant_id = ?", finalScopes, storeId, tenantId)));
        shopifyGateway.forgetOrderPiiTier(shopDomain);
        boolean granted = ShopifyReturnsScopes.granted(scopes);
        TenantContext.runAs(tenantId, () -> returnsStore.markAccess(storeId, granted));
        log.info("app/scopes_update for {}: scopes refreshed, returns access {}", shopDomain, granted ? "granted" : "not granted");

        RegisterShopifyWebhooksJob.TopicsOutcome topics = register.addMissingTopics(storeId, tenantId);
        log.info("app/scopes_update for {}: webhook topics added {}, deferred {}, failed {}{}", shopDomain, topics.added(),
            topics.deferred(), topics.failed(), topics.skipped() == null ? "" : " (skipped: " + topics.skipped() + ")");
        if (granted) backfill.request(tenantId, storeId);
    }
}
