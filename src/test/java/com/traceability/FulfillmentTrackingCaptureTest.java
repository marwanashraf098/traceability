package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * V129 order_fulfillment_tracking — store-only capture of Shopify fulfillment tracking
 * numbers from orders/updated REST payloads.
 *
 *   ft1 realistic BROEK fulfillment (service "manual", tracking_numbers[], Bosta URL) → one
 *       'bosta' row; no Bosta call, no shipment, no status change
 *   ft2 bare 9- and 10-digit numbers; a hub-prefixed number is stored bare
 *   ft3 Wijha + Bosta on one order → 'other_known' + 'bosta'; Jumi's "Other" → 'unknown'
 *   ft4 a fulfillment Shopify cancels → status 'cancelled', row kept (also when it later
 *       disappears from the payload)
 *   ft5 a duplicate orders/updated event → one row
 *   ft6 a GraphQL upsert of the same order leaves the rows intact
 *   ft7 cross-tenant isolation as app_user, same-tenant positive control; RLS policy present
 *   ft8 only Bosta numbers are normalized: Wijha "WJ-12345" is stored unchanged (whitespace
 *       removed only), a hub-prefixed Bosta number is stored bare
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FulfillmentTrackingCaptureTest {

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

    @Autowired JdbcTemplate               jdbc;
    @Autowired ObjectMapper               mapper;
    @Autowired ShopifyWebhookProcessorJob processorJob;
    @Autowired ShopifySyncService         syncService;
    @MockBean  BostaGateway               bostaGateway;
    @MockBean  JobScheduler               jobScheduler;

    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    @BeforeAll
    void appUser() {
        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc = new JdbcTemplate(ds);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    record Shop(UUID tenantId, UUID storeId, String domain) {}

    @Test
    void ft1_realisticBostaFulfillment_storesOneBostaRow_nothingElse() {
        Shop s = shop("ft1");
        String gid = "gid://shopify/Order/18912387137815";
        ArrayNode f = mapper.createArrayNode();
        f.add(fulfillment(7260882305303L, "success", "Bosta", "9432163061",
            "https://bosta.co/tracking-shipments?shipment-number=9432163061"));
        orderUpdated(s, gid, "BRK-44841-EG", f);

        List<Map<String, Object>> rows = rows(s, gid);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("tracking_number")).isEqualTo("9432163061");
            assertThat(r.get("carrier_raw")).isEqualTo("Bosta");
            assertThat(r.get("carrier_class")).isEqualTo("bosta");
            assertThat(r.get("tracking_url")).isEqualTo("https://bosta.co/tracking-shipments?shipment-number=9432163061");
            assertThat(r.get("shopify_fulfillment_id")).isEqualTo("7260882305303");
            assertThat(r.get("fulfillment_status")).isEqualTo("success");
        });
        verifyNoInteractions(bostaGateway);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE tenant_id = ?", Integer.class, s.tenantId()))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE external_id = ?", String.class, gid))
            .isEqualTo("new");
    }

    @Test
    void ft2_bareNineAndTenDigit_andHubPrefixStripped() {
        Shop s = shop("ft2");
        String gid = "gid://shopify/Order/18914721661207";
        ArrayNode f = mapper.createArrayNode();
        f.add(fulfillment(1L, "success", "Bosta", "563601351", null));
        ObjectNode multi = fulfillment(2L, "success", "Bosta", null, null);
        multi.putArray("tracking_numbers").add("5360015612").add("D-07-2944282510");
        f.add(multi);
        orderUpdated(s, gid, "BRK-44900-EG", f);

        assertThat(rows(s, gid)).extracting(r -> r.get("tracking_number"))
            .containsExactlyInAnyOrder("563601351", "5360015612", "2944282510");
    }

    @Test
    void ft3_mixedWijhaAndBosta_andJumiOther() {
        Shop s = shop("ft3");
        String gid = "gid://shopify/Order/70370";
        ArrayNode f = mapper.createArrayNode();
        f.add(fulfillment(10L, "success", "Wijha", "WJ88812345", null));
        f.add(fulfillment(11L, "success", "Bosta", "8948149267", null));
        f.add(fulfillment(12L, "success", "Other", "8958142126", null));   // Jumi's Bosta says "Other"
        f.add(fulfillment(13L, "success", null, "1400430616", null));
        orderUpdated(s, gid, "70370", f);

        Map<Object, Object> byTracking = new java.util.HashMap<>();
        for (Map<String, Object> r : rows(s, gid)) byTracking.put(r.get("tracking_number"), r.get("carrier_class"));
        assertThat(byTracking).containsEntry("WJ88812345", "other_known")
            .containsEntry("8948149267", "bosta")
            .containsEntry("8958142126", "unknown")
            .containsEntry("1400430616", "unknown");
    }

    @Test
    void ft4_cancelledFulfillment_updatesStatus_rowNeverDeleted() {
        Shop s = shop("ft4");
        String gid = "gid://shopify/Order/18900000000004";
        ArrayNode f1 = mapper.createArrayNode();
        f1.add(fulfillment(40L, "success", "Bosta", "5498300341", null));
        orderUpdated(s, gid, "BRK-44876-EG", f1);

        ArrayNode f2 = mapper.createArrayNode();
        f2.add(fulfillment(40L, "cancelled", "Bosta", "5498300341", null));
        orderUpdated(s, gid, "BRK-44876-EG", f2);
        assertThat(rows(s, gid)).singleElement()
            .satisfies(r -> assertThat(r.get("fulfillment_status")).isEqualTo("cancelled"));

        orderUpdated(s, gid, "BRK-44876-EG", mapper.createArrayNode());   // fulfillment gone
        assertThat(rows(s, gid)).singleElement()
            .satisfies(r -> assertThat(r.get("fulfillment_status")).isEqualTo("cancelled"));
    }

    @Test
    void ft5_duplicateEvent_producesOneRow() {
        Shop s = shop("ft5");
        String gid = "gid://shopify/Order/18900000000005";
        ArrayNode f = mapper.createArrayNode();
        f.add(fulfillment(50L, "success", "Bosta", "5818827013", null));
        orderUpdated(s, gid, "BRK-44878-EG", f);
        Object firstSeen = rows(s, gid).get(0).get("first_seen_at");
        orderUpdated(s, gid, "BRK-44878-EG", f);

        assertThat(rows(s, gid)).singleElement()
            .satisfies(r -> assertThat(r.get("first_seen_at")).isEqualTo(firstSeen));
    }

    @Test
    void ft6_graphqlUpsert_leavesRowsIntact() {
        Shop s = shop("ft6");
        String gid = "gid://shopify/Order/18900000000006";
        ArrayNode f = mapper.createArrayNode();
        f.add(fulfillment(60L, "success", "Bosta", "9520154680", null));
        f.add(fulfillment(61L, "success", "Wijha", "WJ1002219850", null));
        orderUpdated(s, gid, "BRK-44879-EG", f);
        List<Map<String, Object>> before = rows(s, gid);
        assertThat(before).hasSize(2);

        // The GraphQL import / reconcile shape carries no fulfillments.
        ShopifyGateway.Order graphql = new ShopifyGateway.Order(gid, "BRK-44879-EG", null, null, null,
            "Pending", List.of(), new BigDecimal("450.00"), List.of(),
            Instant.parse("2026-09-30T17:44:50Z"), mapper.createObjectNode().put("id", gid));
        TenantContext.runAs(s.tenantId(), () -> syncService.ingestMissingOrder(s.storeId(), s.tenantId(), graphql));

        assertThat(rows(s, gid)).isEqualTo(before);
    }

    @Test
    void ft7_crossTenantIsolation_asAppUser_withSameTenantControl() {
        Shop a = shop("ft7a");
        Shop b = shop("ft7b");
        ArrayNode fa = mapper.createArrayNode();
        fa.add(fulfillment(70L, "success", "Bosta", "4069420256", null));
        orderUpdated(a, "gid://shopify/Order/18900000000071", "BRK-44881-EG", fa);
        ArrayNode fb = mapper.createArrayNode();
        fb.add(fulfillment(71L, "success", "Bosta", "8388220697", null));
        orderUpdated(b, "gid://shopify/Order/18900000000072", "BRK-44883-EG", fb);

        List<String> seenByA = TenantContext.runAs(a.tenantId(), () -> appUserTx.execute(st ->
            appUserJdbc.queryForList("SELECT tracking_number FROM order_fulfillment_tracking", String.class)));
        assertThat(seenByA).containsExactly("4069420256");   // positive control + nothing of B's

        List<String> noGuc = appUserTx.execute(st ->
            appUserJdbc.queryForList("SELECT tracking_number FROM order_fulfillment_tracking", String.class));
        assertThat(noGuc).as("no tenant context → zero rows").isEmpty();

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_policies WHERE tablename = 'order_fulfillment_tracking' " +
            "AND policyname = 'tenant_isolation'", Integer.class)).isEqualTo(1);
    }

    @Test
    void ft8_onlyBostaNumbersAreNormalized_wijhaKeptAsSent() {
        Shop s = shop("ft8");
        String gid = "gid://shopify/Order/18900000000008";
        ArrayNode f = mapper.createArrayNode();
        f.add(fulfillment(80L, "success", "Wijha", "WJ-12345 ", null));
        f.add(fulfillment(81L, "success", "Bosta", "D-07-2944282510", null));
        orderUpdated(s, gid, "70371", f);

        Map<Object, Object> byTracking = new java.util.HashMap<>();
        for (Map<String, Object> r : rows(s, gid)) byTracking.put(r.get("tracking_number"), r.get("carrier_class"));
        assertThat(byTracking).containsOnly(
            Map.entry("WJ-12345", "other_known"),
            Map.entry("2944282510", "bosta"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Shop shop(String tag) {
        UUID tenantId = UUID.randomUUID();
        UUID storeId  = UUID.randomUUID();
        String domain = tag + "-" + tenantId.toString().substring(0, 8) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "FT-" + tag);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', ?, 'connected')", storeId, tenantId, domain);
        return new Shop(tenantId, storeId, domain);
    }

    private ObjectNode fulfillment(long id, String status, String company, String tracking, String url) {
        ObjectNode f = mapper.createObjectNode();
        f.put("id", id);
        f.put("name", "#F" + id);
        f.put("status", status);
        f.put("service", "manual");
        f.put("created_at", "2026-09-30T17:57:12+03:00");
        f.put("location_id", 83647889687L);
        if (company != null) f.put("tracking_company", company); else f.putNull("tracking_company");
        if (tracking != null) {
            f.put("tracking_number", tracking);
            f.putArray("tracking_numbers").add(tracking);
        } else {
            f.putNull("tracking_number");
        }
        if (url != null) {
            f.put("tracking_url", url);
            f.putArray("tracking_urls").add(url);
        }
        f.put("shipment_status", "in_transit");
        f.put("admin_graphql_api_id", "gid://shopify/Fulfillment/" + id);
        f.putObject("origin_address");
        return f;
    }

    private void orderUpdated(Shop s, String gid, String name, ArrayNode fulfillments) {
        ObjectNode p = mapper.createObjectNode();
        p.put("id", Long.parseLong(gid.substring(gid.lastIndexOf('/') + 1)));
        p.put("admin_graphql_api_id", gid);
        p.put("name", name);
        p.put("created_at", "2026-09-30T17:44:50+03:00");
        p.put("updated_at", "2026-10-01T18:37:09+03:00");
        p.put("financial_status", "pending");
        p.put("current_total_price", "450.00");
        p.putArray("payment_gateway_names").add("Cash on Delivery (COD)");
        p.putArray("line_items");
        p.set("fulfillments", fulfillments);
        UUID eventId = jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, 'orders/updated', ?, ?, ?::jsonb) RETURNING id",
            UUID.class, s.tenantId(), s.domain(), UUID.randomUUID().toString(), p.toString());
        processorJob.process(eventId, s.tenantId());
        assertThat(jdbc.queryForObject("SELECT process_error FROM shopify_webhook_events WHERE id = ?",
            String.class, eventId)).isNull();
    }

    private List<Map<String, Object>> rows(Shop s, String gid) {
        return jdbc.queryForList(
            "SELECT t.tracking_number, t.carrier_raw, t.tracking_url, t.carrier_class, " +
            "       t.shopify_fulfillment_id, t.fulfillment_status, t.first_seen_at " +
            "FROM order_fulfillment_tracking t JOIN orders o ON o.id = t.order_id " +
            "WHERE t.tenant_id = ? AND o.external_id = ? ORDER BY t.tracking_number",
            s.tenantId(), gid);
    }
}
