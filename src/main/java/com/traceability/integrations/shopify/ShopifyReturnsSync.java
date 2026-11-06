package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify-native returns S1 — fetches a Return / FulfillmentOrder by GID (READ ONLY) and upserts its full current
 * state through {@link ShopifyReturnsStore}. A webhook is only a signal; the state always comes from this fetch.
 *
 * No DB transaction is open during a Shopify call: the token read and every upsert run in their own short
 * transactions, each inside {@code TenantContext.runAs(tenantId, …)} (the caller may have no tenant context — the
 * 2026-10-11 portal bug).
 *
 * Graceful degrade: ACCESS_DENIED (the store hasn't approved read_returns / read_merchant_managed_fulfillment_orders)
 * marks the store returns_access = 'not_granted' and returns {@link Outcome#ACCESS_DENIED} — no exception, no retry,
 * no alert; logged once when the state changes. A successful read marks it 'granted'. A store that needs re-auth is
 * skipped quietly. Any other failure (5xx, timeout, GraphQL error) is thrown, so the webhook processor's existing
 * backoff retries it.
 */
@Component
public class ShopifyReturnsSync {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReturnsSync.class);

    public enum Outcome { SYNCED, NOT_IN_TRACED, NOT_FOUND, ACCESS_DENIED, NO_TOKEN }

    private final ShopifyReturnsGateway gateway;
    private final ShopifyReturnsStore store;
    private final ShopifyTokenProvider tokenProvider;

    public ShopifyReturnsSync(ShopifyReturnsGateway gateway, ShopifyReturnsStore store, ShopifyTokenProvider tokenProvider) {
        this.gateway = gateway;
        this.store = store;
        this.tokenProvider = tokenProvider;
    }

    /** S3b: shadow orders are reconciled after every return / fulfillment-order upsert (post-commit). */
    private com.traceability.inventory.ShopifyShadowOrders shadows;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setShadows(com.traceability.inventory.ShopifyShadowOrders shadows) { this.shadows = shadows; }

    private void reconcileShadows(UUID orderId) {
        if (shadows != null) shadows.reconcileQuietly(orderId);
    }

    /** The store's token, or null when it needs re-auth / can't be had right now (quiet skip). */
    public String token(UUID tenantId, UUID storeId) {
        try {
            return TenantContext.runAs(tenantId, () -> tokenProvider.getValidToken(storeId));
        } catch (ShopifyStoreNeedsReauthException | ShopifyStoreDisconnectedException e) {
            log.info("Shopify returns sync: store {} has no usable token ({}) — skipped", storeId, e.getClass().getSimpleName());
            return null;
        }
    }

    public Outcome syncReturn(UUID tenantId, UUID storeId, String shopDomain, String returnGid) {
        String token = token(tenantId, storeId);
        return token == null ? Outcome.NO_TOKEN : syncReturn(tenantId, storeId, shopDomain, token, returnGid);
    }

    public Outcome syncReturn(UUID tenantId, UUID storeId, String shopDomain, String token, String returnGid) {
        return syncReturn(tenantId, storeId, shopDomain, token, returnGid, true);
    }

    /**
     * withFulfillmentOrders (S3b Gap A): a return with exchange lines also re-reads its order's fulfillment orders —
     * returns/* never names them and orders/updated only refreshes FOs already mirrored, so the exchange FO would
     * otherwise arrive only with a fulfillment_orders/* webhook. syncOrder passes false (it syncs the FOs itself).
     */
    Outcome syncReturn(UUID tenantId, UUID storeId, String shopDomain, String token, String returnGid,
                       boolean withFulfillmentOrders) {
        JsonNode ret;
        try {
            ret = gateway.fetchReturn(shopDomain, token, returnGid);
        } catch (ShopifyReturnsAccessDeniedException e) {
            return denied(tenantId, storeId, shopDomain);
        }
        granted(tenantId, storeId, shopDomain);
        if (ret == null) return Outcome.NOT_FOUND;
        String orderGid = ret.path("order").path("id").asText("");
        UUID orderId = TenantContext.runAs(tenantId, () -> store.tracedOrderId(storeId, orderGid));
        if (orderId == null) {
            log.debug("Shopify return {} on {}: order {} isn't in Traced — ignored", returnGid, shopDomain, orderGid);
            return Outcome.NOT_IN_TRACED;
        }
        TenantContext.runAs(tenantId, () -> store.upsertReturn(storeId, orderId, ret));
        boolean hasExchangeLines = ret.path("exchangeLineItems").path("nodes").size() > 0;
        if (withFulfillmentOrders && hasExchangeLines) {
            Outcome o = syncOrderFulfillmentOrders(tenantId, storeId, shopDomain, token, orderGid);
            if (o == Outcome.ACCESS_DENIED) return o;
        }
        TenantContext.runAs(tenantId, () -> { reconcileShadows(orderId); return null; });
        return Outcome.SYNCED;
    }

    public Outcome syncFulfillmentOrder(UUID tenantId, UUID storeId, String shopDomain, String foGid) {
        String token = token(tenantId, storeId);
        return token == null ? Outcome.NO_TOKEN : syncFulfillmentOrder(tenantId, storeId, shopDomain, token, foGid);
    }

    public Outcome syncFulfillmentOrder(UUID tenantId, UUID storeId, String shopDomain, String token, String foGid) {
        JsonNode fo;
        try {
            fo = gateway.fetchFulfillmentOrder(shopDomain, token, foGid);
        } catch (ShopifyReturnsAccessDeniedException e) {
            return denied(tenantId, storeId, shopDomain);
        }
        granted(tenantId, storeId, shopDomain);
        if (fo == null) return Outcome.NOT_FOUND;
        String orderGid = fo.path("order").path("id").asText("");
        return TenantContext.runAs(tenantId, () -> {
            UUID orderId = store.tracedOrderId(storeId, orderGid);
            if (orderId == null) return Outcome.NOT_IN_TRACED;
            store.upsertFulfillmentOrder(storeId, orderId, fo);
            reconcileShadows(orderId);
            return Outcome.SYNCED;
        });
    }

    /**
     * Every return of one order (and, when it has any, its fulfillment orders) — the reverse-fulfillment-order
     * dispose signal and the backfill. An order Traced doesn't hold is ignored before any fetch.
     */
    public Outcome syncOrder(UUID tenantId, UUID storeId, String shopDomain, String token, String orderGid,
                             List<String> returnGids) {
        UUID orderId = TenantContext.runAs(tenantId, () -> store.tracedOrderId(storeId, orderGid));
        if (orderId == null) return Outcome.NOT_IN_TRACED;
        for (String r : returnGids) {
            Outcome o = syncReturn(tenantId, storeId, shopDomain, token, r, false);
            if (o == Outcome.ACCESS_DENIED) return o;
        }
        if (returnGids.isEmpty()) return Outcome.SYNCED;
        return syncOrderFulfillmentOrders(tenantId, storeId, shopDomain, token, orderGid);
    }

    /** Every fulfillment order of one Shopify order (each upsert reconciles the order's shadows). */
    private Outcome syncOrderFulfillmentOrders(UUID tenantId, UUID storeId, String shopDomain, String token, String orderGid) {
        List<String> fos;
        try {
            fos = gateway.fetchOrderFulfillmentOrderIds(shopDomain, token, orderGid);
        } catch (ShopifyReturnsAccessDeniedException e) {
            return denied(tenantId, storeId, shopDomain);
        }
        for (String fo : fos) {
            Outcome o = syncFulfillmentOrder(tenantId, storeId, shopDomain, token, fo);
            if (o == Outcome.ACCESS_DENIED) return o;
        }
        return Outcome.SYNCED;
    }

    /** reverse_fulfillment_orders/dispose: the RFO's order, then every return of that order (dispositions live there). */
    public Outcome syncByReverseFulfillmentOrder(UUID tenantId, UUID storeId, String shopDomain, String rfoGid) {
        String token = token(tenantId, storeId);
        if (token == null) return Outcome.NO_TOKEN;
        String orderGid;
        Map<String, List<String>> returns;
        try {
            orderGid = gateway.fetchReverseFulfillmentOrderOrderGid(shopDomain, token, rfoGid);
            if (orderGid == null) return Outcome.NOT_FOUND;
            UUID orderId = TenantContext.runAs(tenantId, () -> store.tracedOrderId(storeId, orderGid));
            if (orderId == null) return Outcome.NOT_IN_TRACED;
            returns = gateway.fetchOrderReturnIds(shopDomain, token, List.of(orderGid));
        } catch (ShopifyReturnsAccessDeniedException e) {
            return denied(tenantId, storeId, shopDomain);
        }
        return syncOrder(tenantId, storeId, shopDomain, token, orderGid, returns.getOrDefault(orderGid, List.of()));
    }

    Outcome denied(UUID tenantId, UUID storeId, String shopDomain) {
        boolean changed = TenantContext.runAs(tenantId, () -> store.markAccess(storeId, false));
        if (changed) {
            log.info("Shopify returns: {} hasn't approved the returns / fulfillment-order scopes — returns access: not " +
                "granted; returns sync stops until it does (app/scopes_update)", shopDomain);
        }
        return Outcome.ACCESS_DENIED;
    }

    private void granted(UUID tenantId, UUID storeId, String shopDomain) {
        boolean changed = TenantContext.runAs(tenantId, () -> store.markAccess(storeId, true));
        if (changed) log.info("Shopify returns: {} returns access: granted", shopDomain);
    }
}
