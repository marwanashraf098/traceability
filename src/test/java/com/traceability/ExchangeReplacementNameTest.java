package com.traceability;

import com.traceability.portal.ReturnRequestService;
import com.traceability.returncases.ReturnCaseService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
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

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-13 — an exchange's replacement from ANOTHER product (F3 / Lookup) is named in full: the request detail
 * carries its product title (+ image / SKU when Traced has them) and the Returns list row reads
 * "Linen Shirt White / M → Flipped Pants Black / L". A same-product swap is unchanged: no new keys, "→ White / L".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeReplacementNameTest {

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

    @Autowired JdbcTemplate         jdbc;
    @Autowired ReturnRequestService requests;
    @Autowired ReturnCaseService    cases;

    @MockBean JobScheduler jobScheduler;

    UUID tenantId, storeId, shirt, whiteM, whiteL, pants, pantsBlackL, tote, toteOnly;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Replacement Names')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'rn.myshopify.com', 'disconnected')",
            storeId, tenantId);
        shirt = product("P-LIN", "Linen Shirt", "https://cdn.shopify.com/linen.jpg");
        whiteM = variant(shirt, "White / M", "LIN-WM");
        whiteL = variant(shirt, "White / L", "LIN-WL");
        pants = product("P-PANTS", "Flipped Pants", "https://cdn.shopify.com/pants.jpg");
        pantsBlackL = variant(pants, "Black / L", "PANTS-BL");
        tote = product("P-TOTE", "Canvas Tote", null);
        toteOnly = variant(tote, "Default Title", null);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    @Test
    void n1_differentProduct_detailCarriesProductImageSku_listNamesItInFull() {
        UUID r = exchangeRequest("#5001", whiteM, pantsBlackL);
        Map<String, Object> item = detailItem(r);
        assertThat(item.get("replacementVariantTitle")).isEqualTo("Black / L");
        assertThat(item.get("replacementProductTitle")).isEqualTo("Flipped Pants");
        assertThat(item.get("replacementImageUrl")).isEqualTo("https://cdn.shopify.com/pants.jpg");
        assertThat(item.get("replacementSku")).isEqualTo("PANTS-BL");
        assertThat(item.get("productTitle")).isEqualTo("Linen Shirt");

        assertThat(listSummary("#5001")).isEqualTo("Linen Shirt White / M → Flipped Pants Black / L");
    }

    @Test
    void n2_sameProduct_detailHasNoNewKeys_listKeepsVariantOnly() {
        UUID r = exchangeRequest("#5002", whiteM, whiteL);
        Map<String, Object> item = detailItem(r);
        assertThat(item.get("replacementVariantTitle")).isEqualTo("White / L");
        assertThat(item).doesNotContainKeys("replacementProductTitle", "replacementImageUrl", "replacementSku");

        assertThat(listSummary("#5002")).isEqualTo("Linen Shirt White / M → White / L");
    }

    @Test
    void n3_differentProduct_noImageNoSku_defaultTitle_productOnly() {
        UUID r = exchangeRequest("#5003", whiteM, toteOnly);
        Map<String, Object> item = detailItem(r);
        assertThat(item.get("replacementProductTitle")).isEqualTo("Canvas Tote");
        assertThat(item).doesNotContainKeys("replacementImageUrl", "replacementSku");

        assertThat(listSummary("#5003")).isEqualTo("Linen Shirt White / M → Canvas Tote");
    }

    @Test
    void n4_dashboardExchange_otherProduct_listNamesItInFull_sameProductUnchanged() {
        UUID o1 = order("#5004", whiteM);
        UUID o2 = order("#5005", whiteM);
        dashboardExchange("AWB5004", o1, whiteM, pantsBlackL);
        dashboardExchange("AWB5005", o2, whiteM, whiteL);

        assertThat(listSummary("#5004")).isEqualTo("Linen Shirt White / M → Flipped Pants Black / L");
        assertThat(listSummaryAr("#5004")).isEqualTo("Linen Shirt White / M ← Flipped Pants Black / L");
        assertThat(listSummary("#5005")).isEqualTo("Linen Shirt White / M → White / L");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> detailItem(UUID request) {
        TenantContext.set(tenantId);
        try {
            return ((List<Map<String, Object>>) requests.detail(request).get("items")).get(0);
        } finally {
            TenantContext.clear();
        }
    }

    private Map<String, Object> listRow(String orderNumber) {
        TenantContext.set(tenantId);
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) cases.list(
                new ReturnCaseService.Filter(null, null, null, null), null, 100).get("items");
            return items.stream().filter(m -> orderNumber.equals(m.get("orderNumber"))).findFirst()
                .orElseThrow(() -> new AssertionError("no case for " + orderNumber + " in " + items));
        } finally {
            TenantContext.clear();
        }
    }

    private String listSummary(String orderNumber) { return (String) listRow(orderNumber).get("itemsSummary"); }

    private String listSummaryAr(String orderNumber) { return (String) listRow(orderNumber).get("itemsSummaryAr"); }

    private UUID product(String externalId, String title, String imageUrl) {
        UUID p = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, image_url) VALUES (?, ?, ?, ?, ?, 'active', ?)",
            p, tenantId, storeId, externalId, title, imageUrl);
        return p;
    }

    private UUID variant(UUID product, String title, String sku) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenantId, product, "V-" + v, title, sku);
        return v;
    }

    private UUID order(String number, UUID variant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name) " +
            "VALUES (?, ?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona Customer')",
            id, tenantId, storeId, "gid://shopify/Order/" + UUID.randomUUID(), number);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            UUID.randomUUID(), tenantId, id, variant);
        return id;
    }

    /** A portal exchange request ('requested') for one untracked unit of the order's line. */
    private UUID exchangeRequest(String number, UUID from, UUID to) {
        UUID order = order(number, from);
        UUID line = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, order);
        UUID id = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference) " +
            "VALUES (?, ?, 'exchange', 'requested', ?) RETURNING id", UUID.class, tenantId, order, reference());
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code, " +
            "    replacement_variant_id) VALUES (?, ?, ?, 1, ?, 'wrong_size', ?)", tenantId, id, line, from, to);
        return id;
    }

    /** B — a dashboard Bosta exchange (no request) with known inbound / outbound variants. */
    private void dashboardExchange(String tracking, UUID matchedOrder, UUID inbound, UUID outbound) {
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, inbound_description, outbound_description, raw, " +
            "    matched_order_id, inbound_variant_id, outbound_variant_id) " +
            "VALUES (?, ?, 'matched', 'in', 'out', '{}'::jsonb, ?, ?, ?)", tenantId, tracking, matchedOrder, inbound, outbound);
    }

    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }
}
