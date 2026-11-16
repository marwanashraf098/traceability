package com.traceability.portal;

import com.traceability.integrations.bosta.CourierSimulation;
import com.traceability.integrations.bosta.PreConnectDeliveryFilter;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 2026-11-16 (Storm 2472106867 "#2739:#2739-R1") — the parent order of a Bosta exchange (30) / customer return
 * pickup (25) that Bosta created AFTER the connect for an order placed BEFORE it, imported on demand from Shopify
 * so the trip can be matched to it and its lines shown as untracked units.
 *
 * The fetch-and-store core of the returns portal's on-demand lookup without the portal's gates: no phone check, no
 * portal Bosta budget, no Bosta call at all, no hourly caps. One Shopify read ({@code findOrdersByName}, the import's
 * PII tier, never email) through the tenant's token; stored by {@link PreConnectOrderStore} — the same
 * 'portal_pre_connect' row shape ('internal:portal:…', untracked lines, outside merchant_orders / warehouse_orders),
 * with no delivery (portal_delivery / portal_delivered_at NULL).
 *
 * Never blocks ingest: every miss / failure returns {@link Outcome#orderId()} null with a reason, logged at INFO
 * (WARN for a Shopify failure) with the tenant and the reason only — never the number, the customer, the token or a
 * Shopify body. Idempotent: an existing pre-connect row of that number is reused without a Shopify call; a concurrent
 * import returns the winner's row. A miss is remembered for {@code preconnect.parent-import.negative-cache-minutes}
 * (30) so later states of the same delivery don't re-ask Shopify.
 *
 * MUST be called with NO transaction open (a Shopify call is made); every DB touch runs in its own short
 * transaction under {@code TenantContext.runAs(tenantId, …)}.
 */
@Service
public class PreConnectParentImport {

    private static final Logger log = LoggerFactory.getLogger(PreConnectParentImport.class);
    /** The same safe-number rule as the portal's on-demand lookup. */
    private static final Pattern SAFE_NUMBER = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    public enum Reason {
        IMPORTED, REUSED,
        NO_NUMBER, INELIGIBLE_STORE, NOT_FOUND, AMBIGUOUS, NOT_PRE_CONNECT, NO_CATALOG_LINES,
        TOKEN_UNAVAILABLE, SHOPIFY_ERROR, CACHED_MISS
    }

    public record Outcome(UUID orderId, Reason reason) {}

    private record Store(UUID id, String shopDomain, Instant cutoff) {}

    private record Expiring(Reason reason, Instant until) {
        boolean live() { return Instant.now().isBefore(until); }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final ShopifyTokenProvider tokens;
    private final Duration negativeTtl;
    private final Map<String, Expiring> negative = new ConcurrentHashMap<>();

    public PreConnectParentImport(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyGateway shopify,
                                  ShopifyTokenProvider tokens,
                                  @org.springframework.beans.factory.annotation.Value(
                                      "${preconnect.parent-import.negative-cache-minutes:30}") long negativeMinutes) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.shopify = shopify;
        this.tokens = tokens;
        this.negativeTtl = Duration.ofMinutes(negativeMinutes);
    }

    /** Test seam: forget remembered misses. */
    public void clearCaches() {
        negative.clear();
    }

    /** The parent of the delivery with this Bosta businessReference (its order-number part), or null + a reason. */
    public Outcome importParent(UUID tenantId, String businessReference) {
        Outcome o = decide(tenantId, businessReference);
        if (o.reason() == Reason.TOKEN_UNAVAILABLE || o.reason() == Reason.SHOPIFY_ERROR) {
            log.warn("Pre-connect parent import: tenant {} → {}", tenantId, o.reason());
        } else {
            log.info("Pre-connect parent import: tenant {} → {}", tenantId, o.reason());
        }
        return o;
    }

    private Outcome decide(UUID tenantId, String businessReference) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PreConnectParentImport must run with no transaction open (it calls Shopify)");
        }
        String number = PreConnectDeliveryFilter.orderNumberPart(businessReference);
        if (number == null || !SAFE_NUMBER.matcher(number).matches()) return new Outcome(null, Reason.NO_NUMBER);

        Object pre = inTenant(tenantId, () -> {
            UUID existing = existingRow(tenantId, number);
            if (existing != null) return existing;
            return store(tenantId);
        });
        if (pre instanceof UUID id) return new Outcome(id, Reason.REUSED);
        if (pre == null) return new Outcome(null, Reason.INELIGIBLE_STORE);
        Store store = (Store) pre;

        String cacheKey = tenantId + "|" + number.toLowerCase(java.util.Locale.ROOT);
        Expiring miss = negative.get(cacheKey);
        if (miss != null && miss.live()) return new Outcome(null, Reason.CACHED_MISS);

        String token;
        try {
            // Under the tenant: the provider reads (and may refresh) the stores row as app_user + RLS.
            token = TenantContext.runAs(tenantId, () -> tokens.getValidToken(store.id()));
        } catch (RuntimeException e) {
            return new Outcome(null, Reason.TOKEN_UNAVAILABLE);
        }
        ShopifyGateway.PortalOrderSearch search;
        try {
            search = shopify.findOrdersByName(store.shopDomain(), token, number);
        } catch (RuntimeException e) {
            return new Outcome(null, Reason.SHOPIFY_ERROR);
        }
        List<ShopifyGateway.Order> exact = search == null || search.orders() == null ? List.of()
            : search.orders().stream().filter(o -> sameNumber(o.name(), number)).toList();
        if (exact.isEmpty()) return remember(cacheKey, Reason.NOT_FOUND);
        if (exact.size() > 1) return remember(cacheKey, Reason.AMBIGUOUS);
        ShopifyGateway.Order order = exact.get(0);
        // Placed at / after the connect → Shopify's own ingest owns it, never a pre-connect row.
        if (order.createdAt() == null || !order.createdAt().isBefore(store.cutoff())) {
            return remember(cacheKey, Reason.NOT_PRE_CONNECT);
        }
        UUID id = inTenant(tenantId, () -> PreConnectOrderStore.store(jdbc, tenantId, store.id(), order, null, null));
        if (id == null) return remember(cacheKey, Reason.NO_CATALOG_LINES);
        return new Outcome(id, Reason.IMPORTED);
    }

    private Outcome remember(String cacheKey, Reason reason) {
        negative.put(cacheKey, new Expiring(reason, Instant.now().plus(negativeTtl)));
        return new Outcome(null, reason);
    }

    private <T> T inTenant(UUID tenantId, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(tenantId, () -> tx.execute(s -> body.get()));
    }

    /** An already-stored pre-connect row of this number (portal or an earlier parent import). */
    private UUID existingRow(UUID tenantId, String number) {
        String bare = number.startsWith("#") ? number.substring(1) : number;
        return jdbc.queryForList(
            "SELECT id FROM orders WHERE tenant_id = ? AND origin = 'portal_pre_connect' " +
            "  AND (number = ? OR number = ?) ORDER BY created_at ASC, id ASC LIMIT 1",
            UUID.class, tenantId, bare, "#" + bare).stream().findFirst().orElse(null);
    }

    /** Exactly one connected store with a non-null cutoff, no NULL cutoff anywhere, not simulated — else null. */
    private Store store(UUID tenantId) {
        if (CourierSimulation.isSimulated(jdbc, tenantId)) return null;
        List<Map<String, Object>> stores = jdbc.queryForList(
            "SELECT id, shop_domain, orders_ingest_from FROM stores WHERE tenant_id = ? AND status = 'connected'", tenantId);
        if (stores.size() != 1 || stores.get(0).get("orders_ingest_from") == null) return null;
        Boolean anyNullCutoff = jdbc.queryForObject(
            "SELECT bool_or(orders_ingest_from IS NULL) FROM stores WHERE tenant_id = ?", Boolean.class, tenantId);
        if (Boolean.TRUE.equals(anyNullCutoff)) return null;
        Map<String, Object> s = stores.get(0);
        Object c = s.get("orders_ingest_from");
        Instant cutoff = c instanceof Timestamp ts ? ts.toInstant()
            : c instanceof java.time.OffsetDateTime odt ? odt.toInstant() : null;
        if (cutoff == null) return null;
        return new Store((UUID) s.get("id"), (String) s.get("shop_domain"), cutoff);
    }

    private static boolean sameNumber(String shopifyName, String number) {
        if (shopifyName == null) return false;
        String n = shopifyName.startsWith("#") ? shopifyName.substring(1) : shopifyName;
        String m = number.startsWith("#") ? number.substring(1) : number;
        return n.equalsIgnoreCase(m);
    }
}
