package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.account.ConnectionsController;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway.OnlineFulfillment;
import com.traceability.integrations.shopify.ShopifyShippingModelGateway;
import com.traceability.integrations.shopify.ShopifyShippingModelGateway.Model;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L1b-1 (2026-11-15) — market-driven shipping: no delivery-profile call, never "Ready".
 *
 *   m1  first link on a MARKET-DRIVEN store: no delivery-profile call at all; model recorded; readiness
 *       add_to_shipping_rates (never ready); /connections carries it + the location number
 *   m2  first link, model UNKNOWN (the read failed): no delivery-profile call; readiness unknown
 *   m3  positive control — first link on a LEGACY store: membership READ (already a member) → ready; never a write
 *   m4  reconnect of a market-driven store: no membership read
 *   (m5 — the manual Activate fulfillment's market-driven refusal — removed with the endpoint, L1b-2 2026-11-16)
 *   m6  Turn back on, market-driven: fulfils-online is turned on (a location setting), the profile is never touched;
 *       without read_markets the Markets read records no_access (no call)
 *   m7  hourly refresh: records model + fulfils-online, never an edit, never a profile call
 *   m8  a market-driven store Traced had recorded as 'activated' (the stale no-op since Oct 1) is NOT ready
 *   m9  the readiness rule, case by case
 *   m10 the audit logs the live model + wrongly_activated and writes nothing
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracedShippingModelTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.newContainer()
        .withDatabaseName("traceability_test").withUsername("postgres").withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("shopify.api-version",        () -> "2026-04");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean ShopifyLocationGateway locations;
    @MockBean ShopifyDeliveryProfileGateway profiles;
    @MockBean ShopifyTokenProvider tokens;
    @MockBean ShopifyShippingModelGateway models;

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifyLocationProvisioningService provisioning;
    @Autowired TracedLocationOnlineService online;
    @Autowired LocationController locationController;
    @Autowired ConnectionsController connections;
    @Autowired LocationSettingsAuditJob audit;

    static final String SHOP = "l1b-test.myshopify.com";
    static final String GID = "gid://shopify/Location/4242";
    static final OnlineFulfillment ON = new OnlineFulfillment(true, true);
    static final OnlineFulfillment OFF = new OnlineFulfillment(true, false);

    UUID tenantId;
    UUID storeId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'L1b Tenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, access_token_scopes) " +
                    "VALUES (?, ?, ?, 'connected', 'read_products,write_locations,write_inventory,write_shipping')",
                    storeId, tenantId, SHOP);
        reset(locations, profiles, tokens, models);
        when(tokens.getValidToken(storeId)).thenReturn("tok");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void firstLink() {
        when(locations.findByName(eq(SHOP), eq("tok"), anyString())).thenReturn(Optional.empty());
        when(locations.create(eq(SHOP), eq("tok"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult(GID, "Traced Main Warehouse"));
        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, SHOP));
    }

    private UUID linkedMain(String model, String profileStatus, boolean activatedBefore, String online) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
                    "  shopify_sync_status, shopify_delivery_profile_status, shopify_delivery_profile_activated_at, " +
                    "  shopify_shipping_model, shopify_online_fulfillment_status, shopify_delivery_profile_checked_at) " +
                    "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked', ?, " +
                    (activatedBefore ? "now() - interval '3 days'" : "NULL") + ", ?, ?, now())",
                    id, tenantId, GID, profileStatus, model, online);
        return id;
    }

    private String readiness() {
        return jdbc.queryForObject("SELECT " + TracedShippingReadiness.SQL + " FROM locations l " +
                                   "WHERE l.tenant_id = ? AND l.is_fulfillment", String.class, tenantId);
    }

    private String model() {
        return jdbc.queryForObject("SELECT shopify_shipping_model FROM locations WHERE tenant_id = ? AND is_fulfillment",
            String.class, tenantId);
    }

    private <T> T as(String role, java.util.function.Supplier<T> action) {
        var auth = new TestingAuthenticationToken("l1b-" + role, "n/a", "ROLE_" + role);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return TenantContext.runAs(tenantId, () -> action.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID owner() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, role) VALUES (?, ?, 'L1b owner', 'owner'::user_role)", id, tenantId);
        return id;
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void m1_marketDriven_firstLink_noProfileCall_neverReady() {
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.MARKET_DRIVEN));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));

        firstLink();

        verifyNoInteractions(profiles);
        assertThat(model()).isEqualTo("market_driven");
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.ADD_TO_SHIPPING_RATES);
        Map<String, Object> shopify = (Map<String, Object>) as("OWNER", () -> connections.status(
            new CustomUserDetails(owner(), tenantId, "OWNER", SHOP))).get("shopify");
        assertThat(shopify).containsEntry("tracedMainShipping", "add_to_shipping_rates").containsEntry("tracedLocationNumber", "4242");
        List<Map<String, Object>> rows = as("OWNER", () -> locationController.list());
        assertThat(rows.get(0)).containsEntry("shopify_shipping_readiness", "add_to_shipping_rates")
            .containsEntry("shopify_shipping_model", "market_driven");
    }

    @Test
    void m2_unknownModel_firstLink_noProfileCall_unknown() {
        when(models.read(SHOP, "tok")).thenReturn(Optional.empty());
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));

        firstLink();

        verifyNoInteractions(profiles);
        assertThat(model()).isEqualTo("unknown");
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.UNKNOWN);
    }

    @Test
    void m3_legacy_firstLink_memberRead_ready() {
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.LEGACY));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(
            Optional.of(new ShopifyDeliveryProfileGateway.LocationGroupInfo("p", "g", Set.of(GID))));

        firstLink();

        verify(profiles, times(1)).findDefaultProfileLocationGroup(SHOP, "tok");
        verifyNoMoreInteractions(profiles);
        assertThat(model()).isEqualTo("legacy");
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.READY);
    }

    @Test
    void m4_reconnect_marketDriven_noMembershipRead() {
        linkedMain("unknown", "activated", true, "on");
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.MARKET_DRIVEN));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));

        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, SHOP));

        verifyNoInteractions(profiles);
        verify(locations, never()).enableOnlineFulfillment(any(), any(), any());
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.ADD_TO_SHIPPING_RATES);
    }

    @Test
    void m6_turnBackOn_marketDriven_onlineOnly_profileNeverTouched() {
        linkedMain("market_driven", "not_activated", false, "off");
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.MARKET_DRIVEN));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF), Optional.of(ON));
        UUID owner = owner();

        Map<String, Object> res = as("OWNER", () -> locationController.turnTracedLocationBackOn(
            new CustomUserDetails(owner, tenantId, "OWNER", SHOP)));

        assertThat(res).containsEntry("onlineStatus", "on").containsEntry("profile", "no_access");
        verify(locations).enableOnlineFulfillment(SHOP, "tok", GID);
        verifyNoInteractions(profiles);
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.ADD_TO_SHIPPING_RATES);
    }

    @Test
    void m7_hourlyRefresh_readsOnly() {
        linkedMain("unknown", "activated", true, "unknown");
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.LEGACY));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok"))
            .thenReturn(Optional.of(new ShopifyDeliveryProfileGateway.LocationGroupInfo("p", "g", Set.of(GID))));

        online.refreshQuietly(tenantId);

        assertThat(model()).isEqualTo("legacy");
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.READY);

        // Shopify now says fulfils-online is OFF: the hourly pass records it — it never turns it back on.
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF));
        online.refreshQuietly(tenantId);
        assertThat(readiness()).isEqualTo("online_off");
        verify(locations, never()).enableOnlineFulfillment(any(), any(), any());
    }

    @Test
    void m8_marketDriven_recordedActivated_isNotReady() {
        linkedMain("market_driven", "activated", true, "on");
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.ADD_TO_SHIPPING_RATES);
    }

    @Test
    void m9_readinessRule_caseByCase() {
        UUID id = linkedMain("legacy", "activated", true, "on");
        assertThat(readiness()).isEqualTo("ready");
        jdbc.update("UPDATE locations SET shopify_online_fulfillment_status = 'unknown' WHERE id = ?", id);
        assertThat(readiness()).as("never Ready on unknown").isEqualTo("unknown");
        jdbc.update("UPDATE locations SET shopify_online_fulfillment_status = 'on', shopify_shipping_model = 'unknown' WHERE id = ?", id);
        assertThat(readiness()).as("never Ready on an unknown model").isEqualTo("unknown");
        jdbc.update("UPDATE locations SET shopify_shipping_model = 'legacy', shopify_delivery_profile_checked_at = NULL WHERE id = ?", id);
        assertThat(readiness()).as("legacy: never Ready (nor 'can't') without a LIVE membership read").isEqualTo("unknown");
        jdbc.update("UPDATE locations SET shopify_delivery_profile_checked_at = now() WHERE id = ?", id);
        jdbc.update("UPDATE locations SET shopify_shipping_model = 'legacy', shopify_delivery_profile_status = 'not_activated' WHERE id = ?", id);
        assertThat(readiness()).isEqualTo("profile_removed");
        jdbc.update("UPDATE locations SET shopify_delivery_profile_activated_at = NULL WHERE id = ?", id);
        assertThat(readiness()).isEqualTo("activate_needed");
        jdbc.update("UPDATE locations SET shopify_online_fulfillment_status = 'off' WHERE id = ?", id);
        assertThat(readiness()).isEqualTo("online_off");
        jdbc.update("UPDATE locations SET shopify_location_state = 'inactive' WHERE id = ?", id);
        assertThat(readiness()).isEqualTo("location_inactive");
        jdbc.update("UPDATE locations SET shopify_sync_status = 'error' WHERE id = ?", id);
        assertThat(readiness()).isEqualTo("not_linked");
    }

    @Test
    void m11_legacy_storedNotActivated_noLiveRead_isChecking_thenHourlyLiveReadDecides() {
        // Before L1 joining was manual: V67 says not_activated, but the merchant added Traced Main by hand in Shopify.
        UUID id = linkedMain("legacy", "not_activated", false, "on");
        jdbc.update("UPDATE locations SET shopify_delivery_profile_checked_at = NULL WHERE id = ?", id);
        assertThat(readiness()).as("right after deploy: Checking…, never \"can't ship\"").isEqualTo("unknown");

        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.LEGACY));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok"))
            .thenReturn(Optional.of(new ShopifyDeliveryProfileGateway.LocationGroupInfo("p", "g", Set.of(GID))));
        online.refreshQuietly(tenantId);

        assertThat(readiness()).as("the live read found it in the profile").isEqualTo("ready");
        assertThat(jdbc.queryForObject("SELECT shopify_delivery_profile_checked_at IS NOT NULL FROM locations WHERE id = ?",
            Boolean.class, id)).isTrue();

        // Not in the profile per the live read, never in it before → the owner's add-to-profile instruction.
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok"))
            .thenReturn(Optional.of(new ShopifyDeliveryProfileGateway.LocationGroupInfo("p", "g", Set.of())));
        jdbc.update("UPDATE locations SET shopify_delivery_profile_activated_at = NULL WHERE id = ?", id);
        online.refreshQuietly(tenantId);
        assertThat(readiness()).isEqualTo("activate_needed");
    }

    @Test
    void m12_ownerConfirm_marketDriven_persists_notReady_auditLogged() {
        UUID id = linkedMain("market_driven", "not_activated", false, "on");
        UUID owner = owner();

        Map<String, Object> res = as("OWNER", () -> locationController.confirmTracedShippingRates(
            new CustomUserDetails(owner, tenantId, "OWNER", SHOP)));

        assertThat(res).containsEntry("readiness", "owner_confirmed");
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.OWNER_CONFIRMED).isNotEqualTo("ready");
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT shopify_shipping_owner_confirmed_at, shopify_shipping_owner_confirmed_by FROM locations WHERE id = ?", id);
        assertThat(row.get("shopify_shipping_owner_confirmed_at")).isNotNull();
        assertThat(row.get("shopify_shipping_owner_confirmed_by")).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'shopify_shipping_rates_confirmed'",
            Integer.class, tenantId)).isEqualTo(1);
        verifyNoInteractions(profiles, locations, models);

        // The gate row / card read it from /connections and /locations.
        @SuppressWarnings("unchecked")
        Map<String, Object> shopify = (Map<String, Object>) as("OWNER", () -> connections.status(
            new CustomUserDetails(owner, tenantId, "OWNER", SHOP))).get("shopify");
        assertThat(shopify).containsEntry("tracedMainShipping", "owner_confirmed");
        // Survives the hourly pass (it never clears it).
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.MARKET_DRIVEN));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
        online.refreshQuietly(tenantId);
        assertThat(readiness()).isEqualTo("owner_confirmed");
    }

    @Test
    void m13_ownerConfirm_ownerOnly_and_marketDrivenOnly() {
        UUID id = linkedMain("market_driven", "not_activated", false, "on");
        UUID manager = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, role) VALUES (?, ?, 'L1b manager', 'manager'::user_role)", manager, tenantId);

        assertThatThrownBy(() -> as("MANAGER", () -> locationController.confirmTracedShippingRates(
                new CustomUserDetails(manager, tenantId, "MANAGER", SHOP))))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(readiness()).isEqualTo("add_to_shipping_rates");

        jdbc.update("UPDATE locations SET shopify_shipping_model = 'legacy' WHERE id = ?", id);
        UUID owner = owner();
        assertThatThrownBy(() -> as("OWNER", () -> locationController.confirmTracedShippingRates(
                new CustomUserDetails(owner, tenantId, "OWNER", SHOP))))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("409");
        assertThat(jdbc.queryForObject("SELECT shopify_shipping_owner_confirmed_at FROM locations WHERE id = ?",
            java.time.OffsetDateTime.class, id)).isNull();
    }

    @Test
    void m10_audit_logsModel_flagsWronglyActivated_writesNothing() {
        linkedMain("unknown", "activated", true, "on");
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.MARKET_DRIVEN));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok"))
            .thenReturn(Optional.of(new ShopifyDeliveryProfileGateway.LocationGroupInfo("p", "g", Set.of(GID))));
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM locations WHERE tenant_id = ?", tenantId);

        var r = audit.run(List.of(tenantId)).get(0);

        assertThat(r.shippingModel()).isEqualTo("market_driven");
        assertThat(r.wronglyActivated()).isTrue();
        assertThat(r.flagged()).isTrue();
        assertThat(LocationSettingsAuditJob.line(r)).contains("shipping_model=market_driven")
            .contains("wrongly_activated=true").contains("traced_readiness=unknown");
        assertThat(jdbc.queryForMap("SELECT * FROM locations WHERE tenant_id = ?", tenantId)).isEqualTo(before);
        verify(locations, never()).enableOnlineFulfillment(any(), any(), any());
    }
}
