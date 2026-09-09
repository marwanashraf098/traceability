package com.traceability.integrations.shopify;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Owner-triggered soft disconnect for a Shopify store.
 *
 * Reuses the EXACT status-flip seam that {@code ShopifyWebhookProcessorJob.handleAppUninstalled()}
 * uses for the app/uninstalled webhook — same UPDATE, same WHERE clause, same "leave the token
 * columns alone" behavior. No parallel disconnect path is introduced.
 *
 * Shopify-side revocation is intentionally NOT done here: it is a best-effort network call that
 * must happen strictly AFTER this transaction commits, so a slow/failed revoke can never roll
 * back or block the already-committed local disconnect (see ShopifyController.disconnect()).
 */
@Service
public class ShopifyDisconnectService {

    private static final String FIND_STORE = """
            SELECT shop_domain, status, access_token_encrypted
            FROM stores WHERE id = ? AND tenant_id = ?
            """;

    // Exact seam query from ShopifyWebhookProcessorJob.handleAppUninstalled() — do not diverge.
    // Deliberately does NOT null access_token_encrypted / refresh_token_encrypted: seam parity.
    private static final String DISCONNECT_STORE =
        "UPDATE stores SET status = 'disconnected' WHERE shop_domain = ? AND tenant_id = ?";

    private final JdbcTemplate       jdbc;
    private final EncryptionService  encryptionService;

    public ShopifyDisconnectService(JdbcTemplate jdbc, EncryptionService encryptionService) {
        this.jdbc              = jdbc;
        this.encryptionService = encryptionService;
    }

    /**
     * @param alreadyDisconnected true if the store was already disconnected (no-op, no write)
     * @param shopDomain          the store's shop domain (for the post-commit revoke call)
     * @param rawAccessToken      decrypted access token to revoke; null when already-disconnected
     *                            or when the store has no token on file
     */
    public record DisconnectResult(boolean alreadyDisconnected, String shopDomain, String rawAccessToken) {}

    @Transactional
    public DisconnectResult disconnect(UUID storeId) {
        UUID tenantId = TenantContext.require();

        record StoreRow(String shopDomain, String status, String accessTokenEncrypted) {}
        StoreRow row = jdbc.query(FIND_STORE,
            rs -> rs.next() ? new StoreRow(
                rs.getString("shop_domain"),
                rs.getString("status"),
                rs.getString("access_token_encrypted")) : null,
            storeId, tenantId);

        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Store not found or does not belong to this account");
        }

        if ("disconnected".equals(row.status())) {
            return new DisconnectResult(true, row.shopDomain(), null);
        }

        String rawAccessToken = row.accessTokenEncrypted() != null
            ? encryptionService.decrypt(row.accessTokenEncrypted())
            : null;

        jdbc.update(DISCONNECT_STORE, row.shopDomain(), tenantId);

        return new DisconnectResult(false, row.shopDomain(), rawAccessToken);
    }
}
