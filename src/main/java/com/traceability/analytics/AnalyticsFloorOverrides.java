package com.traceability.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Analytics floor for a store whose {@code stores.orders_ingest_from} is NULL.
 *
 * The analytics floor is the store's connection cutoff ({@code orders_ingest_from}): Traced reports
 * only orders placed after the merchant connected. Jumi's cutoff is NULL permanently and must stay
 * NULL — that column is not analytics-only: Shopify ingest (ShopifySyncService.loadCutoff) drops
 * webhooks and imports for orders placed before it, and PreConnectDeliveryFilter /
 * BostaDiscoveryPollJob start ignoring Bosta deliveries created before it. Setting it would change
 * ingest, not just reports. So the floor for such a store comes from configuration instead, and is
 * read only by analytics:
 *
 * <pre>analytics.floor-overrides: shop-domain=YYYY-MM-DD[,shop-domain=YYYY-MM-DD…]</pre>
 *
 * The date is a Cairo calendar day; the floor is 00:00 Africa/Cairo on it. An override applies only
 * while the store's {@code orders_ingest_from} is NULL — a real cutoff always wins. A store with
 * neither has no floor. Keyed by shop domain (the store's stable identity), never a tenant id.
 * A malformed value fails startup rather than silently reporting pre-connect orders.
 */
@Component
public class AnalyticsFloorOverrides {

    private static final Pattern SHOP_DOMAIN = Pattern.compile("^[a-z0-9][a-z0-9.-]*$");

    private final Map<String, LocalDate> byShopDomain;

    public AnalyticsFloorOverrides(@Value("${analytics.floor-overrides:}") String raw) {
        this.byShopDomain = Collections.unmodifiableMap(parse(raw));
    }

    static Map<String, LocalDate> parse(String raw) {
        Map<String, LocalDate> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String entry : raw.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int eq = e.indexOf('=');
            if (eq <= 0 || eq == e.length() - 1) {
                throw new IllegalStateException("analytics.floor-overrides: expected shop-domain=YYYY-MM-DD, got '" + e + "'");
            }
            String domain = e.substring(0, eq).trim().toLowerCase();
            String day    = e.substring(eq + 1).trim();
            if (!SHOP_DOMAIN.matcher(domain).matches()) {
                throw new IllegalStateException("analytics.floor-overrides: not a shop domain: '" + domain + "'");
            }
            try {
                out.put(domain, LocalDate.parse(day));
            } catch (DateTimeParseException ex) {
                throw new IllegalStateException("analytics.floor-overrides: not a YYYY-MM-DD date for " + domain + ": '" + day + "'");
            }
        }
        return out;
    }

    public Map<String, LocalDate> asMap() {
        return byShopDomain;
    }

    /** Shop domains, in the same order as {@link #days()} — bound as two parallel SQL text arrays. */
    String[] shopDomains() {
        return byShopDomain.keySet().toArray(new String[0]);
    }

    String[] days() {
        List<String> days = new ArrayList<>();
        byShopDomain.values().forEach(d -> days.add(d.toString()));
        return days.toArray(new String[0]);
    }
}
