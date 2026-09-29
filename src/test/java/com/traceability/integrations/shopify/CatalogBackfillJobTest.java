package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.inventory.ShopifyCatalogActivationService;
import com.traceability.security.EncryptionService;
import com.traceability.inventory.BlocklistService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * One-time catalog backfill (CatalogBackfillJob + CatalogBackfillTrigger), the marker set by a
 * normal import, and activation of variants added by the products webhook.
 *
 * Shopify is a mocked ShopifyGateway: each shop's catalog is two products / three variants
 * (one DRAFT); inventoryActivate calls are counted per shop. Every test builds its own tenants
 * and first marks any store left by an earlier test as done, so only its own stores are eligible.
 *
 * Revert-checked: bf2 (marker guard dropped from ELIGIBLE_STORES → re-import), wh1/wh2
 * (webhook activation removed → no call).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogBackfillJobTest {

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
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final Timestamp OLD_SYNC = Timestamp.valueOf("2026-01-01 10:00:00");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txm;
    @Autowired CatalogBackfillJob backfill;
    @Autowired ShopifySyncService syncService;
    @Autowired ShopifyCatalogActivationService activationService;
    @Autowired ShopifyImportJob importJob;
    @Autowired ShopifyWebhookProcessorJob webhookProcessor;
    @Autowired JobScheduler jobScheduler;
    @Autowired EncryptionService encryption;
    @Autowired BlocklistService blocklist;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyLocationGateway shopifyLocations;

    record Store(UUID tenant, UUID id, String shop) {}

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE stores SET catalog_backfilled_at = now() WHERE catalog_backfilled_at IS NULL");
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.fetchProductsPage(anyString(), anyString(), any()))
            .thenAnswer(inv -> catalogOf(inv.getArgument(0)));
        when(shopifyGateway.fetchOrdersPage(anyString(), anyString(), any(), anyString()))
            .thenReturn(new ShopifyGateway.OrderPage(List.of(), false, null));
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenAnswer(inv -> "gid://shopify/InventoryItem/for-" + inv.getArgument(2));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ── BF: the backfill job ──────────────────────────────────────────────────

    @Test
    void bf1_twoConnectedInDifferentTenants_backfilled_disconnectedUntouched_allVariantsActivated() {
        Store a = store("bf1-a", "connected");
        Store b = store("bf1-b", "connected");
        Store off = store("bf1-off", "disconnected");
        existingVariant(a, "webhook-only");   // came in by webhook, never activated
        Map<UUID, Map<String, Object>> before = merchantState(a, b, off);

        backfill.run();

        assertThat(marker(a)).isNotNull();
        assertThat(marker(b)).isNotNull();
        assertThat(marker(off)).isNull();
        assertThat(products(a)).containsExactlyInAnyOrder("active", "draft", "active");
        assertThat(products(b)).containsExactlyInAnyOrder("active", "draft");
        assertThat(products(off)).isEmpty();
        verify(shopifyGateway, never()).fetchProductsPage(eq(off.shop()), anyString(), any());
        verify(shopifyGateway, never()).fetchOrdersPage(anyString(), anyString(), any(), anyString());

        // ALL variants of each store: the 3 imported + A's never-activated webhook variant.
        assertThat(activationCalls(a)).isEqualTo(4);
        assertThat(activationCalls(b)).isEqualTo(3);
        assertThat(activationCalls(off)).isZero();
        assertThat(merchantState(a, b, off)).as("import_status / last_sync_at / status unchanged").isEqualTo(before);
    }

    @Test
    void bf2_secondRun_doesNothing_markerGuard() {
        Store a = store("bf2-a", "connected");
        backfill.run();
        Timestamp first = marker(a);
        clearInvocations(shopifyGateway);

        backfill.run();

        verify(shopifyGateway, never()).fetchProductsPage(anyString(), anyString(), any());
        verify(shopifyGateway, never()).activateInventoryItem(anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(marker(a)).isEqualTo(first);
    }

    @Test
    void bf3_storeAFailsMidImport_BSucceeds_jobThrows_retryDoesAOnly() {
        Store a = store("bf3-a", "connected");
        Store b = store("bf3-b", "connected");
        Map<UUID, Map<String, Object>> before = merchantState(a, b);
        when(shopifyGateway.fetchProductsPage(eq(a.shop()), anyString(), any()))
            .thenThrow(new ShopifyException("Shopify GraphQL HTTP 502 for " + a.shop()))
            .thenAnswer(inv -> catalogOf(inv.getArgument(0)));

        assertThatThrownBy(() -> backfill.run()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1 of 2").hasMessageContaining(a.id().toString());
        assertThat(marker(a)).isNull();
        assertThat(marker(b)).isNotNull();
        assertThat(merchantState(a, b)).as("a failed store's merchant-visible state is untouched too").isEqualTo(before);

        backfill.run();   // JobRunr's retry

        assertThat(marker(a)).isNotNull();
        verify(shopifyGateway, times(2)).fetchProductsPage(eq(a.shop()), anyString(), any());
        verify(shopifyGateway, times(1)).fetchProductsPage(eq(b.shop()), anyString(), any());
        assertThat(merchantState(a, b)).isEqualTo(before);
    }

    @Test
    void bf4_activationFailure_leavesMarkerNull() {
        Store a = store("bf4-a", "connected");
        doThrow(new ShopifyException("inventoryActivate failed: boom"))
            .when(shopifyGateway).activateInventoryItem(eq(a.shop()), anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> backfill.run()).isInstanceOf(IllegalStateException.class);
        assertThat(marker(a)).isNull();
    }

    @Test
    void bf5_killSwitchOff_jobDoesNothing() {
        Store a = store("bf5-a", "connected");
        CatalogBackfillJob off = new CatalogBackfillJob(dataSource, jdbc, txm, syncService, tokenProvider, activationService, false);

        off.run();

        verify(shopifyGateway, never()).fetchProductsPage(anyString(), anyString(), any());
        assertThat(marker(a)).isNull();
    }

    // ── TR: the startup trigger ───────────────────────────────────────────────

    @Test
    void tr1_killSwitchOff_nothingEnqueued() {
        store("tr1-a", "connected");
        JobScheduler scheduler = mock(JobScheduler.class);

        assertThat(new CatalogBackfillTrigger(jdbc, scheduler, false).enqueueIfNeeded()).isFalse();
        verifyNoInteractions(scheduler);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tr2_eligibleStore_enqueuedUnderTheFixedId_nothingEligible_notEnqueued() {
        JobScheduler scheduler = mock(JobScheduler.class);
        assertThat(new CatalogBackfillTrigger(jdbc, scheduler, true).enqueueIfNeeded()).as("no eligible store").isFalse();
        verifyNoInteractions(scheduler);

        store("tr2-a", "connected");
        assertThat(new CatalogBackfillTrigger(jdbc, scheduler, true).enqueueIfNeeded()).isTrue();
        verify(scheduler).enqueue(eq(CatalogBackfillJob.JOB_ID), any(IocJobLambda.class));
    }

    @Test
    void tr3_repeatedStarts_collapseIntoOneJob_realJobRunrStorage() {
        store("tr3-a", "connected");
        jdbc.update("DELETE FROM jobrunr_jobs WHERE id = ?", CatalogBackfillJob.JOB_ID.toString());
        CatalogBackfillTrigger trigger = new CatalogBackfillTrigger(jdbc, jobScheduler, true);

        assertThat(trigger.enqueueIfNeeded()).isTrue();
        Map<String, Object> first = jdbc.queryForMap("SELECT state, version, createdAt FROM jobrunr_jobs WHERE id = ?",
            CatalogBackfillJob.JOB_ID.toString());

        trigger.enqueueIfNeeded();        // a second start
        trigger.onApplicationReady();     // and a third, through the fail-soft listener

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobrunr_jobs WHERE id = ?", Integer.class,
            CatalogBackfillJob.JOB_ID.toString())).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT state, version, createdAt FROM jobrunr_jobs WHERE id = ?",
            CatalogBackfillJob.JOB_ID.toString())).as("the existing job is left exactly as it was").isEqualTo(first);
        assertThat(first.get("state")).isEqualTo("ENQUEUED");
    }

    // ── IM: the normal import sets the marker ─────────────────────────────────

    @Test
    void im1_normalImportJobSuccess_setsMarker_soTheBackfillSkipsIt() {
        Store a = store("im1-a", "connected");
        importJob.run(a.id(), a.tenant());

        assertThat(jdbc.queryForObject("SELECT import_status::text FROM stores WHERE id = ?", String.class, a.id())).isEqualTo("completed");
        assertThat(marker(a)).isNotNull();
        clearInvocations(shopifyGateway);
        backfill.run();
        verify(shopifyGateway, never()).fetchProductsPage(anyString(), anyString(), any());
    }

    @Test
    void im2_failedImport_leavesMarkerNull() {
        Store a = store("im2-a", "connected");
        when(shopifyGateway.fetchProductsPage(eq(a.shop()), anyString(), any())).thenThrow(new ShopifyException("boom"));
        importJob.run(a.id(), a.tenant());
        assertThat(jdbc.queryForObject("SELECT import_status::text FROM stores WHERE id = ?", String.class, a.id())).isEqualTo("failed");
        assertThat(marker(a)).isNull();
    }

    // ── X: tenant isolation on a real app_user connection ─────────────────────

    @Test
    void x1_appUser_eachStoreWritesOnlyItsOwnTenant_markerUpdateCannotReachTheOther() {
        Store a = store("x1-a", "connected");
        Store b = store("x1-b", "connected");
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        TransactionTemplate appTx = new TransactionTemplate(appTxm);
        ShopifySyncService appSync = new ShopifySyncService(appJdbc, shopifyGateway, encryption, M, appTxm, blocklist,
            new ShopifySameShopGuard(appJdbc, appTxm), 30);
        CatalogBackfillJob appJob = new CatalogBackfillJob(dataSource, appJdbc, appTxm, appSync, tokenProvider, activationService, true);

        appJob.run();

        // Read back as app_user under each tenant: each sees exactly its own shop's catalog.
        List<UUID> seenByA = TenantContext.runAs(a.tenant(), () -> appTx.execute(s -> appJdbc.queryForList(
            "SELECT DISTINCT store_id FROM products", UUID.class)));
        List<UUID> seenByB = TenantContext.runAs(b.tenant(), () -> appTx.execute(s -> appJdbc.queryForList(
            "SELECT DISTINCT store_id FROM products", UUID.class)));
        assertThat(seenByA).containsExactly(a.id());
        assertThat(seenByB).containsExactly(b.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE tenant_id = ? AND external_id LIKE ?",
            Integer.class, b.tenant(), "gid://shopify/Product/" + a.shop() + "%")).isZero();
        assertThat(marker(a)).isNotNull();
        assertThat(marker(b)).isNotNull();

        // The marker write path under tenant A cannot touch B's store; the same-tenant control can.
        jdbc.update("UPDATE stores SET catalog_backfilled_at = NULL WHERE id IN (?, ?)", a.id(), b.id());
        Integer other = TenantContext.runAs(a.tenant(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE stores SET catalog_backfilled_at = now() WHERE id = ?", b.id())));
        Integer own = TenantContext.runAs(a.tenant(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE stores SET catalog_backfilled_at = now() WHERE id = ?", a.id())));
        assertThat(other).isZero();
        assertThat(own).isEqualTo(1);
        assertThat(marker(b)).isNull();
        jdbc.update("UPDATE stores SET catalog_backfilled_at = now() WHERE id = ?", b.id());
    }

    // ── WH: products webhook activates only newly inserted variants ──────────

    @Test
    void wh1_newProductByWebhook_activatesEachNewVariantOnce_redeliveryActivatesNothing() {
        Store a = store("wh1-a", "connected");
        jdbc.update("UPDATE stores SET catalog_backfilled_at = now() WHERE id = ?", a.id());

        process(a, "products/create", webhookProduct(900, 901, 902));
        assertThat(activationCalls(a)).isEqualTo(2);
        verify(shopifyGateway).activateInventoryItem(eq(a.shop()), anyString(),
            eq("gid://shopify/InventoryItem/for-gid://shopify/ProductVariant/901"), anyString(), anyString());
        verify(shopifyGateway).activateInventoryItem(eq(a.shop()), anyString(),
            eq("gid://shopify/InventoryItem/for-gid://shopify/ProductVariant/902"), anyString(), anyString());

        clearInvocations(shopifyGateway);
        process(a, "products/create", webhookProduct(900, 901, 902));   // Shopify redelivery
        assertThat(activationCalls(a)).isZero();
    }

    @Test
    void wh2_updateAddsOneVariant_exactlyOneActivation_forIt() {
        Store a = store("wh2-a", "connected");
        jdbc.update("UPDATE stores SET catalog_backfilled_at = now() WHERE id = ?", a.id());
        process(a, "products/create", webhookProduct(910, 911));
        clearInvocations(shopifyGateway);

        process(a, "products/update", webhookProduct(910, 911, 912));

        assertThat(activationCalls(a)).isEqualTo(1);
        verify(shopifyGateway).activateInventoryItem(eq(a.shop()), anyString(),
            eq("gid://shopify/InventoryItem/for-gid://shopify/ProductVariant/912"), anyString(), anyString());
    }

    @Test
    void wh3_activationFailure_neverFailsTheProductUpsert() {
        Store a = store("wh3-a", "connected");
        jdbc.update("UPDATE stores SET catalog_backfilled_at = now() WHERE id = ?", a.id());
        doThrow(new ShopifyException("inventoryActivate failed: boom"))
            .when(shopifyGateway).activateInventoryItem(anyString(), anyString(), anyString(), anyString(), anyString());

        UUID event = process(a, "products/create", webhookProduct(920, 921));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM variants v JOIN products p ON p.id = v.product_id " +
            "WHERE p.store_id = ? AND p.external_id = 'gid://shopify/Product/920'", Integer.class, a.id())).isEqualTo(1);
        Map<String, Object> ev = jdbc.queryForMap("SELECT processed_at, process_error FROM shopify_webhook_events WHERE id = ?", event);
        assertThat(ev.get("processed_at")).isNotNull();
        assertThat(ev.get("process_error")).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Store store(String name, String status) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, last_sync_at) " +
                    "VALUES (?, ?, 'shopify', ?, ?::store_status, 'completed', ?)", store, tenant, shop, status, OLD_SYNC);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
                    "VALUES (gen_random_uuid(), ?, 'Main Warehouse', ?, 'linked', true)", tenant, "gid://shopify/Location/" + shop);
        return new Store(tenant, store, shop);
    }

    private void existingVariant(Store s, String key) {
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Old', 'active')",
            product, s.tenant(), s.id(), "gid://shopify/Product/" + s.shop() + "-" + key);
        jdbc.update("INSERT INTO variants (tenant_id, product_id, external_id, title) VALUES (?, ?, ?, 'Old')",
            s.tenant(), product, "gid://shopify/ProductVariant/" + s.shop() + "-" + key);
    }

    /** Each shop's Shopify catalog: an ACTIVE product with 2 variants and a DRAFT product with 1. */
    private static ShopifyGateway.ProductPage catalogOf(String shop) {
        String p = "gid://shopify/Product/" + shop, v = "gid://shopify/ProductVariant/" + shop;
        return new ShopifyGateway.ProductPage(List.of(
            new ShopifyGateway.Product(p + "-1", "Shirt", "active", null, List.of(
                new ShopifyGateway.Variant(v + "-11", "S11", "M", new BigDecimal("100.00")),
                new ShopifyGateway.Variant(v + "-12", "S12", "L", new BigDecimal("100.00")))),
            new ShopifyGateway.Product(p + "-2", "Coat", "draft", null, List.of(
                new ShopifyGateway.Variant(v + "-21", "S21", "One", new BigDecimal("900.00"))))),
            false, null);
    }

    private static ObjectNode webhookProduct(long productId, long... variantIds) {
        ObjectNode p = M.createObjectNode().put("id", productId)
            .put("admin_graphql_api_id", "gid://shopify/Product/" + productId)
            .put("title", "Webhook product " + productId).put("status", "draft");
        ArrayNode vs = p.putArray("variants");
        for (long id : variantIds) {
            vs.addObject().put("id", id).put("admin_graphql_api_id", "gid://shopify/ProductVariant/" + id)
                .put("sku", "W" + id).put("title", "V" + id).put("price", "100.00");
        }
        return p;
    }

    private UUID process(Store s, String topic, ObjectNode payload) {
        UUID event = jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id", UUID.class,
            s.tenant(), topic, s.shop(), "wh-" + UUID.randomUUID(), payload.toString());
        webhookProcessor.process(event, s.tenant());
        return event;
    }

    private int activationCalls(Store s) {
        return (int) Mockito.mockingDetails(shopifyGateway).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("activateInventoryItem") && s.shop().equals(i.getArgument(0)))
            .count();
    }

    private Timestamp marker(Store s) {
        return jdbc.queryForObject("SELECT catalog_backfilled_at FROM stores WHERE id = ?", Timestamp.class, s.id());
    }

    private List<String> products(Store s) {
        return jdbc.queryForList("SELECT status FROM products WHERE store_id = ?", String.class, s.id());
    }

    private Map<UUID, Map<String, Object>> merchantState(Store... stores) {
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        for (Store s : stores) {
            out.put(s.id(), jdbc.queryForMap(
                "SELECT import_status::text AS import_status, last_sync_at, status::text AS status, import_summary::text AS summary " +
                "FROM stores WHERE id = ?", s.id()));
        }
        return out;
    }
}
