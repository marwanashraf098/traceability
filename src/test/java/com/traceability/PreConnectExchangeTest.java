package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExchangeService;
import com.traceability.portal.PreConnectParentImport;
import com.traceability.security.EncryptionService;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-16 — a Bosta exchange (30) Bosta created AFTER the connect for an order placed BEFORE it (prod: Storm,
 * cutoff 2026-11-13 17:15Z, lowest order #3018, 2472106867 "#2739:#2739-R1"). Bare-numeric tracking numbers, Bosta's
 * v0 JS-date createdAt, Shopify's GraphQL order shape.
 *
 *   x1  Storm shape → exchanges row (needs mapping), parent #2739 imported from Shopify (portal_pre_connect,
 *       internal:portal:, untracked line, outside merchant_orders / warehouse_orders), matched by 'reference';
 *       map → EXC-2472106867 order + linked 'created' forward leg
 *   x2  Shopify search fails → the exchange is still created, matched_order_id NULL (never blocks ingest)
 *   x3  a later state of the same delivery → no second parent row, no second Shopify call
 *   x4  multi-item → the EXCHANGE_MULTI_ITEM unlinked lane, unchanged; no Shopify call
 *   x5  cross-tenant: tenant B's pre-connect #2739 is never reused for tenant A (A imports its own);
 *       positive control — B's own exchange matches B's row
 *   x6  an order Shopify says was placed after the connect is never stored as a pre-connect row
 *   x7  an exact outbound description auto-maps (existing tryAutoMap) → EXC order, matched to the parent
 *   c1  CRP (type 25) of a pre-connect order → return leg linked to the imported parent; Scan returns ("A-06-…")
 *       lists the parent's line as an untracked unit row
 *   c2  CRP, Shopify fails → still ingested (unlinked, as before)
 *   d1  combined (post BRK D1–D4): pre-connect CRP → untracked units, label pre-selects, arrived, in-session undo
 *   r1–r5 PreConnectReprocessService: dry run writes nothing and reports type / createdAt / state / reference / item
 *       counts / decision; apply → the exchange lane, a second apply is a no-op; picked-up → NOT_OPEN, pre-cutoff →
 *       STILL_IGNORED (both kept ignored on apply); Little Toes excluded; cross-tenant untouched; explicit list
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreConnectExchangeTest {

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

    static final Instant STORM_CUTOFF = Instant.parse("2026-11-13T17:15:08Z");
    static final String AFTER = "Sun Nov 15 2026 22:43:11 GMT+0000 (Coordinated Universal Time)";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired BostaWebhookJob webhookJob;
    @Autowired ExchangeService exchangeService;
    @Autowired PreConnectParentImport parentImport;
    @Autowired com.traceability.inventory.ReturnSessionService returns;
    @Autowired com.traceability.integrations.bosta.PreConnectReprocessService reprocess;
    @MockBean BostaGateway bostaGateway;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokens;

    @BeforeEach
    void stubs() {
        reset(bostaGateway, shopify, tokens);
        parentImport.clearCaches();
        when(tokens.getValidToken(any())).thenReturn("shpat-test");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void x1_stormShape_parentImported_matched_mapToExcOrderWithForwardLeg() {
        Shop s = new Shop("x1");
        shopifyReturns(s, "#2739", "2026-11-01T10:00:00Z", s.jeans30);

        ObjectNode bosta = exchange("2472106867", "#2739:#2739-R1", AFTER, 1, 1);
        // A description the resolver can't pin to one variant → a person maps it (the auto-map path is x7).
        ((ObjectNode) bosta.path("specs").path("packageDetails")).put("description", "Exchange item");
        long ev = process(s.tenant, bosta);
        assertThat(note(ev)).isEqualTo("exchange: 2472106867");

        Map<String, Object> parent = jdbc.queryForMap(
            "SELECT id, number, origin, external_id, portal_delivered_at, portal_delivery FROM orders " +
            "WHERE tenant_id = ? AND number = '#2739'", s.tenant);
        assertThat(parent.get("origin")).isEqualTo("portal_pre_connect");
        assertThat((String) parent.get("external_id")).startsWith("internal:portal:");
        assertThat(parent.get("portal_delivered_at")).isNull();
        assertThat(parent.get("portal_delivery")).isNull();
        UUID parentId = (UUID) parent.get("id");
        assertThat(count("SELECT COUNT(*) FROM order_items WHERE order_id = ?", parentId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ?", parentId)).as("untracked: no allocation, no piece").isZero();
        assertThat(count("SELECT COUNT(*) FROM merchant_orders WHERE id = ?", parentId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM warehouse_orders WHERE id = ?", parentId)).isZero();

        Map<String, Object> ex = jdbc.queryForMap(
            "SELECT id, status, matched_order_id, match_method FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            s.tenant, "2472106867");
        assertThat(ex.get("matched_order_id")).isEqualTo(parentId);
        assertThat(ex.get("match_method")).isEqualTo("reference");
        assertThat(ex.get("status")).isEqualTo("needs_mapping");

        Map<String, Object> mapped = TenantContext.runAs(s.tenant,
            () -> exchangeService.map((UUID) ex.get("id"), s.jeans32, s.jeans30));
        UUID excOrder = UUID.fromString((String) mapped.get("orderId"));
        assertThat(jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, excOrder))
            .isEqualTo("EXC-2472106867");
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE order_id = ? " +
            "AND shipment_leg = 'forward' AND tracking_number = '2472106867'", String.class, excOrder)).isEqualTo("created");
        assertThat(mapped.get("status")).isEqualTo("matched");
    }

    @Test
    void x2_shopifySearchFails_exchangeStillCreated_unmatched() {
        Shop s = new Shop("x2");
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("Shopify 503"));

        long ev = process(s.tenant, exchange("5028197718", "#2922:#2922-R1", AFTER, 1, 1));
        assertThat(note(ev)).isEqualTo("exchange: 5028197718");
        assertThat(jdbc.queryForObject("SELECT matched_order_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            UUID.class, s.tenant, "5028197718")).isNull();
        assertThat(count("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND origin = 'portal_pre_connect'", s.tenant)).isZero();
    }

    @Test
    void x3_laterStateOfSameDelivery_noSecondParent_noSecondShopifyCall() {
        Shop s = new Shop("x3");
        shopifyReturns(s, "#2906", "2026-11-02T10:00:00Z", s.jeans30);

        process(s.tenant, exchange("3251207139", "#2906:#2906-R1", AFTER, 1, 1));
        ObjectNode later = exchange("3251207139", "#2906:#2906-R1", AFTER, 1, 1);
        later.put("updatedAt", "2026-11-16T09:00:00.000Z");
        later.putObject("state").put("code", 20).put("value", "Route assigned");
        process(s.tenant, later);

        assertThat(count("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND origin = 'portal_pre_connect'", s.tenant)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", s.tenant, "3251207139")).isEqualTo(1);
        verify(shopify, times(1)).findOrdersByName(anyString(), anyString(), anyString());
    }

    @Test
    void x4_multiItem_staysOnTheUnlinkedLane_noShopifyCall() {
        Shop s = new Shop("x4");
        long ev = process(s.tenant, exchange("3092641690", "#2950:#2950-R1", AFTER, 2, 1));
        assertThat(note(ev)).isEqualTo("unlinked: 3092641690");
        assertThat(jdbc.queryForObject("SELECT match_reason FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ?",
            String.class, s.tenant, "3092641690")).isEqualTo("EXCHANGE_MULTI_ITEM");
        verify(shopify, never()).findOrdersByName(anyString(), anyString(), anyString());
    }

    @Test
    void x5_crossTenant_otherTenantsParentNeverReused_positiveControl() {
        Shop a = new Shop("x5a");
        Shop b = new Shop("x5b");
        shopifyReturns(b, "#2739", "2026-11-01T10:00:00Z", b.jeans30);
        process(b.tenant, exchange("1332754325", "#2739:#2739-R1", AFTER, 1, 1));
        UUID bParent = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#2739'", UUID.class, b.tenant);
        assertThat(jdbc.queryForObject("SELECT matched_order_id FROM exchanges WHERE tracking_number = '1332754325'", UUID.class))
            .as("positive control: B's own exchange matches B's row").isEqualTo(bParent);

        reset(shopify);
        shopifyReturns(a, "#2739", "2026-11-01T10:00:00Z", a.jeans30);
        process(a.tenant, exchange("7779516755", "#2739:#2739-R1", AFTER, 1, 1));
        UUID aParent = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#2739'", UUID.class, a.tenant);
        assertThat(aParent).isNotEqualTo(bParent);
        assertThat(jdbc.queryForObject("SELECT matched_order_id FROM exchanges WHERE tracking_number = '7779516755'", UUID.class))
            .isEqualTo(aParent);
        verify(shopify, times(1)).findOrdersByName(eq(a.shop), anyString(), eq("2739"));
    }

    @Test
    void x6_shopifyOrderPlacedAfterConnect_neverStoredAsPreConnect() {
        Shop s = new Shop("x6");
        shopifyReturns(s, "#2945", "2026-11-14T10:00:00Z", s.jeans30);
        process(s.tenant, exchange("3549194002", "#2945:#2945-R1", AFTER, 1, 1));
        assertThat(count("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND origin = 'portal_pre_connect'", s.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT matched_order_id FROM exchanges WHERE tracking_number = '3549194002'", UUID.class)).isNull();
    }

    @Test
    void x7_exactDescription_autoMapsToExcOrder_andMatchesTheParent() {
        Shop s = new Shop("x7");
        shopifyReturns(s, "#2955", "2026-11-01T10:00:00Z", s.jeans30);
        process(s.tenant, exchange("1332750001", "#2955:#2955-R1", AFTER, 1, 1));
        Map<String, Object> ex = jdbc.queryForMap(
            "SELECT status, matched_order_id, outbound_order_id FROM exchanges WHERE tracking_number = '1332750001'");
        assertThat(ex.get("status")).isEqualTo("matched");
        assertThat(ex.get("matched_order_id")).isEqualTo(
            jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#2955'", UUID.class, s.tenant));
        assertThat(jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, ex.get("outbound_order_id")))
            .isEqualTo("EXC-1332750001");
    }

    @Test
    @SuppressWarnings("unchecked")
    void c1_crpOfPreConnectOrder_legLinkedToParent_scanReturnsListsUntrackedUnits() {
        Shop s = new Shop("c1");
        shopifyReturns(s, "#2948", "2026-11-01T10:00:00Z", s.jeans30);

        ObjectNode crp = exchange("3741531660", "#2948:#2948-R1", AFTER, 1, 1);
        crp.putObject("type").put("code", 25).put("value", "Customer Return Pickup");
        crp.remove("returnSpecs");
        long ev = process(s.tenant, crp);
        assertThat(note(ev)).isNull();

        UUID parent = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#2948'", UUID.class, s.tenant);
        Map<String, Object> leg = jdbc.queryForMap(
            "SELECT id, order_id, shipment_leg FROM shipments WHERE tenant_id = ? AND tracking_number = '3741531660'", s.tenant);
        assertThat(leg.get("order_id")).isEqualTo(parent);
        assertThat(leg.get("shipment_leg")).isEqualTo("return");
        assertThat(count("SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = '3741531660'",
            s.tenant)).isZero();

        // The courier brings it back; Scan returns lists the parent's line as one untracked unit row.
        jdbc.update("UPDATE shipments SET internal_state = 'returned' WHERE id = ?", leg.get("id"));
        UUID session = TenantContext.runAs(s.tenant, () -> returns.createSession(null, s.owner));
        TenantContext.runAs(s.tenant, () -> returns.scan(session, "A-06-3741531660", s.warehouse, s.owner));
        Map<String, Object> view = TenantContext.runAs(s.tenant, () -> returns.getSession(session));
        List<Map<String, Object>> parcels = (List<Map<String, Object>>) view.get("parcels");
        assertThat(parcels).hasSize(1);
        List<Map<String, Object>> units = (List<Map<String, Object>>) parcels.get(0).get("untrackedUnits");
        assertThat(units).hasSize(1);
    }

    @Test
    void c2_crp_shopifyFails_stillIngested_unlinked() {
        Shop s = new Shop("c2");
        when(shopify.findOrdersByName(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("Shopify 503"));
        ObjectNode crp = exchange("6252401153", "#2917:#2917-R1", AFTER, 1, 1);
        crp.putObject("type").put("code", 25).put("value", "Customer Return Pickup");
        crp.remove("returnSpecs");
        assertThat(note(process(s.tenant, crp))).isEqualTo("unlinked: 6252401153");
    }

    /**
     * 2026-11-16 combined (after the BRK D1–D4 merge): a pre-connect CRP arrives → Scan returns lists the parent's lines
     * as untracked units (D4: no allocation → untracked) → the Bosta label parser puts the line its SKU names first,
     * flagged onLabel → mark arrived → Undo inside the session → mark again → close.
     * NOT covered: "Undo arrival" AFTER the session closed — it lives on the order drawer, which (with its undo
     * endpoints) reads warehouse_orders and is 404 for a pre-connect parent by the P4a rule. Decision pending.
     */
    @Test
    @SuppressWarnings("unchecked")
    void d1_preConnectCrp_untrackedLines_labelPreselects_arrived_undoInSession() {
        Shop s = new Shop("d1");
        // The pre-connect order in Shopify: both sizes; only the 30 is coming back.
        ObjectNode raw = mapper.createObjectNode();
        raw.put("name", "#2978");
        ArrayNode edges = raw.putObject("lineItems").putArray("edges");
        for (String gid : List.of(s.jeans32Gid, s.jeans30Gid)) {
            ObjectNode node = edges.addObject().putObject("node");
            node.put("id", "gid://shopify/LineItem/" + UUID.randomUUID());
            node.put("quantity", 1);
            node.put("currentQuantity", 1);
            node.putObject("variant").put("id", gid);
        }
        when(shopify.findOrdersByName(eq(s.shop), anyString(), anyString())).thenReturn(new ShopifyGateway.PortalOrderSearch(
            List.of(new ShopifyGateway.Order("gid://shopify/Order/" + UUID.randomUUID(), "#2978", null, null, null, "PENDING",
                List.of("Cash on Delivery (COD)"), new BigDecimal("1900.00"), List.of(), Instant.parse("2026-11-02T10:00:00Z"), raw)),
            true));

        ObjectNode crp = exchange("3201727169", "#2978:#2978-R1", AFTER, 1, 1);
        crp.putObject("type").put("code", 25).put("value", "Customer Return Pickup");
        crp.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1)
            .put("description", "x1 Jeans - 30 - SKU: JN-30-d1");
        assertThat(note(process(s.tenant, crp))).isNull();
        UUID parent = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#2978'", UUID.class, s.tenant);
        UUID leg = jdbc.queryForObject("SELECT id FROM shipments WHERE tenant_id = ? AND tracking_number = '3201727169' " +
            "AND shipment_leg = 'return' AND order_id = ?", UUID.class, s.tenant, parent);
        UUID line30 = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?", UUID.class, parent, s.jeans30);

        // The courier brings it back.
        jdbc.update("UPDATE shipments SET internal_state = 'returned' WHERE id = ?", leg);
        UUID session = TenantContext.runAs(s.tenant, () -> returns.createSession(null, s.owner));
        TenantContext.runAs(s.tenant, () -> returns.scan(session, "A-06-3201727169", s.warehouse, s.owner));

        Map<String, Object> parcel = ((List<Map<String, Object>>) TenantContext.runAs(s.tenant,
            () -> returns.getSession(session)).get("parcels")).get(0);
        List<Map<String, Object>> units = (List<Map<String, Object>>) parcel.get("untrackedUnits");
        assertThat(units).as("D4: both lines of the pre-connect parent are untracked").hasSize(2);
        assertThat(parcel).containsKey("bostaLabel");
        assertThat(units.get(0).get("orderItemId")).as("the label's line comes first").isEqualTo(line30.toString());
        assertThat(units.get(0).get("onLabel")).isEqualTo(true);
        assertThat(units.get(1).get("onLabel")).as("not on the Bosta label").isEqualTo(false);

        // Mark the pre-selected line arrived, undo it inside the session, mark it again.
        TenantContext.runAs(s.tenant, () -> returns.unitArrived(session, leg, line30, 1, "sellable", s.owner));
        assertThat(count("SELECT COUNT(*) FROM untracked_unit_intakes WHERE shipment_id = ? AND undone_at IS NULL", leg)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM untracked_unit_intakes WHERE order_id = ? AND undone_at IS NULL", parent))
            .as("the intake is on the pre-connect parent").isEqualTo(1);
        TenantContext.runAs(s.tenant, () -> returns.undoUnitArrived(session, leg, line30, 1, s.owner));
        assertThat(count("SELECT COUNT(*) FROM untracked_unit_intakes WHERE shipment_id = ? AND undone_at IS NULL", leg)).isZero();
        TenantContext.runAs(s.tenant, () -> returns.unitArrived(session, leg, line30, 1, "sellable", s.owner));
        TenantContext.runAs(s.tenant, () -> returns.close(session, s.owner));
        assertThat(jdbc.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class, leg))
            .isEqualTo("untracked_units_arrived");
        assertThat(count("SELECT COUNT(*) FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id WHERE oi.order_id = ?",
            parent)).as("no piece, no allocation").isZero();
    }

    // ── reprocess (P5) ───────────────────────────────────────────────────────

    @Test
    void r1_dryRun_reportsTheDelivery_writesNothing() {
        Shop s = new Shop("r1");
        shopifyReturns(s, "#2739", "2026-11-01T10:00:00Z", s.jeans30);
        long ev = ignored(s, exchange("2472100001", "#2739:#2739-R1", AFTER, 1, 1));

        var rows = reprocess.run(s.tenant, List.of("2472100001"), false);
        assertThat(rows).hasSize(1);
        var r = rows.get(0);
        assertThat(r.verdict()).isEqualTo("WOULD_REPROCESS");
        assertThat(r.typeCode()).isEqualTo(30);
        assertThat(r.createdAt()).isEqualTo("2026-11-15T22:43:11Z");
        assertThat(r.state()).isEqualTo(10);
        assertThat(r.reference()).isEqualTo("#2739:#2739-R1");
        assertThat(r.outItems()).isEqualTo(1);
        assertThat(r.inItems()).isEqualTo(1);
        assertThat(r.decision()).isEqualTo("KEEP");
        assertThat(note(ev)).isEqualTo("ignored_pre_connect: 2472100001");
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", s.tenant)).isZero();
        verify(shopify, never()).findOrdersByName(anyString(), anyString(), anyString());
    }

    @Test
    void r2_apply_reprocessesIntoTheExchangeLane_secondApplyIsANoOp() {
        Shop s = new Shop("r2");
        shopifyReturns(s, "#2922", "2026-11-01T10:00:00Z", s.jeans30);
        long ev = ignored(s, exchange("5028100002", "#2922:#2922-R1", AFTER, 1, 1));

        var first = reprocess.run(s.tenant, List.of(), true);
        assertThat(first).extracting(com.traceability.integrations.bosta.PreConnectReprocessService.Row::verdict).containsExactly("REPROCESSED");
        assertThat(first.get(0).detail()).isEqualTo("exchange: 5028100002");
        assertThat(note(ev)).isEqualTo("exchange: 5028100002");
        UUID parent = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#2922'", UUID.class, s.tenant);
        assertThat(jdbc.queryForObject("SELECT matched_order_id FROM exchanges WHERE tracking_number = '5028100002'", UUID.class))
            .isEqualTo(parent);

        var second = reprocess.run(s.tenant, List.of(), true);
        assertThat(second).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", s.tenant)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND origin = 'portal_pre_connect'", s.tenant)).isEqualTo(1);
    }

    @Test
    void r3_pickedUp_isNotOpen_preCutoff_staysIgnored_evenOnApply() {
        Shop s = new Shop("r3");
        ObjectNode picked = exchange("3251200003", "#2906:#2906-R1", AFTER, 1, 1);
        picked.putObject("state").put("code", 21).put("value", "Picked up");
        long evPicked = ignored(s, picked);
        long evOld = ignored(s, exchange("4545100004", "#2950:#2950-R1",
            "Wed Nov 12 2026 10:00:00 GMT+0000 (Coordinated Universal Time)", 1, 1));

        var rows = reprocess.run(s.tenant, List.of(), true);
        assertThat(rows).extracting(com.traceability.integrations.bosta.PreConnectReprocessService.Row::trackingNumber, com.traceability.integrations.bosta.PreConnectReprocessService.Row::verdict)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("3251200003", "NOT_OPEN"),
                org.assertj.core.groups.Tuple.tuple("4545100004", "STILL_IGNORED"));
        assertThat(note(evPicked)).startsWith("ignored_pre_connect:");
        assertThat(note(evOld)).startsWith("ignored_pre_connect:");
    }

    @Test
    void r4_excludedTenant_andCrossTenant_untouched() {
        Shop little = new Shop("r4lt", UUID.fromString("fbc7e946-12d9-4a3b-a396-387c0dacf9f5"));
        long evLt = ignored(little, exchange("1111000001", "#2700:#2700-R1", AFTER, 1, 1));
        assertThat(reprocess.run(little.tenant, List.of(), true))
            .extracting(com.traceability.integrations.bosta.PreConnectReprocessService.Row::verdict, com.traceability.integrations.bosta.PreConnectReprocessService.Row::detail)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("SKIP", "tenant excluded"));
        assertThat(note(evLt)).startsWith("ignored_pre_connect:");

        Shop a = new Shop("r4a");
        Shop b = new Shop("r4b");
        long evB = ignored(b, exchange("1111000002", "#2701:#2701-R1", AFTER, 1, 1));
        shopifyReturns(a, "#2702", "2026-11-01T10:00:00Z", a.jeans30);
        ignored(a, exchange("1111000003", "#2702:#2702-R1", AFTER, 1, 1));
        assertThat(reprocess.run(a.tenant, List.of(), true))
            .extracting(com.traceability.integrations.bosta.PreConnectReprocessService.Row::trackingNumber).containsExactly("1111000003");
        assertThat(note(evB)).as("tenant B's row is never a candidate of tenant A").startsWith("ignored_pre_connect:");
    }

    @Test
    void r5_explicitList_onlyThose_unknownReported() {
        Shop s = new Shop("r5");
        shopifyReturns(s, "#2945", "2026-11-01T10:00:00Z", s.jeans30);
        ignored(s, exchange("7779500005", "#2945:#2945-R1", AFTER, 1, 1));
        long other = ignored(s, exchange("1332700006", "#2955:#2955-R1", AFTER, 1, 1));
        var rows = reprocess.run(s.tenant, List.of("7779500005", "9999999999"), false);
        assertThat(rows).extracting(com.traceability.integrations.bosta.PreConnectReprocessService.Row::trackingNumber, com.traceability.integrations.bosta.PreConnectReprocessService.Row::verdict)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("7779500005", "WOULD_REPROCESS"),
                             org.assertj.core.groups.Tuple.tuple("9999999999", "SKIP"));
        assertThat(note(other)).startsWith("ignored_pre_connect:");
    }

    /** The row discovery wrote for an ignored delivery (same shape as BostaIngestionHelper.recordIgnoredPreConnect). */
    long ignored(Shop s, ObjectNode raw) {
        String tracking = raw.path("trackingNumber").asText();
        when(bostaGateway.fetchDelivery(eq("key-" + s.tenant), eq(tracking))).thenReturn(BostaDelivery.fromRaw(tracking, raw));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tracking);
        payload.put("state", raw.path("state").path("code").asInt());
        payload.put("type", "EXCHANGE");
        payload.put("updatedAt", raw.path("updatedAt").asText());
        payload.put("businessReference", raw.path("businessReference").asText());
        String key = BostaWebhookJob.sha256(tracking + ":" + raw.path("state").path("code").asInt() + ":" + raw.path("updatedAt").asText());
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, processed_at, external_event_id, error) " +
            "VALUES ('bosta_poll_discovery'::webhook_source, ?, 'delivery_update', ?::jsonb, 'processed', now(), now(), ?, ?) RETURNING id",
            Long.class, s.tenant, payload.toString(), key, "ignored_pre_connect: " + tracking);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A Storm-shaped tenant: cutoff 2026-11-13 17:15Z, lowest ingested order #3018, two catalog variants. */
    class Shop {
        final UUID tenant;
        final UUID store = UUID.randomUUID();
        final String shop;
        final UUID jeans30, jeans32;
        final String jeans30Gid, jeans32Gid;
        final UUID owner = UUID.randomUUID(), warehouse = UUID.randomUUID();

        Shop(String name) { this(name, UUID.randomUUID()); }

        Shop(String name, UUID id) {
            tenant = id;
            shop = "pc-" + name + "-" + tenant.toString().substring(0, 8) + ".myshopify.com";
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Storm-" + name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
                owner, tenant, "owner-" + tenant + "@example.com");
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true)",
                warehouse, tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryption.encrypt("key-" + tenant));
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, tenant, shop, Timestamp.from(STORM_CUTOFF));
            jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, '#3018', 'new'::order_status, ?)",
                tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), Timestamp.from(Instant.parse("2026-11-13T18:00:00Z")));
            UUID product = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Jeans', 'active')",
                product, tenant, store, "gid://shopify/Product/" + UUID.randomUUID());
            jeans30 = UUID.randomUUID(); jeans32 = UUID.randomUUID();
            jeans30Gid = "gid://shopify/ProductVariant/" + UUID.randomUUID();
            jeans32Gid = "gid://shopify/ProductVariant/" + UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, '30', ?)",
                jeans30, tenant, product, jeans30Gid, "JN-30-" + name);
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, '32', ?)",
                jeans32, tenant, product, jeans32Gid, "JN-32-" + name);
        }

        String gidOf(UUID variant) { return variant.equals(jeans30) ? jeans30Gid : jeans32Gid; }
    }

    /** Shopify answers findOrdersByName for {@code name} with one order of one line of {@code variant}. */
    void shopifyReturns(Shop s, String name, String createdAt, UUID variant) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("id", "gid://shopify/Order/" + Math.abs(name.hashCode()) + s.tenant.toString().substring(0, 4).hashCode());
        raw.put("name", name);
        ArrayNode edges = raw.putObject("lineItems").putArray("edges");
        ObjectNode node = edges.addObject().putObject("node");
        node.put("id", "gid://shopify/LineItem/" + UUID.randomUUID());
        node.put("quantity", 1);
        node.put("currentQuantity", 1);
        node.putObject("variant").put("id", s.gidOf(variant));
        ShopifyGateway.Order order = new ShopifyGateway.Order(
            "gid://shopify/Order/" + UUID.randomUUID(), name, null, null, null, "PENDING", List.of("Cash on Delivery (COD)"),
            new BigDecimal("950.00"), List.of(), Instant.parse(createdAt), raw);
        when(shopify.findOrdersByName(eq(s.shop), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(order), true));
    }

    ObjectNode exchange(String tracking, String reference, String createdAt, int outItems, int inItems) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.put("businessReference", reference);
        raw.put("createdAt", createdAt);
        raw.put("updatedAt", "2026-11-15T22:43:11.000Z");
        raw.putObject("type").put("code", 30).put("value", "Exchange");
        raw.putObject("state").put("code", 10).put("value", "Pickup requested");
        raw.putObject("specs").putObject("packageDetails").put("itemsCount", outItems).put("description", "Jeans 32");
        raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", inItems).put("description", "Jeans 30");
        raw.putObject("receiver").put("fullName", "Test Customer").put("phone", "01000000000");
        return raw;
    }

    long process(UUID tenant, ObjectNode raw) {
        String tracking = raw.path("trackingNumber").asText();
        when(bostaGateway.fetchDelivery(eq("key-" + tenant), eq(tracking))).thenReturn(BostaDelivery.fromRaw(tracking, raw));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tracking);
        payload.put("state", raw.path("state").path("code").asInt());
        payload.put("updatedAt", raw.path("updatedAt").asText());
        Long id = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenant, payload.toString());
        webhookJob.process(id, tenant);
        return id;
    }

    String note(long id) {
        return jdbc.queryForObject("SELECT error FROM webhook_events WHERE id = ?", String.class, id);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
