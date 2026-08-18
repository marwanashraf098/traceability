package com.traceability;

import com.traceability.inventory.ExchangeService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-EXCHANGE Phase 2 — the manual mapping step (ExchangeService.list()/map()).
 *
 * Matrix:
 *   m1 — list() returns descriptions/itemsCounts/customer parsed from raw, scoped to
 *        the requested status
 *   m2 — map() creates the Model-A order + order_item (qty from raw itemsCount),
 *        populates PII, records the mapping, flips status → mapped
 *   m3 — store_id is derived from the OUTBOUND variant's product, not "the tenant's
 *        only store" — proven with two stores, variant under the non-first one
 *   m4 — map() creates NO shipments row and does not touch is_self_pickup (Fulfill
 *        queue inertness guarantee, §0b)
 *   m5 — re-posting an already-mapped exchange 409s (claim-first idempotency)
 *   m6 — unknown variant id → 400, before any order/order_item write
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeMappingTest {

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

    @Autowired ExchangeService excSvc;
    @Autowired JdbcTemplate    jdbc;
    @MockBean  JobScheduler    jobScheduler;

    UUID tenantId;
    UUID storeAId, storeBId;
    UUID outboundVariantId, inboundVariantId;

    @BeforeAll
    void setupFixture() {
        tenantId = UUID.randomUUID();
        storeAId = UUID.randomUUID();
        storeBId = UUID.randomUUID();
        UUID productAId = UUID.randomUUID();
        UUID productBId = UUID.randomUUID();
        inboundVariantId  = UUID.randomUUID();
        outboundVariantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ExchangeMappingTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'exmap-a.myshopify.com', 'connected')", storeAId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'exmap-b.myshopify.com', 'connected')", storeBId, tenantId);
        // Two products under two DIFFERENT stores — outbound variant lives under store B,
        // proving m3's derivation isn't just "grab the tenant's first store".
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-EXMAP-A', 'Inbound Product')", productAId, tenantId, storeAId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-EXMAP-B', 'Outbound Product')", productBId, tenantId, storeBId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-EXMAP-IN', 'Red', 'RED-01')", inboundVariantId, tenantId, productAId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-EXMAP-OUT', 'Yellow', 'YEL-01')", outboundVariantId, tenantId, productBId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() { TenantContext.clear(); }

    @AfterEach
    void cleanup() {
        // exchanges.outbound_order_id FKs to orders — delete exchanges first.
        jdbc.update("DELETE FROM exchanges   WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders      WHERE tenant_id = ?", tenantId);
    }

    private UUID seedExchange(String tracking, String outboundDesc, int outboundCount,
                               String inboundDesc, String inboundDescAr, int inboundCount) {
        String raw = String.format("""
            {"type":{"code":30,"value":"Exchange"},
             "specs":{"packageDetails":{"description":"%s","itemsCount":%d}},
             "returnSpecs":{"packageDetails":{"description":"%s","descriptionAr":"%s","itemsCount":%d}},
             "cod":"0","goodsInfo":{"amount":"600"},
             "receiver":{"fullName":"Maya Mostafa","phone":"+201000301512"},
             "dropOffAddress":{"firstLine":"12 Palm St","city":{"name":"October"}}}
            """, outboundDesc, outboundCount, inboundDesc, inboundDescAr, inboundCount);
        return jdbc.queryForObject(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, " +
            "  inbound_description, inbound_description_ar, cod, goods_value, raw) " +
            "VALUES (?, ?, 'needs_mapping', ?, ?, ?, 0, 600, ?::jsonb) RETURNING id",
            UUID.class, tenantId, tracking, outboundDesc, inboundDesc, inboundDescAr, raw);
    }

    // ── m1: list() parses descriptions/itemsCounts/customer from raw ────────────

    @Test
    void m1_list_returnsParsedFieldsScopedByStatus() {
        UUID id = seedExchange("EXMAP-M1", "Yellow hat", 1, "Red hat", "قبعة حمراء", 1);

        List<Map<String, Object>> rows = excSvc.list("needs_mapping");
        Map<String, Object> row = rows.stream()
            .filter(r -> id.toString().equals(r.get("id").toString()))
            .findFirst().orElseThrow();

        assertThat(row.get("tracking_number")).isEqualTo("EXMAP-M1");
        assertThat(row.get("outbound_description")).isEqualTo("Yellow hat");
        assertThat(row.get("inbound_description")).isEqualTo("Red hat");
        assertThat(row.get("inbound_description_ar")).isEqualTo("قبعة حمراء");
        assertThat(((Number) row.get("outbound_items_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("inbound_items_count")).intValue()).isEqualTo(1);
        assertThat(row.get("customer_name")).isEqualTo("Maya Mostafa");
        assertThat(row.get("customer_phone")).isEqualTo("+201000301512");

        assertThat(excSvc.list("mapped")).noneMatch(r -> id.toString().equals(r.get("id").toString()));
    }

    // ── m2: map() creates the order + order_item, populates PII, flips status ───

    @Test
    void m2_map_createsOrderAndOrderItem_populatesPii_flipsStatus() {
        UUID id = seedExchange("EXMAP-M2", "Yellow hat", 1, "Red hat", "قبعة حمراء", 1);

        Map<String, Object> result = excSvc.map(id, outboundVariantId, inboundVariantId);
        UUID orderId = UUID.fromString((String) result.get("orderId"));

        Map<String, Object> order = jdbc.queryForMap(
            "SELECT * FROM orders WHERE id = ? AND tenant_id = ?", orderId, tenantId);
        assertThat(order.get("external_id")).isEqualTo("internal:exchange:EXMAP-M2");
        assertThat(order.get("number")).isEqualTo("EXC-EXMAP-M2");
        assertThat(order.get("customer_name")).isEqualTo("Maya Mostafa");
        assertThat(order.get("customer_phone")).isNotNull();
        assertThat(order.get("payment_method")).isNull();
        assertThat(order.get("status")).isEqualTo("new");

        Map<String, Object> item = jdbc.queryForMap(
            "SELECT * FROM order_items WHERE order_id = ?", orderId);
        assertThat(item.get("variant_id")).isEqualTo(outboundVariantId);
        assertThat(((Number) item.get("quantity")).intValue()).isEqualTo(1);

        Map<String, Object> exchange = jdbc.queryForMap(
            "SELECT * FROM exchanges WHERE id = ?", id);
        assertThat(exchange.get("status")).isEqualTo("mapped");
        assertThat(exchange.get("outbound_order_id")).isEqualTo(orderId);
        assertThat(exchange.get("outbound_variant_id")).isEqualTo(outboundVariantId);
        assertThat(exchange.get("inbound_variant_id")).isEqualTo(inboundVariantId);
    }

    // ── m3: store_id derived from the outbound variant's product ────────────────

    @Test
    void m3_map_derivesStoreIdFromOutboundVariantProduct_notTenantsFirstStore() {
        UUID id = seedExchange("EXMAP-M3", "Yellow hat", 1, "Red hat", "قبعة حمراء", 1);

        Map<String, Object> result = excSvc.map(id, outboundVariantId, inboundVariantId);
        UUID orderId = UUID.fromString((String) result.get("orderId"));

        UUID actualStoreId = jdbc.queryForObject(
            "SELECT store_id FROM orders WHERE id = ?", UUID.class, orderId);
        assertThat(actualStoreId)
            .as("store_id must come from the outbound variant's product (store B), not store A")
            .isEqualTo(storeBId)
            .isNotEqualTo(storeAId);
    }

    // ── m4: no shipments row, is_self_pickup untouched (Fulfill inertness) ──────

    @Test
    void m4_map_createsNoShipmentsRow_leavesIsSelfPickupDefault() {
        UUID id = seedExchange("EXMAP-M4", "Yellow hat", 1, "Red hat", "قبعة حمراء", 1);
        Map<String, Object> result = excSvc.map(id, outboundVariantId, inboundVariantId);
        UUID orderId = UUID.fromString((String) result.get("orderId"));

        Integer shipmentCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, orderId);
        assertThat(shipmentCount).as("Phase 2 must not create a shipments row").isZero();

        Boolean isSelfPickup = jdbc.queryForObject(
            "SELECT is_self_pickup FROM orders WHERE id = ?", Boolean.class, orderId);
        assertThat(isSelfPickup).isFalse();

        // Directly re-verify the Fulfill pickability predicate excludes this order.
        Integer pickable = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders o " +
            "LEFT JOIN LATERAL (" +
            "    SELECT internal_state FROM shipments" +
            "    WHERE order_id = o.id AND tenant_id = o.tenant_id AND shipment_leg = 'forward'" +
            "    ORDER BY created_at DESC, id DESC LIMIT 1" +
            ") latest_shipment ON true " +
            "WHERE o.id = ? AND o.status IN ('new','ready_to_pick','self_pickup_pending') " +
            "  AND o.on_hold = false " +
            "  AND (o.is_self_pickup = true OR latest_shipment.internal_state = 'created')",
            Integer.class, orderId);
        assertThat(pickable).as("internal exchange order must be inert to the Fulfill queue").isZero();
    }

    // ── m5: re-posting an already-mapped exchange 409s ───────────────────────────

    @Test
    void m5_map_alreadyMapped_conflicts() {
        UUID id = seedExchange("EXMAP-M5", "Yellow hat", 1, "Red hat", "قبعة حمراء", 1);
        excSvc.map(id, outboundVariantId, inboundVariantId);

        // Message text (not just the 409 status) proves the CLAIM guard fired, not the
        // external_id-UNIQUE backstop (which throws a differently-worded 409 and would
        // only fire if the claim guard were missing — see the two-guard design note in
        // ExchangeService.map()'s javadoc).
        assertThatThrownBy(() -> excSvc.map(id, outboundVariantId, inboundVariantId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not awaiting mapping");

        // Exactly one order — the second call never reached the orders INSERT.
        Integer orderCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE external_id = ?",
            Integer.class, "internal:exchange:EXMAP-M5");
        assertThat(orderCount).isEqualTo(1);
    }

    // ── m6: unknown variant id → 400, no writes ──────────────────────────────────

    @Test
    void m6_map_unknownOutboundVariant_badRequest_noWrites() {
        UUID id = seedExchange("EXMAP-M6", "Yellow hat", 1, "Red hat", "قبعة حمراء", 1);

        assertThatThrownBy(() -> excSvc.map(id, UUID.randomUUID(), inboundVariantId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");

        // Rolled back entirely — claim, too, since it's all one @Transactional method.
        String status = jdbc.queryForObject(
            "SELECT status FROM exchanges WHERE id = ?", String.class, id);
        assertThat(status).as("failed mapping must not strand the exchange in 'mapped'").isEqualTo("needs_mapping");
    }
}
