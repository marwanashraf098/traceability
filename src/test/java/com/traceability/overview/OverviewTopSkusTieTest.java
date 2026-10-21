package com.traceability.overview;

import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /overview/top-skus with ties at the top-5 boundary (2026-10-21). The query ordered by units only, so tied
 * SKUs came back in whatever order Postgres produced — GenericAnalyticsIsolationTest's golden flaked on a 3-way tie.
 * Ties now break on sku ASC, then variant id (null skus).
 *
 * tt1 — one leader + eleven SKUs tied at the boundary, inserted in a shuffled order: the leader, then the four
 *       lowest skus, the same list on every call.
 * tt2 — six null-sku variants tied with one sku: the sku first, then the four lowest variant ids (cut at 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverviewTopSkusTieTest {

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

    @Autowired JdbcTemplate jdbc;

    OverviewService overview;
    UUID tenant, store, product;

    @BeforeAll
    void setupFixture() {
        tenant = UUID.randomUUID();
        store = UUID.randomUUID();
        product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TopSkuTie')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'tt.myshopify.com', 'connected', 'completed', 'enc', now() + interval '876000 hours')",
            store, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/TT', 'Tie')", product, tenant, store);
        overview = new OverviewService(jdbc, Clock.system(ZoneId.of("Africa/Cairo")));
    }

    @AfterEach
    void cleanState() {
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenant);
    }

    @Test
    void tt1_tieAtTheBoundary_sameOrderEveryCall_lowestSkusFirst() {
        List<String> tied = new ArrayList<>();
        for (int i = 1; i <= 11; i++) tied.add(String.format("TT-%02d", i));
        List<String> insertOrder = new ArrayList<>(tied);
        insertOrder.add("TT-LEAD");
        Collections.shuffle(insertOrder, new Random(42));
        Collections.reverse(insertOrder);
        for (String sku : insertOrder) {
            UUID variant = insertVariant(UUID.randomUUID(), sku);
            sell(variant, sku.equals("TT-LEAD") ? 9 : 4);
        }

        List<String> first = skus();
        assertThat(first)
            .as("the leader, then the four lowest skus of the eleven tied at 4 units")
            .containsExactly("TT-LEAD", "TT-01", "TT-02", "TT-03", "TT-04");
        for (int i = 0; i < 20; i++) {
            assertThat(skus()).as("call %d", i).isEqualTo(first);
        }
    }

    @Test
    void tt2_nullSkusTied_lowerVariantIdFirst_afterNonNullSkus() {
        List<UUID> nullSkuIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) nullSkuIds.add(insertVariant(UUID.randomUUID(), null));
        for (UUID id : nullSkuIds) sell(id, 3);
        sell(insertVariant(UUID.randomUUID(), "TT-Z"), 3);

        TenantContext.set(tenant);
        List<OverviewService.TopSku> rows;
        try { rows = overview.topSkus(); } finally { TenantContext.clear(); }

        // Postgres compares uuids bytewise — the same order as their lowercase text.
        List<String> expected = new ArrayList<>(List.of("Tie — V-TT-Z"));
        nullSkuIds.stream().map(UUID::toString).sorted().limit(4).forEach(id -> expected.add("Tie — V-" + id));
        assertThat(rows).extracting(OverviewService.TopSku::sku).containsExactly("TT-Z", null, null, null, null);
        assertThat(rows).extracting(OverviewService.TopSku::title)
            .as("null skus after every sku with the same units, lower variant id first, cut at 5")
            .containsExactly(expected.toArray(new String[0]));
    }

    private List<String> skus() {
        TenantContext.set(tenant);
        try {
            return overview.topSkus().stream().map(OverviewService.TopSku::sku).toList();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID insertVariant(UUID id, String sku) {
        String title = "V-" + (sku != null ? sku : id.toString());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            id, tenant, product, "gid://shopify/ProductVariant/" + id, title, sku);
        return id;
    }

    private void sell(UUID variant, int units) {
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, ?, 'new'::order_status, 'cod', now())",
            order, tenant, store, "EXT-" + order, "#" + order.toString().substring(0, 8));
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (gen_random_uuid(), ?, ?, ?, ?)",
            tenant, order, variant, units);
    }
}
