package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddress;
import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddressState;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.traceability.inventory.MainLocationAddressService.GENERIC;
import static com.traceability.inventory.MainLocationAddressService.Outcome;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Feature A (2026-10-25) — the address on the Traced Main Warehouse's Shopify location.
 *
 *   la1  empty address → generic written (filled), what we wrote is stored
 *   la2  merchant-set address → has_address, never edited
 *   la3  address still equal to what Traced last wrote → overwritten (filled)
 *   la4  local pickup on + generic → local_pickup_on, never edited
 *   la5  dry run → would-be outcome recorded (dry_run=true), no edit, nothing "written"
 *   la6  missing write_locations → missing_scope, no Shopify call
 *   la7  main location not linked → not_linked, no Shopify call
 *   la8  read / edit failure → failed + error; the B job throws (JobRunr retries)
 *   la9  location target: only the main location's GID is ever read / edited
 *   la10 edit variables carry ONLY input.address (never name / fulfillsOnlineOrders)
 *   la11 provisioning: create sends the generic address and records it; reuse of an existing
 *        empty "Traced Main Warehouse" fills it; a merchant-set one is left alone
 *   la12 LocationController (fulfillment location) creates with the address
 *   la13 setMainLocationAddressAfterCommit: rollback → no job; commit → one job, which applies it
 *   la14 backfill: eligible list, dry run default, not_traced_main left alone, final outcomes
 *        not re-listed, failed → run throws
 *   la15 trigger: off → nothing; dry-run default; write mode → write job id
 *   la16 app_user + RLS: the service works under RLS for its own tenant and can't touch another's
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocationAddressTest {

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
        r.add("shopify.api-version",        () -> "2026-04");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean ShopifyLocationGateway gateway;
    @MockBean ShopifyTokenProvider   tokenProvider;
    @MockBean ShopifyDeliveryProfileGateway deliveryProfiles;
    @MockBean JobScheduler           jobScheduler;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired MainLocationAddressService service;
    @Autowired MainLocationAddressJob job;
    @Autowired ShopifyLocationProvisioningService provisioning;
    @Autowired LocationController controller;
    @Autowired LocationAddressBackfillJob backfill;
    @Autowired StoreRepository storeRepository;
    @Autowired ObjectMapper mapper;

    static final LocationAddress EMPTY_EG   = new LocationAddress(null, null, null, null, null, "EG");
    static final LocationAddress MERCHANT   = new LocationAddress("12 Tahrir St", "Floor 3", "Giza", "GZ", "12511", "EG");

    UUID tenantId, storeId, locationId;
    String shop, gid;

    @BeforeEach
    void setup() {
        reset(gateway, tokenProvider, jobScheduler);
        jdbc.update("DELETE FROM locations");
        jdbc.update("DELETE FROM stores");
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID(); locationId = UUID.randomUUID();
        shop = "la-" + tenantId.toString().substring(0, 8) + ".myshopify.com";
        gid  = "gid://shopify/Location/" + Math.abs(tenantId.getMostSignificantBits() % 100000);
        tenant(tenantId, storeId, shop, "read_products,write_locations,write_inventory");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, " +
                "shopify_location_id, shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')",
            locationId, tenantId, gid);
        when(tokenProvider.getValidToken(any())).thenReturn("test-token");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void tenant(UUID t, UUID s, String domain, String scopes) {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'LA Tenant')", t);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'connected', ?)", s, t, domain, scopes);
    }

    private void shopifyHas(String g, LocationAddress a, boolean localPickup) {
        when(gateway.readLocationAddress(anyString(), anyString(), eq(g)))
            .thenReturn(Optional.of(new LocationAddressState("Traced Main Warehouse", a, localPickup)));
    }

    private MainLocationAddressService.Result run(LocationAddress desired, boolean dryRun) {
        return TenantContext.runAs(tenantId, () -> service.apply(tenantId, desired, dryRun, false));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT shopify_address_outcome AS outcome, shopify_address_dry_run AS dry, " +
            "shopify_address_written::text AS written, shopify_address_error AS error FROM locations WHERE id = ?", locationId);
    }

    private LocationAddress written() throws Exception {
        String w = (String) row().get("written");
        if (w == null) return null;
        var n = mapper.readTree(w);
        return new LocationAddress(n.path("address1").asText(null), n.path("address2").asText(null),
            n.path("city").asText(null), n.path("provinceCode").asText(null), n.path("zip").asText(null),
            n.path("countryCode").asText(null));
    }

    private void storeWritten(LocationAddress a) {
        jdbc.update("UPDATE locations SET shopify_address_written = ?::jsonb WHERE id = ?", service.toJson(a), locationId);
    }

    @Test
    void la1_emptyAddress_genericWrittenAndStored() throws Exception {
        shopifyHas(gid, EMPTY_EG, false);
        var r = run(GENERIC, false);
        assertThat(r.outcome()).isEqualTo(Outcome.filled);
        assertThat(r.wrote()).isTrue();
        verify(gateway).editLocationAddress(shop, "test-token", gid, GENERIC);
        assertThat(row().get("outcome")).isEqualTo("filled");
        assertThat(row().get("dry")).isEqualTo(false);
        assertThat(written()).isEqualTo(GENERIC);
        assertThat(GENERIC).isEqualTo(new LocationAddress("Main warehouse", null, "Cairo", "C", "11511", "EG"));

        // Re-run: Shopify now shows what we wrote → filled again, no second edit.
        shopifyHas(gid, GENERIC, false);
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.filled);
        verify(gateway, times(1)).editLocationAddress(any(), any(), any(), any());
    }

    @Test
    void la2_merchantSetAddress_hasAddress_neverEdited() {
        shopifyHas(gid, MERCHANT, false);
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.has_address);
        // Only city set by the merchant still counts as theirs.
        shopifyHas(gid, new LocationAddress(null, null, "Alexandria", null, null, "EG"), false);
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.has_address);
        verify(gateway, never()).editLocationAddress(any(), any(), any(), any());
        assertThat(row().get("outcome")).isEqualTo("has_address");
        assertThat(row().get("written")).isNull();
    }

    @Test
    void la3_stillWhatTracedWrote_isOverwritten() throws Exception {
        storeWritten(GENERIC);
        shopifyHas(gid, GENERIC, false);
        var r = run(MERCHANT, false);
        assertThat(r.outcome()).isEqualTo(Outcome.filled);
        verify(gateway).editLocationAddress(shop, "test-token", gid, MERCHANT);
        assertThat(written()).isEqualTo(MERCHANT);

        // Shopify differs from what we wrote (merchant changed it in Shopify) → left alone.
        shopifyHas(gid, new LocationAddress("Somewhere else", null, "Cairo", "C", null, "EG"), false);
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.has_address);
        verify(gateway, times(1)).editLocationAddress(any(), any(), any(), any());
    }

    @Test
    void la4_localPickupOn_genericNeverWritten() {
        shopifyHas(gid, EMPTY_EG, true);
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.local_pickup_on);
        verify(gateway, never()).editLocationAddress(any(), any(), any(), any());
        assertThat(row().get("outcome")).isEqualTo("local_pickup_on");
        // A real (merchant) address is not held back by local pickup.
        assertThat(run(MERCHANT, false).outcome()).isEqualTo(Outcome.filled);
    }

    @Test
    void la5_dryRun_recordsWouldBeOutcome_noEdit() {
        shopifyHas(gid, EMPTY_EG, false);
        var r = run(GENERIC, true);
        assertThat(r.outcome()).isEqualTo(Outcome.filled);
        assertThat(r.wrote()).isFalse();
        verify(gateway, never()).editLocationAddress(any(), any(), any(), any());
        assertThat(row().get("outcome")).isEqualTo("filled");
        assertThat(row().get("dry")).isEqualTo(true);
        assertThat(row().get("written")).isNull();
    }

    @Test
    void la6_missingScope_noShopifyCall() {
        jdbc.update("UPDATE stores SET access_token_scopes = 'read_products' WHERE id = ?", storeId);
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.missing_scope);
        verifyNoInteractions(gateway);
        assertThat(row().get("outcome")).isEqualTo("missing_scope");
    }

    @Test
    void la7_notLinked_noShopifyCall() {
        jdbc.update("UPDATE locations SET shopify_location_id = NULL, shopify_sync_status = 'error' WHERE id = ?", locationId);
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.not_linked);
        verifyNoInteractions(gateway);
        assertThat(row().get("outcome")).isEqualTo("not_linked");
    }

    @Test
    void la8_failures_recorded_jobThrowsForRetry() {
        when(gateway.readLocationAddress(any(), any(), any())).thenThrow(new ShopifyException("boom read"));
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.failed);
        assertThat((String) row().get("error")).contains("boom read");

        reset(gateway);
        shopifyHas(gid, EMPTY_EG, false);
        doThrow(new ShopifyException("locationEdit failed: bad zip")).when(gateway).editLocationAddress(any(), any(), any(), any());
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.failed);
        assertThat(row().get("written")).isNull();
        assertThatThrownBy(() -> job.apply(tenantId, "Main warehouse", null, "Cairo", "C", "11511", "EG"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("retry");

        // Location gone in Shopify → failed too.
        reset(gateway);
        when(gateway.readLocationAddress(any(), any(), any())).thenReturn(Optional.empty());
        assertThat(run(GENERIC, false).outcome()).isEqualTo(Outcome.failed);
    }

    @Test
    void la9_onlyTheMainLocationGidIsTouched() {
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, " +
                "shopify_location_id, shopify_sync_status) VALUES (?, ?, 'Showroom', 'branch', false, false, " +
                "'gid://shopify/Location/other', 'linked')", UUID.randomUUID(), tenantId);
        shopifyHas(gid, EMPTY_EG, false);
        shopifyHas("gid://shopify/Location/other", EMPTY_EG, false);
        run(GENERIC, false);
        verify(gateway, never()).readLocationAddress(any(), any(), eq("gid://shopify/Location/other"));
        verify(gateway, never()).editLocationAddress(any(), any(), eq("gid://shopify/Location/other"), any());
        verify(gateway).editLocationAddress(any(), any(), eq(gid), any());
    }

    @Test
    void la10_editVariables_onlyAddress() throws Exception {
        var m = Class.forName("com.traceability.integrations.shopify.ShopifyLocationGatewayImpl")
            .getDeclaredMethod("editAddressVariables", ObjectMapper.class, String.class, LocationAddress.class);
        m.setAccessible(true);
        ObjectNode vars = (ObjectNode) m.invoke(null, mapper, gid, GENERIC);
        assertThat(vars.path("id").asText()).isEqualTo(gid);
        List<String> inputFields = iter(vars.path("input").fieldNames());
        assertThat(inputFields).containsExactly("address");
        var a = vars.path("input").path("address");
        assertThat(a.path("address1").asText()).isEqualTo("Main warehouse");
        assertThat(a.path("city").asText()).isEqualTo("Cairo");
        assertThat(a.path("provinceCode").asText()).isEqualTo("C");
        assertThat(a.path("zip").asText()).isEqualTo("11511");
        assertThat(a.path("countryCode").asText()).isEqualTo("EG");
        assertThat(a.has("address2")).isFalse();
        var f = Class.forName("com.traceability.integrations.shopify.ShopifyLocationGatewayImpl")
            .getDeclaredField("LOCATION_EDIT_ADDRESS_MUTATION");
        f.setAccessible(true);
        String mutation = (String) f.get(null);
        assertThat(mutation).contains("locationEdit(id: $id, input: $input)")
            .doesNotContain("fulfillsOnlineOrders").doesNotContain("inventory");
    }

    private static List<String> iter(java.util.Iterator<String> it) {
        List<String> l = new java.util.ArrayList<>(); it.forEachRemaining(l::add); return l;
    }

    @Test
    void la11_provisioning_createWithGeneric_reuseFillsOnlyIfEmpty() throws Exception {
        jdbc.update("UPDATE locations SET shopify_location_id = NULL, shopify_sync_status = 'unsynced' WHERE id = ?", locationId);
        when(gateway.findByName(eq(shop), eq("test-token"), anyString())).thenReturn(Optional.empty());
        when(gateway.create(eq(shop), eq("test-token"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult(gid, "Traced Main Warehouse"));
        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, shop));

        ArgumentCaptor<ShopifyLocationGateway.LocationInput> in = ArgumentCaptor.forClass(ShopifyLocationGateway.LocationInput.class);
        verify(gateway).create(eq(shop), eq("test-token"), in.capture());
        assertThat(in.getValue()).isEqualTo(new ShopifyLocationGateway.LocationInput(
            "Traced Main Warehouse", "Main warehouse", null, "Cairo", "C", "11511", "EG", true));
        assertThat(written()).isEqualTo(GENERIC);
        assertThat(row().get("outcome")).isEqualTo("filled");

        // Reuse of an existing, empty "Traced Main Warehouse" → filled through the fill rule.
        reset(gateway);
        jdbc.update("UPDATE locations SET shopify_location_id = NULL, shopify_sync_status = 'unsynced', " +
            "shopify_address_written = NULL, shopify_address_outcome = NULL WHERE id = ?", locationId);
        when(gateway.findByName(eq(shop), eq("test-token"), anyString()))
            .thenReturn(Optional.of(new ShopifyLocationGateway.LocationResult(gid, "Traced Main Warehouse")));
        shopifyHas(gid, EMPTY_EG, false);
        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, shop));
        verify(gateway, never()).create(any(), any(), any());
        verify(gateway).editLocationAddress(shop, "test-token", gid, GENERIC);

        // Reuse of one the merchant addressed → left alone; a read failure never breaks the link.
        reset(gateway);
        jdbc.update("UPDATE locations SET shopify_location_id = NULL, shopify_sync_status = 'unsynced', " +
            "shopify_address_written = NULL WHERE id = ?", locationId);
        when(gateway.findByName(eq(shop), eq("test-token"), anyString()))
            .thenReturn(Optional.of(new ShopifyLocationGateway.LocationResult(gid, "Traced Main Warehouse")));
        shopifyHas(gid, MERCHANT, false);
        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, shop));
        verify(gateway, never()).editLocationAddress(any(), any(), any(), any());
        assertThat(row().get("outcome")).isEqualTo("has_address");

        reset(gateway);
        jdbc.update("UPDATE locations SET shopify_location_id = NULL, shopify_sync_status = 'unsynced' WHERE id = ?", locationId);
        when(gateway.findByName(eq(shop), eq("test-token"), anyString()))
            .thenReturn(Optional.of(new ShopifyLocationGateway.LocationResult(gid, "Traced Main Warehouse")));
        when(gateway.readLocationAddress(any(), any(), any())).thenThrow(new RuntimeException("down"));
        TenantContext.runAs(tenantId, () -> provisioning.ensureTracedWarehouse(tenantId, storeId, shop));
        assertThat(jdbc.queryForObject("SELECT shopify_sync_status FROM locations WHERE id = ?", String.class, locationId))
            .isEqualTo("linked");
    }

    @Test
    void la12_locationController_createsFulfillmentLocationWithAddress() throws Exception {
        jdbc.update("DELETE FROM locations WHERE id = ?", locationId);
        when(gateway.create(eq(shop), eq("test-token"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult(gid, "My WH"));
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken("o", "n/a", "ROLE_OWNER");
        auth.setAuthenticated(true);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            TenantContext.runAs(tenantId, () -> controller.create(Map.of("name", "My WH", "type", "warehouse", "isFulfillment", "true")));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        ArgumentCaptor<ShopifyLocationGateway.LocationInput> in = ArgumentCaptor.forClass(ShopifyLocationGateway.LocationInput.class);
        verify(gateway).create(eq(shop), eq("test-token"), in.capture());
        assertThat(in.getValue().provinceCode()).isEqualTo("C");
        assertThat(in.getValue().zip()).isEqualTo("11511");
        assertThat(in.getValue().fulfillsOnlineOrders()).isTrue();
        String w = jdbc.queryForObject("SELECT shopify_address_written::text FROM locations WHERE tenant_id = ? AND is_fulfillment",
            String.class, tenantId);
        assertThat(mapper.readTree(w).path("provinceCode").asText()).isEqualTo("C");
    }

    @Test
    @SuppressWarnings("unchecked")
    void la13_afterCommit_rollbackMeansNoJob_commitEnqueuesOneThatApplies() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txm);
        tx.execute(s -> { service.setMainLocationAddressAfterCommit(tenantId, MERCHANT); s.setRollbackOnly(); return null; });
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));

        tx.execute(s -> {
            service.setMainLocationAddressAfterCommit(tenantId, MERCHANT);
            verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));   // not before commit
            return null;
        });
        ArgumentCaptor<IocJobLambda<MainLocationAddressJob>> captor = ArgumentCaptor.forClass(IocJobLambda.class);
        verify(jobScheduler, times(1)).enqueue(captor.capture());

        shopifyHas(gid, EMPTY_EG, false);
        captor.getValue().accept(job);          // run the enqueued job body
        verify(gateway).editLocationAddress(shop, "test-token", gid, MERCHANT);
        assertThat(written()).isEqualTo(MERCHANT);
    }

    @Test
    void la14_backfill_dryRunThenWrite_scopeAndEligibility() {
        // Tenant 2: merchant-addressed; tenant 3: Shopify location not named "Traced Main Warehouse";
        // tenant 4: disconnected store (never listed).
        UUID t2 = UUID.randomUUID(), s2 = UUID.randomUUID(), l2 = UUID.randomUUID();
        tenant(t2, s2, "la2-" + t2.toString().substring(0, 6) + ".myshopify.com", "write_locations");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
            "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, 'gid://shopify/Location/t2', 'linked')", l2, t2);
        UUID t3 = UUID.randomUUID(), s3 = UUID.randomUUID(), l3 = UUID.randomUUID();
        tenant(t3, s3, "la3-" + t3.toString().substring(0, 6) + ".myshopify.com", "write_locations");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
            "shopify_sync_status) VALUES (?, ?, 'Shop floor', 'warehouse', true, true, 'gid://shopify/Location/t3', 'linked')", l3, t3);
        UUID t4 = UUID.randomUUID(), s4 = UUID.randomUUID();
        tenant(t4, s4, "la4-" + t4.toString().substring(0, 6) + ".myshopify.com", "write_locations");
        jdbc.update("UPDATE stores SET status = 'disconnected' WHERE id = ?", s4);

        shopifyHas(gid, EMPTY_EG, false);
        shopifyHas("gid://shopify/Location/t2", MERCHANT, false);
        when(gateway.readLocationAddress(anyString(), anyString(), eq("gid://shopify/Location/t3")))
            .thenReturn(Optional.of(new LocationAddressState("Shop floor", EMPTY_EG, false)));

        var dry = backfill.runOnce(true);
        assertThat(dry.tenants()).isEqualTo(3);
        assertThat(dry.counts()).containsEntry(Outcome.filled, 1).containsEntry(Outcome.has_address, 1)
            .containsEntry(Outcome.not_traced_main, 1);
        verify(gateway, never()).editLocationAddress(any(), any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT shopify_address_outcome FROM locations WHERE id = ?", String.class, l3)).isNull();

        var write = backfill.runOnce(false);
        assertThat(write.tenants()).isEqualTo(3);   // dry-run outcomes are not final
        verify(gateway, times(1)).editLocationAddress(any(), any(), any(), any());
        verify(gateway).editLocationAddress(shop, "test-token", gid, GENERIC);

        // Final write-mode outcomes (filled / has_address) are not listed again; not_traced_main stays listed.
        var again = backfill.runOnce(false);
        assertThat(again.tenants()).isEqualTo(1);
        assertThat(again.counts()).containsOnlyKeys(Outcome.not_traced_main);

        // A failure → run() throws (JobRunr retries).
        when(gateway.readLocationAddress(anyString(), anyString(), eq("gid://shopify/Location/t3")))
            .thenThrow(new ShopifyException("timeout"));
        assertThatThrownBy(() -> backfill.run(false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void la15_trigger_modes() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneId.of("Africa/Cairo"));
        assertThat(new LocationAddressBackfillTrigger(jdbc, jobScheduler, "off", clock).enqueueIfNeeded()).isFalse();
        verify(jobScheduler, never()).enqueue(any(UUID.class), any(IocJobLambda.class));

        assertThat(new LocationAddressBackfillTrigger(jdbc, jobScheduler, "dry-run", clock).enqueueIfNeeded()).isTrue();
        verify(jobScheduler).enqueue(eq(LocationAddressBackfillJob.jobIdFor(java.time.LocalDate.of(2026, 10, 25), true)),
            any(IocJobLambda.class));
        assertThat(new LocationAddressBackfillTrigger(jdbc, jobScheduler, "bogus", clock).enqueueIfNeeded()).isTrue();
        verify(jobScheduler, times(2)).enqueue(eq(LocationAddressBackfillJob.jobIdFor(java.time.LocalDate.of(2026, 10, 25), true)),
            any(IocJobLambda.class));
        assertThat(new LocationAddressBackfillTrigger(jdbc, jobScheduler, "write", clock).enqueueIfNeeded()).isTrue();
        verify(jobScheduler).enqueue(eq(LocationAddressBackfillJob.jobIdFor(java.time.LocalDate.of(2026, 10, 25), false)),
            any(IocJobLambda.class));
        // Default property value is dry-run.
        assertThat(new LocationAddressBackfillTrigger(jdbc, jobScheduler, null, clock).enqueueIfNeeded()).isTrue();
    }

    @Test
    void la16_appUser_rls_ownTenantOnly() {
        UUID tB = UUID.randomUUID(), sB = UUID.randomUUID();
        tenant(tB, sB, "lab-" + tB.toString().substring(0, 6) + ".myshopify.com", "write_locations");

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        MainLocationAddressService appService = new MainLocationAddressService(appJdbc, appTxm, gateway, tokenProvider,
            new StoreRepository(appJdbc, appTxm), jobScheduler, mapper);
        shopifyHas(gid, EMPTY_EG, false);

        // Tenant B's context: B has no main location → not_linked; A's row is invisible and untouched.
        var rB = TenantContext.runAs(tB, () -> appService.apply(tB, GENERIC, false, false));
        assertThat(rB.outcome()).isEqualTo(Outcome.not_linked);
        Integer touched = TenantContext.runAs(tB, () -> new TransactionTemplate(appTxm).execute(s -> appJdbc.update(
            "UPDATE locations SET shopify_address_outcome = 'failed' WHERE id = ?", locationId)));
        assertThat(touched).isZero();
        assertThat(row().get("outcome")).isNull();
        verifyNoInteractions(gateway);

        // No tenant context at all → refused before any read.
        assertThatThrownBy(() -> appService.apply(tenantId, GENERIC, false, false)).isInstanceOf(RuntimeException.class);

        // Positive control under RLS: A's own context works end to end.
        var rA = TenantContext.runAs(tenantId, () -> appService.apply(tenantId, GENERIC, false, false));
        assertThat(rA.outcome()).isEqualTo(Outcome.filled);
        assertThat(row().get("outcome")).isEqualTo("filled");
    }
}
