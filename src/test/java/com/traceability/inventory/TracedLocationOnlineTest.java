package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway.OnlineFulfillment;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L1 (2026-11-12) — the Traced Main Warehouse sells online from its first link.
 *
 *   o1  first link (created): fulfils-online read false → ONE edit → read back true ('on', turned_on_at); profile
 *       non-member → join → read back member ('activated')
 *   o2  first link, both already on → no edit, no join (read-first)
 *   o3  edit refused (userErrors) → 'error' recorded, provisioning still linked, nothing thrown; positive control: the
 *       same tenant's "Turn back on" with Shopify accepting → 'on'
 *   o4  edit accepted but the read-back still says false → 'error' (only the read-back decides)
 *   o5  join accepted but the profile read-back has no Traced location → V67 'error'
 *   o6  first link REUSING an existing "Traced Main Warehouse" that doesn't fulfil online orders → turned on
 *   o7  reconnect of an already-linked tenant: READ ONLY — 'off' recorded, profile 'not_activated' with activated_at
 *       kept; never an edit, never a join
 *   o8  "Turn back on": a MANAGER is refused (owner only) and nothing reaches Shopify; the OWNER's click writes
 *   o9  the only edit target is the is_fulfillment row's GID, even with another linked location in the tenant
 *   o10 no write_shipping scope → profile 'error', never a join; online still handled
 *   o11 the one-off audit (TRACED_LOCATION_SETTINGS_AUDIT_TENANTS) reads and flags, and changes nothing — not in
 *       Shopify, not on the location row
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracedLocationOnlineTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifyLocationProvisioningService provisioning;
    @Autowired LocationController controller;
    @Autowired LocationSettingsAuditJob audit;

    static final String SHOP = "l1-test.myshopify.com";
    static final String GID = "gid://shopify/Location/l1";
    static final String PROFILE = "gid://shopify/DeliveryProfile/l1";
    static final String GROUP = "gid://shopify/DeliveryLocationGroup/l1";
    static final OnlineFulfillment ON = new OnlineFulfillment(true, true);
    static final OnlineFulfillment OFF = new OnlineFulfillment(true, false);

    UUID tenantId;
    UUID storeId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'L1 Tenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, access_token_scopes) " +
                    "VALUES (?, ?, ?, 'connected', 'read_products,write_locations,write_inventory,write_shipping')",
                    storeId, tenantId, SHOP);
        reset(locations, profiles, tokens);
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

    private static ShopifyDeliveryProfileGateway.LocationGroupInfo group(String... members) {
        return new ShopifyDeliveryProfileGateway.LocationGroupInfo(PROFILE, GROUP, Set.of(members));
    }

    /** A first link that CREATES the Shopify location. */
    private void firstLinkCreating() {
        when(locations.findByName(eq(SHOP), eq("tok"), anyString())).thenReturn(Optional.empty());
        when(locations.create(eq(SHOP), eq("tok"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult(GID, "Traced Main Warehouse"));
        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, SHOP));
    }

    /** A tenant whose Traced location is ALREADY linked (a reconnect / re-sync). */
    private UUID linkedMain(String profileStatus, boolean activatedBefore) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
                    "  shopify_sync_status, shopify_delivery_profile_status, shopify_delivery_profile_activated_at) " +
                    "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked', ?, " +
                    (activatedBefore ? "now() - interval '3 days'" : "NULL") + ")",
                    id, tenantId, GID, profileStatus);
        return id;
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT shopify_sync_status, shopify_online_fulfillment_status, shopify_online_fulfillment_error, " +
                                "  shopify_online_fulfillment_turned_on_at, shopify_online_fulfillment_checked_at, " +
                                "  shopify_delivery_profile_status, shopify_delivery_profile_error, " +
                                "  shopify_delivery_profile_activated_at, shopify_delivery_profile_checked_at " +
                                "FROM locations WHERE tenant_id = ? AND is_fulfillment", tenantId);
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, role) VALUES (?, ?, 'L1 user', ?::user_role)", id, tenantId, role);
        return id;
    }

    private <T> T as(String role, java.util.function.Supplier<T> action) {
        var auth = new TestingAuthenticationToken("l1-" + role, "n/a", "ROLE_" + role);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return TenantContext.runAs(tenantId, action::get);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void o1_firstLink_turnsOnlineOn_andJoinsProfile_bothReadBack() {
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF), Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group("gid://shopify/Location/shop")),
            Optional.of(group("gid://shopify/Location/shop", GID)));

        firstLinkCreating();

        verify(locations, times(1)).enableOnlineFulfillment(SHOP, "tok", GID);
        verify(locations, times(2)).readOnlineFulfillment(SHOP, "tok", GID);
        verify(profiles, times(1)).addLocationToGroup(SHOP, "tok", PROFILE, GROUP, GID);
        verify(profiles, times(2)).findDefaultProfileLocationGroup(SHOP, "tok");
        Map<String, Object> r = row();
        assertThat(r.get("shopify_sync_status")).isEqualTo("linked");
        assertThat(r.get("shopify_online_fulfillment_status")).isEqualTo("on");
        assertThat(r.get("shopify_online_fulfillment_turned_on_at")).isNotNull();
        assertThat(r.get("shopify_delivery_profile_status")).isEqualTo("activated");
        assertThat(r.get("shopify_delivery_profile_activated_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'shopify_fulfillment_activation' " +
                                       "AND metadata->>'source' = 'first_link' AND metadata->>'outcome' = 'joined'", Long.class, tenantId))
            .isEqualTo(1L);
    }

    @Test
    void o2_firstLink_alreadyOn_andMember_writesNothing() {
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group(GID)));

        firstLinkCreating();

        verify(locations, never()).enableOnlineFulfillment(any(), any(), any());
        verify(profiles, never()).addLocationToGroup(any(), any(), any(), any(), any());
        assertThat(row().get("shopify_online_fulfillment_status")).isEqualTo("on");
        assertThat(row().get("shopify_online_fulfillment_turned_on_at")).isNull();
        assertThat(row().get("shopify_delivery_profile_status")).isEqualTo("activated");
    }

    @Test
    void o3_editRefused_recorded_neverBlocksProvisioning_thenOwnerTurnsBackOn() {
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF));
        doThrow(new ShopifyException("locationEdit (fulfillsOnlineOrders) failed: GENERIC_ERROR — nope"))
            .when(locations).enableOnlineFulfillment(SHOP, "tok", GID);
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group(GID)));

        firstLinkCreating();                                                    // nothing thrown

        Map<String, Object> r = row();
        assertThat(r.get("shopify_sync_status")).isEqualTo("linked");
        assertThat(r.get("shopify_online_fulfillment_status")).isEqualTo("error");
        assertThat((String) r.get("shopify_online_fulfillment_error")).contains("GENERIC_ERROR");

        // Positive control, same tenant: Shopify now accepts → the owner's click turns it on.
        reset(locations);
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF), Optional.of(ON));
        UUID owner = user("owner");
        Map<String, Object> res = as("OWNER", () -> controller.turnTracedLocationBackOn(
            new CustomUserDetails(owner, tenantId, "OWNER", SHOP)));
        assertThat(res.get("onlineStatus")).isEqualTo("on");
        assertThat(row().get("shopify_online_fulfillment_status")).isEqualTo("on");
        verify(locations, times(1)).enableOnlineFulfillment(SHOP, "tok", GID);
    }

    @Test
    void o4_editAccepted_butReadBackStillOff_isAnError() {
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF), Optional.of(OFF));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group(GID)));

        firstLinkCreating();

        verify(locations, times(1)).enableOnlineFulfillment(SHOP, "tok", GID);
        assertThat(row().get("shopify_online_fulfillment_status")).isEqualTo("error");
        assertThat(row().get("shopify_online_fulfillment_turned_on_at")).isNull();
    }

    @Test
    void o5_joinAccepted_butProfileReadBackWithoutUs_isAnError() {
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group()), Optional.of(group()));

        firstLinkCreating();

        verify(profiles, times(1)).addLocationToGroup(SHOP, "tok", PROFILE, GROUP, GID);
        assertThat(row().get("shopify_delivery_profile_status")).isEqualTo("error");
        assertThat((String) row().get("shopify_delivery_profile_error")).contains("not in the default delivery profile");
    }

    @Test
    void o6_firstLink_reusingExistingLocation_thatIsOff_turnsItOn() {
        when(locations.findByName(eq(SHOP), eq("tok"), anyString()))
            .thenReturn(Optional.of(new ShopifyLocationGateway.LocationResult(GID, "Traced Main Warehouse")));
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF), Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group(GID)));

        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, SHOP));

        verify(locations, never()).create(any(), any(), any());
        verify(locations, times(1)).enableOnlineFulfillment(SHOP, "tok", GID);
        assertThat(row().get("shopify_online_fulfillment_status")).isEqualTo("on");
    }

    @Test
    void o7_reconnect_isReadOnly_recordsOffAndOutOfProfile_neverWrites() {
        linkedMain("activated", true);
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group("gid://shopify/Location/shop")));

        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, SHOP));

        verify(locations, never()).enableOnlineFulfillment(any(), any(), any());
        verify(locations, never()).create(any(), any(), any());
        verify(locations, never()).findByName(any(), any(), any());
        verify(profiles, never()).addLocationToGroup(any(), any(), any(), any(), any());
        Map<String, Object> r = row();
        assertThat(r.get("shopify_online_fulfillment_status")).isEqualTo("off");
        assertThat(r.get("shopify_online_fulfillment_checked_at")).isNotNull();
        assertThat(r.get("shopify_delivery_profile_status")).isEqualTo("not_activated");
        assertThat(r.get("shopify_delivery_profile_activated_at")).as("kept — the card reads it as 'taken out'").isNotNull();
        assertThat(r.get("shopify_delivery_profile_checked_at")).isNotNull();
    }

    @Test
    void o8_turnBackOn_ownerOnly() {
        linkedMain("not_activated", true);
        UUID manager = user("manager");
        assertThrows(AccessDeniedException.class, () -> as("MANAGER", () -> controller.turnTracedLocationBackOn(
            new CustomUserDetails(manager, tenantId, "MANAGER", SHOP))));
        verifyNoInteractions(locations, profiles);

        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF), Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group()), Optional.of(group(GID)));
        UUID owner = user("owner");
        Map<String, Object> res = as("OWNER", () -> controller.turnTracedLocationBackOn(
            new CustomUserDetails(owner, tenantId, "OWNER", SHOP)));
        assertThat(res).containsEntry("onlineStatus", "on").containsEntry("profile", "JOINED");
        verify(locations).enableOnlineFulfillment(SHOP, "tok", GID);
        verify(profiles).addLocationToGroup(SHOP, "tok", PROFILE, GROUP, GID);
        assertThat(row().get("shopify_delivery_profile_status")).isEqualTo("activated");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND actor_user_id = ? " +
                                       "AND metadata->>'source' = 'turn_back_on'", Long.class, tenantId, owner)).isEqualTo(2L);
    }

    @Test
    void o9_onlyTheFulfillmentLocationIsEverEdited() {
        UUID main = linkedMain("not_activated", true);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
                    "  shopify_sync_status) VALUES (?, ?, 'Old showroom', 'branch', false, false, " +
                    "  'gid://shopify/Location/other', 'linked')", UUID.randomUUID(), tenantId);
        when(locations.readOnlineFulfillment(eq(SHOP), eq("tok"), anyString())).thenReturn(Optional.of(OFF), Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group(GID)));
        UUID owner = user("owner");

        as("OWNER", () -> controller.turnTracedLocationBackOn(new CustomUserDetails(owner, tenantId, "OWNER", SHOP)));

        verify(locations).enableOnlineFulfillment(SHOP, "tok", GID);
        verify(locations, never()).enableOnlineFulfillment(any(), any(), eq("gid://shopify/Location/other"));
        verify(locations, never()).readOnlineFulfillment(any(), any(), eq("gid://shopify/Location/other"));
        assertThat(jdbc.queryForObject("SELECT shopify_online_fulfillment_status FROM locations WHERE id = ?", String.class, main))
            .isEqualTo("on");
    }

    @Test
    void o10_noShippingScope_profileError_neverJoins() {
        jdbc.update("UPDATE stores SET access_token_scopes = 'read_products,write_locations,write_inventory' WHERE id = ?", storeId);
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group()));

        firstLinkCreating();

        verify(profiles, never()).addLocationToGroup(any(), any(), any(), any(), any());
        verify(profiles, never()).findDefaultProfileLocationGroup(any(), any());
        assertThat(row().get("shopify_delivery_profile_status")).isEqualTo("error");
        assertThat(row().get("shopify_online_fulfillment_status")).isEqualTo("on");
    }

    @Test
    void o11_audit_readsAndFlags_changesNothing() {
        linkedMain("activated", true);
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(OFF));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(group(GID)));
        Map<String, Object> before = row();

        var reports = audit.run(java.util.List.of(tenantId));

        assertThat(reports).hasSize(1);
        var r = reports.get(0);
        assertThat(r.fulfillsOnline()).isEqualTo("false");
        assertThat(r.inDefaultProfile()).isEqualTo("true");
        assertThat(r.flagged()).isTrue();
        assertThat(LocationSettingsAuditJob.line(r)).contains("shop=l1-test").contains("fulfills_online=false").contains("flagged=true");
        verify(locations, never()).enableOnlineFulfillment(any(), any(), any());
        verify(profiles, never()).addLocationToGroup(any(), any(), any(), any(), any());
        assertThat(row()).isEqualTo(before);
        assertThat(LocationSettingsAuditJob.parse("not-a-uuid")).isNull();
    }
}
