package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.account.ConnectionsController;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.shopify.MarketShippingCoverage;
import com.traceability.integrations.shopify.MarketShippingCoverage.Result;
import com.traceability.integrations.shopify.MarketShippingCoverage.Verdict;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * L1b-2 (approved by Marawan 2026-11-16) — on a market-driven store Traced READS the primary market's shipping rates;
 * Traced never writes a delivery profile or a rate.
 *
 *   k1  covered + fulfils online → Ready (the only status the M1 gate opens on); covered_by + "Not added for" carried
 *       by /connections and /locations
 *   k2  a verified not_covered beats the owner's "I've added it" → add_to_shipping_rates
 *   k3  no verdict → the owner-confirmed path (owner_confirmed when confirmed, add_to_shipping_rates when not); a stale
 *       'covered' is cleared, never kept on a no-verdict read
 *   k4  a FAILED read changes nothing (the last verdict stays)
 *   k5  without read_markets → 'no_access', no Markets call; Shopify's ACCESS_DENIED → 'no_access'
 *   k6  covered while fulfils-online isn't read as on → unknown, never Ready
 *   k7  Check again: owner and manager, a worker is refused; one per 2 minutes per tenant (second → 429 RATE_LIMITED +
 *       nextAllowedAt, nothing read); after the window it reads again; legacy → the membership read
 *   k8  Check again takes no input from the request (no target) and is owner / manager only
 *   k9  the new-permissions notice: read_markets offered ONLY to the owner of a market-driven OAuth store that hasn't
 *       granted it, with Shopify's optional-scope request URL, and only while the request flag is on
 *   k10 the legacy deep link: the membership read stores the General profile's id → /connections deliveryProfileNumber
 *   k11 every L1b-2 read path (first link, reconnect, hourly, Turn back on, Check again) reaches the Markets read only on
 *       a market-driven store, never on legacy / unknown
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracedMarketShippingTest {

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
        r.add("shopify.markets-scope-request", () -> "true");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean ShopifyLocationGateway locations;
    @MockBean ShopifyDeliveryProfileGateway profiles;
    @MockBean ShopifyTokenProvider tokens;
    @MockBean ShopifyShippingModelGateway models;

    @Autowired JdbcTemplate jdbc;
    @Autowired TracedLocationOnlineService online;
    @Autowired LocationController locationController;
    @Autowired ConnectionsController connections;

    static final String SHOP = "l1b2-test.myshopify.com";
    static final String GID = "gid://shopify/Location/1004";
    static final String SCOPES = "read_products,write_locations,write_inventory,write_shipping";
    static final OnlineFulfillment ON = new OnlineFulfillment(true, true);
    static final Result COVERED = new Result(Verdict.COVERED, "United States — Standard", List.of("Canada"), "covered");
    static final Result NOT_COVERED = new Result(Verdict.NOT_COVERED, null, List.of("United States"), "not_covered");
    static final Result NO_VERDICT = new Result(Verdict.NO_VERDICT, null, List.of(), "primary_market_not_found");

    UUID tenantId;
    UUID storeId;

    @BeforeEach
    void setup() {
        tenantId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'L1b-2 Tenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, connection_type, access_token_scopes) " +
                    "VALUES (?, ?, ?, 'connected', 'oauth', ?)", storeId, tenantId, SHOP, SCOPES + ",read_markets");
        reset(locations, profiles, tokens, models);
        when(tokens.getValidToken(storeId)).thenReturn("tok");
        when(locations.readOnlineFulfillment(SHOP, "tok", GID)).thenReturn(Optional.of(ON));
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

    private UUID linkedMain(String model, String online) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
                    "  shopify_sync_status, shopify_shipping_model, shopify_online_fulfillment_status) " +
                    "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked', ?, ?)",
                    id, tenantId, GID, model, online);
        return id;
    }

    private String readiness() {
        return jdbc.queryForObject("SELECT " + TracedShippingReadiness.SQL + " FROM locations l " +
                                   "WHERE l.tenant_id = ? AND l.is_fulfillment", String.class, tenantId);
    }

    private String marketStatus() {
        return jdbc.queryForObject("SELECT shopify_market_shipping_status FROM locations WHERE tenant_id = ? AND is_fulfillment",
            String.class, tenantId);
    }

    private void marketDriven(Result r) {
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.MARKET_DRIVEN));
        when(models.readMarketCoverage(SHOP, "tok", GID)).thenReturn(Optional.ofNullable(r));
    }

    private <T> T as(String role, java.util.function.Supplier<T> action) {
        var auth = new TestingAuthenticationToken("l1b2-" + role, "n/a", "ROLE_" + role);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return TenantContext.runAs(tenantId, action::get);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, role) VALUES (?, ?, 'L1b-2 user', ?::user_role)", id, tenantId, role);
        return id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> shopifyStatus(String role) {
        UUID u = user(role.toLowerCase());
        return (Map<String, Object>) as(role, () -> connections.status(new CustomUserDetails(u, tenantId, role, SHOP))).get("shopify");
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void k1_covered_andOnline_isReady_withInfo() {
        linkedMain("market_driven", "on");
        marketDriven(COVERED);

        online.refreshQuietly(tenantId);

        assertThat(marketStatus()).isEqualTo("covered");
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.READY);
        Map<String, Object> shopify = shopifyStatus("OWNER");
        assertThat(shopify).containsEntry("tracedMainShipping", "ready").containsEntry("tracedShippingModel", "market_driven")
            .containsEntry("tracedCoveredBy", "United States — Standard");
        assertThat(shopify.get("tracedNotAddedFor")).isEqualTo(List.of("Canada"));
        List<Map<String, Object>> rows = as("OWNER", () -> locationController.list());
        assertThat(rows.get(0).get("shopify_market_shipping_missing")).isEqualTo(List.of("Canada"));
        verifyNoInteractions(profiles);
    }

    @Test
    void k2_verifiedNotCovered_beatsOwnerConfirmed() {
        UUID id = linkedMain("market_driven", "on");
        jdbc.update("UPDATE locations SET shopify_shipping_owner_confirmed_at = now() WHERE id = ?", id);
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.OWNER_CONFIRMED);
        marketDriven(NOT_COVERED);

        online.refreshQuietly(tenantId);

        assertThat(marketStatus()).isEqualTo("not_covered");
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.ADD_TO_SHIPPING_RATES);
        assertThat(shopifyStatus("OWNER").get("tracedNotAddedFor")).isEqualTo(List.of("United States"));
    }

    @Test
    void k3_noVerdict_ownerConfirmedPath_clearsStaleCovered() {
        UUID id = linkedMain("market_driven", "on");
        marketDriven(COVERED);
        online.refreshQuietly(tenantId);
        assertThat(readiness()).isEqualTo("ready");

        marketDriven(NO_VERDICT);
        online.refreshQuietly(tenantId);
        assertThat(marketStatus()).isNull();
        assertThat(readiness()).as("never Ready on an old verdict").isEqualTo(TracedShippingReadiness.ADD_TO_SHIPPING_RATES);
        assertThat(jdbc.queryForObject("SELECT shopify_market_shipping_checked_at IS NOT NULL FROM locations WHERE id = ?",
            Boolean.class, id)).isTrue();

        jdbc.update("UPDATE locations SET shopify_shipping_owner_confirmed_at = now() WHERE id = ?", id);
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.OWNER_CONFIRMED);
    }

    @Test
    void k4_failedRead_changesNothing() {
        linkedMain("market_driven", "on");
        marketDriven(COVERED);
        online.refreshQuietly(tenantId);
        java.sql.Timestamp before = jdbc.queryForObject(
            "SELECT shopify_market_shipping_checked_at FROM locations WHERE tenant_id = ?", java.sql.Timestamp.class, tenantId);

        marketDriven(null);                                                     // the read failed
        online.refreshQuietly(tenantId);

        assertThat(marketStatus()).isEqualTo("covered");
        assertThat(jdbc.queryForObject("SELECT shopify_market_shipping_checked_at FROM locations WHERE tenant_id = ?",
            java.sql.Timestamp.class, tenantId)).isEqualTo(before);
    }

    @Test
    void k5_noReadMarkets_noAccess_noCall() {
        jdbc.update("UPDATE stores SET access_token_scopes = ? WHERE id = ?", SCOPES, storeId);
        linkedMain("market_driven", "on");
        marketDriven(COVERED);

        online.refreshQuietly(tenantId);

        assertThat(marketStatus()).isEqualTo("no_access");
        verify(models, never()).readMarketCoverage(any(), any(), any());
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.ADD_TO_SHIPPING_RATES);

        // Granted but Shopify answers ACCESS_DENIED → no_access as well.
        jdbc.update("UPDATE stores SET access_token_scopes = ? WHERE id = ?", SCOPES + ",read_markets", storeId);
        marketDriven(MarketShippingCoverage.Result.noAccess("access_denied"));
        online.refreshQuietly(tenantId);
        assertThat(marketStatus()).isEqualTo("no_access");
    }

    @Test
    void k6_coveredButOnlineNotOn_isNeverReady() {
        UUID id = linkedMain("market_driven", "on");
        marketDriven(COVERED);
        online.refreshQuietly(tenantId);
        jdbc.update("UPDATE locations SET shopify_online_fulfillment_status = 'error' WHERE id = ?", id);
        assertThat(readiness()).isEqualTo(TracedShippingReadiness.UNKNOWN);
        jdbc.update("UPDATE locations SET shopify_online_fulfillment_status = 'off' WHERE id = ?", id);
        assertThat(readiness()).isEqualTo("online_off");
    }

    @Test
    @SuppressWarnings("unchecked")
    void k7_checkAgain_roles_throttle_legacy() {
        UUID id = linkedMain("market_driven", "on");
        marketDriven(COVERED);

        assertThatThrownBy(() -> as("WORKER", () -> locationController.checkTracedShipping()))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(models);

        ResponseEntity<Map<String, Object>> first = as("MANAGER", () -> locationController.checkTracedShipping());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("readiness", "ready").containsEntry("coveredBy", "United States — Standard")
            .containsEntry("notAddedFor", List.of("Canada"));
        verify(models, times(1)).readMarketCoverage(SHOP, "tok", GID);

        ResponseEntity<Map<String, Object>> second = as("OWNER", () -> locationController.checkTracedShipping());
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getBody()).containsEntry("error", "RATE_LIMITED").containsKey("nextAllowedAt");
        assertThat(second.getBody().get("nextAllowedAt")).isNotNull();
        verify(models, times(1)).readMarketCoverage(SHOP, "tok", GID);              // nothing read the second time
        verify(locations, times(1)).readOnlineFulfillment(SHOP, "tok", GID);

        // After the window it reads again — here the store turned legacy: the membership READ, never a write.
        jdbc.update("UPDATE locations SET shopify_shipping_check_claimed_at = now() - interval '3 minutes' WHERE id = ?", id);
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.LEGACY));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(
            new ShopifyDeliveryProfileGateway.LocationGroupInfo("gid://shopify/DeliveryProfile/77", "g", Set.of(GID))));
        ResponseEntity<Map<String, Object>> third = as("OWNER", () -> locationController.checkTracedShipping());
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(third.getBody()).containsEntry("readiness", "ready").containsEntry("shippingModel", "legacy");
        verify(profiles, times(1)).findDefaultProfileLocationGroup(SHOP, "tok");
        verifyNoMoreInteractions(profiles);
        verify(models, times(1)).readMarketCoverage(any(), any(), any());
    }

    @Test
    void k8_checkAgain_takesNoInput_ownerOrManager() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/traceability/inventory/LocationController.java"));
        int at = src.indexOf("@PostMapping(\"/shopify/shipping-check\")");
        assertThat(at).isPositive();
        String sig = src.substring(at, src.indexOf('{', src.indexOf("public", at)));
        assertThat(sig).contains("@PreAuthorize(\"hasAnyRole('OWNER','MANAGER')\")")
            .doesNotContain("@RequestBody").doesNotContain("@PathVariable").doesNotContain("@RequestParam");
        assertThat(sig.replaceAll("\\s+", " ")).contains("checkTracedShipping()");
    }

    @Test
    @SuppressWarnings("unchecked")
    void k9_marketsPermission_onlyMarketDriven_owner_notGranted() {
        UUID id = linkedMain("market_driven", "on");
        jdbc.update("UPDATE stores SET access_token_scopes = ? WHERE id = ?", SCOPES, storeId);

        Map<String, Object> perms = (Map<String, Object>) shopifyStatus("OWNER").get("newPermissions");
        assertThat(perms).containsEntry("marketsRequestUrl",
            "https://admin.shopify.com/store/l1b2-test/oauth/install?client_id=test-client-id&optional_scopes=read_markets");
        assertThat(perms).doesNotContainKey("openAppUrl");                     // the returns notice is off here

        assertThat(shopifyStatus("MANAGER")).doesNotContainKey("newPermissions");
        jdbc.update("UPDATE locations SET shopify_shipping_model = 'legacy' WHERE id = ?", id);
        assertThat(shopifyStatus("OWNER")).as("never on a legacy store").doesNotContainKey("newPermissions");
        jdbc.update("UPDATE locations SET shopify_shipping_model = 'market_driven' WHERE id = ?", id);
        jdbc.update("UPDATE stores SET access_token_scopes = ? WHERE id = ?", SCOPES + ",read_markets", storeId);
        assertThat(shopifyStatus("OWNER")).as("already granted").doesNotContainKey("newPermissions");
        jdbc.update("UPDATE stores SET access_token_scopes = ?, connection_type = 'custom_app' WHERE id = ?", SCOPES, storeId);
        assertThat(shopifyStatus("OWNER")).as("custom apps set their own scopes").doesNotContainKey("newPermissions");
    }

    @Test
    void k10_legacyDeepLink_profileNumber() {
        linkedMain("legacy", "on");
        when(models.read(SHOP, "tok")).thenReturn(Optional.of(Model.LEGACY));
        when(profiles.findDefaultProfileLocationGroup(SHOP, "tok")).thenReturn(Optional.of(
            new ShopifyDeliveryProfileGateway.LocationGroupInfo("gid://shopify/DeliveryProfile/134823837987", "g", Set.of())));

        online.refreshQuietly(tenantId);

        Map<String, Object> shopify = shopifyStatus("OWNER");
        assertThat(shopify).containsEntry("deliveryProfileNumber", "134823837987")
            .containsEntry("tracedMainShipping", "activate_needed").containsEntry("tracedShippingModel", "legacy");
        verify(models, never()).readMarketCoverage(any(), any(), any());
    }

    @Test
    void k11_marketsRead_onlyOnMarketDriven() {
        linkedMain("unknown", "on");
        when(models.read(SHOP, "tok")).thenReturn(Optional.empty());
        online.refreshQuietly(tenantId);
        TenantContext.runAs(tenantId, () -> online.onReconnect(tenantId));
        verify(models, never()).readMarketCoverage(any(), any(), any());
        verifyNoInteractions(profiles);
        assertThat(marketStatus()).isNull();

        marketDriven(COVERED);
        TenantContext.runAs(tenantId, () -> online.onReconnect(tenantId));
        verify(models, times(1)).readMarketCoverage(SHOP, "tok", GID);
        assertThat(readiness()).isEqualTo("ready");
    }
}
