package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for ShopifyGateway.scopeGrantMessage() — no Spring context, no network.
 *
 * The production bug this covers: a custom_app_cc store's scope-guard error told the
 * merchant to "reconnect" — which is meaningless for CC (no consent screen; scopes are
 * configured on the Dev Dashboard custom app and picked up by a fresh token exchange).
 * These tests assert the message text actually differs by connection_type.
 */
class ShopifyGatewayScopeMessageTest {

    @Test
    void oauthConnectionType_saysReconnect() {
        String msg = ShopifyGateway.scopeGrantMessage("oauth", "read_products", "write_orders");
        assertThat(msg).contains("Token lacks read_products scope");
        assertThat(msg).contains("granted: write_orders");
        assertThat(msg).contains("store must reconnect to grant the current scope list");
        assertThat(msg).doesNotContain("Dev Dashboard");
    }

    @Test
    void customAppCcConnectionType_saysUpdateDevDashboardAndReissue() {
        String msg = ShopifyGateway.scopeGrantMessage("custom_app_cc", "write_inventory", null);
        assertThat(msg).contains("Token lacks write_inventory scope");
        assertThat(msg).contains("granted: none");
        assertThat(msg).doesNotContain("store must reconnect");
        assertThat(msg).contains("Admin API access scopes");
        assertThat(msg).contains("reissue the token");
    }

    @Test
    void legacyCustomApp_stillSaysReconnect() {
        // Only custom_app_cc gets the corrected message — legacy custom_app (non-CC) has
        // its own token-replacement flow, not covered by this fix.
        String msg = ShopifyGateway.scopeGrantMessage("custom_app", "read_products", "read_orders");
        assertThat(msg).contains("store must reconnect to grant the current scope list");
    }
}
