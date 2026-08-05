package com.traceability;

import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShopifyFulfillmentActivationException;
import com.traceability.inventory.ShopifyFulfillmentActivationService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-17 v2 fulfillment activation (deliveryProfileUpdate). ShopifyFulfillmentActivationService
 * has zero call sites as of this build — these tests exercise it directly, standing in for the
 * (not yet decided) trigger.
 *
 * Matrix:
 *   fa1 — happy path: not yet a group member -> addLocationToGroup called with the resolved
 *         profile+group+location; status recorded 'activated'
 *   fa2 — idempotent re-run: already a group member -> addLocationToGroup never called, result
 *         reports alreadyMember=true, status stays 'activated'
 *   fa3 — missing write_shipping scope: guarded failure (ShopifyFulfillmentActivationException,
 *         code=MISSING_SCOPE), addLocationToGroup never called, status recorded 'error' with
 *         the CC-appropriate message, both message_en and message_ar populated
 *   fa4 — no default delivery profile: clean 409 (code=NO_DEFAULT_PROFILE), never half-adds
 *   fa5 — ambiguous default profile (gateway throws ShopifyException for >1 location group):
 *         clean 409 (code=AMBIGUOUS_LOCATION_GROUPS), never half-adds — this is the path that
 *         was silently escaping as an uncaught 500 before the try/catch was added around
 *         findDefaultProfileLocationGroup()
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyFulfillmentActivationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean ShopifyDeliveryProfileGateway deliveryProfiles;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifyFulfillmentActivationService activationService;

    UUID tenantId;
    UUID storeId;
    UUID locationId;
    static final String SHOP_DOMAIN   = "fa-test.myshopify.com";
    static final String LOCATION_GID  = "gid://shopify/Location/fa-loc";
    static final String PROFILE_GID   = "gid://shopify/DeliveryProfile/fa";
    static final String GROUP_GID     = "gid://shopify/DeliveryLocationGroup/fa";

    @BeforeEach
    void setup() {
        tenantId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'FA Tenant')", tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, status, access_token_scopes, connection_type) " +
            "VALUES (?, ?, ?, 'connected', 'read_products,write_locations,write_inventory,write_shipping', 'oauth')",
            storeId, tenantId, SHOP_DOMAIN);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, " +
            "    shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')",
            locationId, tenantId, LOCATION_GID);
        when(tokenProvider.getValidToken(storeId)).thenReturn("test-token");
        reset(deliveryProfiles);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private <T> T asTenant(java.util.function.Supplier<T> action) {
        TenantContext.set(tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    // ── fa1: happy path ───────────────────────────────────────────────────

    @Test
    void fa1_notYetMember_joinsGroupAndRecordsActivated() {
        when(deliveryProfiles.findDefaultProfileLocationGroup(SHOP_DOMAIN, "test-token"))
            .thenReturn(Optional.of(new ShopifyDeliveryProfileGateway.LocationGroupInfo(
                PROFILE_GID, GROUP_GID, Set.of("gid://shopify/Location/other"))));

        ShopifyFulfillmentActivationService.ActivationResult result =
            asTenant(() -> activationService.activate(null));

        assertThat(result.status()).isEqualTo(ShopifyFulfillmentActivationService.STATUS_ACTIVATED);
        assertThat(result.alreadyMember()).isFalse();

        verify(deliveryProfiles).addLocationToGroup(
            SHOP_DOMAIN, "test-token", PROFILE_GID, GROUP_GID, LOCATION_GID);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT shopify_delivery_profile_status, shopify_delivery_profile_activated_at " +
            "FROM locations WHERE id = ?", locationId);
        assertThat(row.get("shopify_delivery_profile_status")).isEqualTo("activated");
        assertThat(row.get("shopify_delivery_profile_activated_at")).isNotNull();
    }

    // ── fa2: idempotent re-run — already a member ───────────────────────────

    @Test
    void fa2_alreadyMember_neverCallsAddLocationToGroup() {
        when(deliveryProfiles.findDefaultProfileLocationGroup(SHOP_DOMAIN, "test-token"))
            .thenReturn(Optional.of(new ShopifyDeliveryProfileGateway.LocationGroupInfo(
                PROFILE_GID, GROUP_GID, Set.of(LOCATION_GID))));

        ShopifyFulfillmentActivationService.ActivationResult result =
            asTenant(() -> activationService.activate(null));

        assertThat(result.status()).isEqualTo(ShopifyFulfillmentActivationService.STATUS_ACTIVATED);
        assertThat(result.alreadyMember()).isTrue();
        verify(deliveryProfiles, never()).addLocationToGroup(any(), any(), any(), any(), any());

        String status = jdbc.queryForObject(
            "SELECT shopify_delivery_profile_status FROM locations WHERE id = ?", String.class, locationId);
        assertThat(status).isEqualTo("activated");
    }

    // ── fa3: missing write_shipping scope ────────────────────────────────

    @Test
    void fa3_missingScope_guardedFailureNeverCallsShopify() {
        jdbc.update(
            "UPDATE stores SET access_token_scopes = 'read_products,write_locations,write_inventory' " +
            "WHERE id = ?", storeId);

        ShopifyFulfillmentActivationException ex = Assertions.assertThrows(
            ShopifyFulfillmentActivationException.class,
            () -> asTenant(() -> activationService.activate(null)));

        assertThat(ex.code()).isEqualTo(ShopifyFulfillmentActivationException.Code.MISSING_SCOPE);
        assertThat(ex.messageEn()).contains("write_shipping");
        assertThat(ex.messageAr()).isNotBlank();

        verify(deliveryProfiles, never()).findDefaultProfileLocationGroup(any(), any());
        verify(deliveryProfiles, never()).addLocationToGroup(any(), any(), any(), any(), any());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT shopify_delivery_profile_status, shopify_delivery_profile_error " +
            "FROM locations WHERE id = ?", locationId);
        assertThat(row.get("shopify_delivery_profile_status")).isEqualTo("error");
        assertThat((String) row.get("shopify_delivery_profile_error")).contains("write_shipping");
    }

    // ── fa4: no default delivery profile never half-adds ─────────────────────

    @Test
    void fa4_noDefaultProfile_cleanFailureNeverCallsAddLocationToGroup() {
        when(deliveryProfiles.findDefaultProfileLocationGroup(SHOP_DOMAIN, "test-token"))
            .thenReturn(Optional.empty());

        ShopifyFulfillmentActivationException ex = Assertions.assertThrows(
            ShopifyFulfillmentActivationException.class,
            () -> asTenant(() -> activationService.activate(null)));

        assertThat(ex.code()).isEqualTo(ShopifyFulfillmentActivationException.Code.NO_DEFAULT_PROFILE);
        verify(deliveryProfiles, never()).addLocationToGroup(any(), any(), any(), any(), any());

        String status = jdbc.queryForObject(
            "SELECT shopify_delivery_profile_status FROM locations WHERE id = ?", String.class, locationId);
        assertThat(status).isEqualTo("error");
    }

    // ── fa5: ambiguous default profile (gateway throws) never half-adds ──────

    @Test
    void fa5_ambiguousLocationGroups_cleanFailureNeverCallsAddLocationToGroup() {
        when(deliveryProfiles.findDefaultProfileLocationGroup(SHOP_DOMAIN, "test-token"))
            .thenThrow(new ShopifyException("Default delivery profile has 2 location groups"));

        ShopifyFulfillmentActivationException ex = Assertions.assertThrows(
            ShopifyFulfillmentActivationException.class,
            () -> asTenant(() -> activationService.activate(null)));

        assertThat(ex.code()).isEqualTo(ShopifyFulfillmentActivationException.Code.AMBIGUOUS_LOCATION_GROUPS);
        assertThat(ex.httpStatus().value()).isEqualTo(409);
        verify(deliveryProfiles, never()).addLocationToGroup(any(), any(), any(), any(), any());

        String status = jdbc.queryForObject(
            "SELECT shopify_delivery_profile_status FROM locations WHERE id = ?", String.class, locationId);
        assertThat(status).isEqualTo("error");
    }
}
