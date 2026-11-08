package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Part D P3 (2026-11-08) — MainLevelAuditJob, the one-off read-only hidden-stock audit. Real DB, Shopify mocked.
 *
 *   j1 — only the LISTED tenants are audited (an unlisted connected tenant gets no Shopify call); per tenant:
 *        variants with available pieces (at Main and elsewhere counted apart; no available piece → not checked;
 *        review-fixture variants left out), every class counted, hidden stock summed from inactive levels, a ROW
 *        only for what isn't active; batches of at most 10 items (22 items → 10 / 10 / 2)
 *   j2 — writes nothing: every table's rows are unchanged, and the only Shopify calls are auditMainLevels
 *   j3 — output is counts, SKUs and Traced ids only — every line matches the fixed key=value shape (no customer data)
 *   j4 — a failed batch read is counted read_failed (not retried) and the audit goes on; a tenant with no connected
 *        store / no linked main is reported skipped with no Shopify call; a non-id in the list → nothing runs
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "traced.main-level-audit.delay-ms=1")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainLevelAuditJobTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;
    @Autowired MainLevelAuditJob audit;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyLocationGateway locationGateway;
    @MockBean JobScheduler jobScheduler;

    record T(UUID tenant, UUID store, String shop, UUID main, String traced, UUID other, UUID product) {}

    final AtomicInteger seq = new AtomicInteger();
    /** What the mocked Shopify answers per inventory item: state, available, on_hand. */
    final Map<String, Object[]> shopify = new HashMap<>();
    final List<List<String>> batches = new ArrayList<>();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        shopify.clear();
        batches.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.auditMainLevels(anyString(), eq("tok"), anyString(), anyList())).thenAnswer(inv -> {
            List<String> ids = new ArrayList<>(inv.getArgument(3));
            batches.add(ids);
            List<ShopifyGateway.MainLevelAudit> out = new ArrayList<>();
            for (String id : ids) {
                Object[] a = shopify.getOrDefault(id, new Object[]{"active", 1, 1});
                if ("THROW".equals(a[0])) throw new com.traceability.integrations.shopify.ShopifyException("boom");
                out.add(new ShopifyGateway.MainLevelAudit(id, "S-" + id, (String) a[0], (Integer) a[1], (Integer) a[2]));
            }
            return out;
        });
    }

    @Test
    void j1_listedTenantsOnly_classesCounted_hiddenStockSummed_batchesOfTen() {
        T a = tenant("j1a"), b = tenant("j1b"), unlisted = tenant("j1u");
        UUID inactive = variant(a, "INACT-1", "item-j1-inactive"); pieces(a, inactive, a.main(), 2); pieces(a, inactive, a.other(), 1);
        shopify.put("item-j1-inactive", new Object[]{"inactive", 4, 6});
        UUID missing = variant(a, "MISS-1", "item-j1-missing"); pieces(a, missing, a.main(), 1);
        shopify.put("item-j1-missing", new Object[]{"missing", null, null});
        UUID unknown = variant(a, "UNK-1", "item-j1-unknown"); pieces(a, unknown, a.main(), 1);
        shopify.put("item-j1-unknown", new Object[]{"unknown", null, null});
        UUID gone = variant(a, "GONE-1", "item-j1-gone"); pieces(a, gone, a.main(), 1);
        shopify.put("item-j1-gone", new Object[]{"item_gone", null, null});
        UUID noItem = variant(a, "NOITEM-1", null); pieces(a, noItem, a.main(), 3);
        for (int i = 0; i < 18; i++) pieces(a, variant(a, "ACT-" + i, "item-j1-act-" + i), a.main(), 1);   // active
        variant(a, "NOSTOCK", "item-j1-nostock");                                                          // no piece
        UUID sold = variant(a, "SOLD", "item-j1-sold"); piece(a, sold, a.main(), "sold");                   // none available
        UUID fixture = variant(a, "FIXTURE", "item-j1-fixture", "review-fixture:1"); pieces(a, fixture, a.main(), 1);
        pieces(b, variant(b, "B-1", "item-j1b-1"), b.main(), 1);
        pieces(unlisted, variant(unlisted, "U-1", "item-j1u-1"), unlisted.main(), 1);

        List<MainLevelAuditJob.Report> reports = audit.run(List.of(a.tenant(), b.tenant()));

        assertThat(reports).extracting(MainLevelAuditJob.Report::tenantId).containsExactly(a.tenant(), b.tenant());
        verify(shopifyGateway, never()).auditMainLevels(eq(unlisted.shop()), any(), any(), any());
        MainLevelAuditJob.Report ra = reports.get(0);
        assertThat(ra.skipped()).isNull();
        assertThat(ra.counts()).containsEntry("active", 18).containsEntry("inactive", 1).containsEntry("missing", 1)
            .containsEntry("unknown", 1).containsEntry("item_gone", 1).containsEntry("no_item_id", 1)
            .containsEntry("read_failed", 0);
        assertThat(ra.hiddenAvailable()).isEqualTo(4);
        assertThat(ra.hiddenOnHand()).isEqualTo(6);
        assertThat(ra.rows()).extracting(MainLevelAuditJob.Row::cls)
            .containsExactlyInAnyOrder("inactive", "missing", "unknown", "item_gone", "no_item_id");
        MainLevelAuditJob.Row row = ra.rows().stream().filter(r -> "inactive".equals(r.cls())).findFirst().orElseThrow();
        assertThat(row).isEqualTo(new MainLevelAuditJob.Row("inactive", inactive, "INACT-1", 2, 1, 4, 6));
        assertThat(ra.rows().stream().filter(r -> "no_item_id".equals(r.cls())).findFirst().orElseThrow().tracedMain()).isEqualTo(3);

        // Tenant a's 22 items with an inventory item id: batches of 10 / 10 / 2, all at its own main location.
        List<List<String>> aBatches = batches.subList(0, 3);
        assertThat(aBatches).extracting(List::size).containsExactly(10, 10, 2);
        assertThat(aBatches.stream().flatMap(List::stream)).doesNotContain("item-j1-nostock", "item-j1-sold", "item-j1-fixture");
        verify(shopifyGateway, times(3)).auditMainLevels(eq(a.shop()), eq("tok"), eq(a.traced()), anyList());
        assertThat(reports.get(1).counts()).containsEntry("active", 1);
    }

    @Test
    void j2_writesNothing_dbAndShopify() {
        T a = tenant("j2");
        UUID v = variant(a, "J2-1", "item-j2-1"); pieces(a, v, a.main(), 2);
        shopify.put("item-j2-1", new Object[]{"inactive", 1, 1});
        String before = snapshot();

        audit.run(List.of(a.tenant()));

        assertThat(snapshot()).as("every table's rows unchanged").isEqualTo(before);
        verify(shopifyGateway).auditMainLevels(eq(a.shop()), eq("tok"), eq(a.traced()), anyList());
        verifyNoMoreInteractions(shopifyGateway);
    }

    @Test
    void j3_outputIsCountsSkusAndIdsOnly() {
        T a = tenant("j3");
        UUID v = variant(a, "J3 SKU/1", "item-j3-1"); pieces(a, v, a.main(), 1);
        shopify.put("item-j3-1", new Object[]{"inactive", 2, 2});
        UUID n = variant(a, null, null); pieces(a, n, a.main(), 1);

        List<String> lines = new ArrayList<>();
        for (MainLevelAuditJob.Report r : audit.run(List.of(a.tenant()))) lines.addAll(MainLevelAuditJob.lines(r));

        Pattern summary = Pattern.compile("MAIN_LEVEL_AUDIT tenant=[0-9a-f-]{36} shop=[a-z0-9-]+ checked=\\d+" +
            "( (active|inactive|missing|unknown|item_gone|no_item_id|read_failed)=\\d+){7} hidden_available=\\d+ hidden_on_hand=\\d+");
        Pattern row = Pattern.compile("MAIN_LEVEL_AUDIT_ROW tenant=[0-9a-f-]{36} class=(inactive|missing|unknown|item_gone|no_item_id|read_failed)" +
            " sku=\\S+ variant=[0-9a-f-]{36} traced_available_main=\\d+ traced_available_other=\\d+ shopify_active=false" +
            " hidden_available=(\\d+|-) hidden_on_hand=(\\d+|-)");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).matches(summary);
        assertThat(lines.subList(1, 3)).allSatisfy(l -> assertThat(l).matches(row));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("sku=J3_SKU/1").contains("hidden_available=2"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("class=no_item_id sku=-"));
    }

    @Test
    void j4_failedBatchCounted_skippedTenants_badListRunsNothing() {
        T a = tenant("j4");
        for (int i = 0; i < 12; i++) pieces(a, variant(a, "J4-" + i, "item-j4-" + i), a.main(), 1);
        shopify.put("item-j4-0", new Object[]{"THROW", null, null});   // the first batch (10) fails
        T noStore = tenant("j4n");
        jdbc.update("UPDATE stores SET status = 'disconnected' WHERE id = ?", noStore.store());
        T noMain = tenant("j4m");
        jdbc.update("UPDATE locations SET shopify_sync_status = 'unsynced', shopify_location_id = NULL WHERE id = ?", noMain.main());

        List<MainLevelAuditJob.Report> r = audit.run(List.of(a.tenant(), noStore.tenant(), noMain.tenant()));

        assertThat(r.get(0).counts()).containsEntry("read_failed", 10).containsEntry("active", 2);
        assertThat(batches).hasSize(2);   // not retried
        assertThat(r.get(1).skipped()).isEqualTo("no_connected_store");
        assertThat(r.get(2).skipped()).isEqualTo("main_not_linked");
        verify(shopifyGateway, never()).auditMainLevels(eq(noStore.shop()), any(), any(), any());
        verify(shopifyGateway, never()).auditMainLevels(eq(noMain.shop()), any(), any(), any());
        assertThat(MainLevelAuditJob.parse(a.tenant() + ",not-a-tenant")).isNull();
        assertThat(MainLevelAuditJob.parse(" " + a.tenant() + " ,," + a.tenant())).containsExactly(a.tenant());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), main = UUID.randomUUID(), other = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", main, tenant, traced);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Showroom', false)", other, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        return new T(tenant, store, shop, main, traced, other, product);
    }

    private UUID variant(T t, String sku, String item) {
        return variant(t, sku, item, "gid://shopify/ProductVariant/" + t.shop() + "-" + seq.incrementAndGet());
    }

    private UUID variant(T t, String sku, String item, String externalId) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", v, t.tenant(), t.product(), externalId, sku, item);
        return v;
    }

    private void pieces(T t, UUID variant, UUID location, int n) {
        for (int i = 0; i < n; i++) piece(t, variant, location, "available");
    }

    private void piece(T t, UUID variant, UUID location, String status) {
        int k = seq.incrementAndGet();
        String id = "01MA" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?)", id, t.tenant(), variant, status, "MA-" + id, String.format("A%07d", k), location);
    }

    /** Every public table's row count and content hash (JobRunr's own tables excepted) — any write changes it. */
    private String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' " +
                "AND tablename NOT LIKE 'jobrunr%' AND tablename NOT LIKE 'flyway%' ORDER BY tablename", String.class)) {
            sb.append(table).append('=').append(jdbc.queryForObject(
                "SELECT count(*) || ':' || COALESCE(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM \"" + table + "\" t",
                String.class)).append('\n');
        }
        return sb.toString();
    }
}
