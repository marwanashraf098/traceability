package com.traceability.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Build C — the ONE rule for "is this store offered the custom-app → official OAuth upgrade":
 * SHOPIFY_OAUTH_AVAILABLE is on AND (SHOPIFY_OAUTH_UPGRADE_SHOPS is empty — everyone — OR this store's shop
 * domain is listed). Read by GET /connections (the Shopify card's upgrade banner) and by GET /me (the app-wide
 * upgrade notice), so the notice never points at a card without an upgrade button. Display only:
 * /oauth/initiate itself is not gated.
 */
@Component
public class ShopifyUpgradeRollout {

    @Value("${shopify.oauth-available:false}")
    private boolean oauthAvailable;

    // Per-store upgrade rollout (SHOPIFY_OAUTH_UPGRADE_SHOPS): shop domains, comma-separated.
    @Value("${shopify.oauth-upgrade-shops:}")
    private String oauthUpgradeShops;

    public boolean offered(String shopDomain) {
        return offered(oauthAvailable, oauthUpgradeShops, shopDomain);
    }

    static boolean offered(boolean oauthAvailable, String shopsCsv, String shopDomain) {
        if (!oauthAvailable) return false;
        Set<String> shops = new HashSet<>();
        if (shopsCsv != null) {
            for (String s : shopsCsv.split(",")) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) shops.add(t);
            }
        }
        return shops.isEmpty()
            || (shopDomain != null && shops.contains(shopDomain.trim().toLowerCase(Locale.ROOT)));
    }
}
