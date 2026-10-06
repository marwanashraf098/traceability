package com.traceability.integrations.shopify;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Build C — before a custom-app store is switched to the official OAuth app, delete the OLD app's webhook
 * subscriptions that point at Traced, using the old app's own credentials.
 *
 * Why: webhook subscriptions belong to the app that created them. After the flip, Traced verifies webhooks
 * with the official app's secret only (phase B is skipped — no custom-app secret is left), so the custom
 * app's subscriptions would keep firing duplicates that answer 401 until Shopify drops them. Listing with
 * the NEW token cannot see them; only the old app's token can.
 *
 * Token: the stored access token while it has more than a minute left; otherwise, for custom_app_cc, a fresh
 * client-credentials exchange with the stored Client ID / Secret (NOT persisted — the row is about to be
 * replaced). Works whatever the store's status (a store disconnected from Traced's own Disconnect button
 * still has its custom app installed and subscribed).
 *
 * Never throws: every failure comes back as {@code failed} with a reason, and the upgrade goes ahead.
 * A failure is harmless in practice — the leftover subscriptions only produce 401s — and the merchant
 * uninstalling the custom app afterwards removes them for good (Shopify deletes an app's subscriptions with
 * it; the app/uninstalled it sends can no longer disconnect the store — phase-B HMAC is gone).
 */
@Component
public class LegacyWebhookCleanup {

    private static final Logger log = LoggerFactory.getLogger(LegacyWebhookCleanup.class);

    /** What happened. {@code previousType} null = the row was not a custom app: nothing to do, nothing recorded. */
    public record Result(String previousType, String status, String detail) {
        static Result notCustomApp() { return new Result(null, null, null); }
        public boolean isUpgrade() { return previousType != null; }
    }

    private record OldApp(String type, String accessTokenEncrypted, Instant expiresAt,
                          String clientIdEncrypted, String secretEncrypted) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final EncryptionService encryption;
    private final String callbackBase;

    public LegacyWebhookCleanup(JdbcTemplate jdbc, PlatformTransactionManager txm, ShopifyGateway shopify,
                                EncryptionService encryption,
                                @Value("${shopify.webhook-base-url}") String webhookBaseUrl) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.shopify = shopify;
        this.encryption = encryption;
        this.callbackBase = webhookBaseUrl + "/webhooks/shopify/";   // same prefix RegisterShopifyWebhooksJob uses
    }

    public Result run(UUID tenantId, String shopDomain) {
        OldApp old = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
            "SELECT connection_type, access_token_encrypted, access_token_expires_at, client_id_encrypted, " +
            "       api_secret_encrypted FROM stores WHERE tenant_id = ? AND shop_domain = ?",
            rs -> rs.next() ? new OldApp(rs.getString(1), rs.getString(2),
                    rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                    rs.getString(4), rs.getString(5)) : null,
            tenantId, shopDomain)));
        if (old == null || !("custom_app_cc".equals(old.type()) || "custom_app".equals(old.type()))) {
            return Result.notCustomApp();
        }

        String token;
        try {
            token = oldToken(shopDomain, old);
        } catch (RuntimeException e) {
            return failed(old, shopDomain, "old app credentials rejected (the custom app is probably uninstalled, "
                + "which already removed its webhooks): " + e.getMessage());
        }

        int deleted = 0, kept = 0, failedDeletes = 0;
        List<ShopifyGateway.WebhookSubscription> subs;
        try {
            subs = shopify.listWebhookSubscriptions(shopDomain, token);
        } catch (RuntimeException e) {
            return failed(old, shopDomain, "could not list the old app's webhooks: " + e.getMessage());
        }
        StringBuilder errors = new StringBuilder();
        for (ShopifyGateway.WebhookSubscription sub : subs) {
            if (sub.callbackUrl() == null || !sub.callbackUrl().startsWith(callbackBase)) { kept++; continue; }
            try {
                shopify.deleteWebhookSubscription(shopDomain, token, sub.gid());
                deleted++;
            } catch (RuntimeException e) {
                failedDeletes++;
                if (errors.length() < 200) errors.append(sub.topic()).append(": ").append(e.getMessage()).append("; ");
            }
        }
        if (failedDeletes > 0) {
            return failed(old, shopDomain, "deleted " + deleted + ", failed " + failedDeletes + " — " + errors);
        }
        String detail = "deleted " + deleted + " of the old app's webhooks"
            + (kept > 0 ? " (" + kept + " pointing elsewhere left alone)" : "");
        log.info("OAUTH_UPGRADE_CLEANUP shop={} tenant={} from={} status=done {}", shopDomain, tenantId, old.type(), detail);
        return new Result(old.type(), "done", detail);
    }

    private String oldToken(String shopDomain, OldApp old) {
        boolean fresh = old.accessTokenEncrypted() != null && old.expiresAt() != null
            && old.expiresAt().isAfter(Instant.now().plusSeconds(60));
        if (fresh) return encryption.decrypt(old.accessTokenEncrypted());
        if ("custom_app_cc".equals(old.type()) && old.clientIdEncrypted() != null && old.secretEncrypted() != null) {
            return shopify.exchangeClientCredentials(shopDomain,
                encryption.decrypt(old.clientIdEncrypted()), encryption.decrypt(old.secretEncrypted())).accessToken();
        }
        throw new IllegalStateException("no usable token or credentials stored for the old app");
    }

    private static Result failed(OldApp old, String shopDomain, String reason) {
        String detail = reason.length() > 500 ? reason.substring(0, 500) : reason;
        log.warn("OAUTH_UPGRADE_CLEANUP shop={} from={} status=failed — upgrade continues; {}", shopDomain, old.type(), detail);
        return new Result(old.type(), "failed", detail);
    }
}
