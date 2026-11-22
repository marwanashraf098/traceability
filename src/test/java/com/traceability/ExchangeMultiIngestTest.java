package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyReturnsGateway;
import com.traceability.integrations.shopify.ShopifyReturnsStore;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.security.EncryptionService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exchange reference S3b (2026-11-19) — multi-item Bosta exchanges through the REAL ingest pipeline (discovery enqueue +
 * BostaWebhookJob.process), prod shapes.
 *
 * <pre>
 *   i1  BROEK "-R" + SKU labels, every SKU in the catalog, label sum = itemsCount → the row is created, AUTO-COMMITTED
 *       (one internal:exchange: order, one line per label line, auto_matched; the inbound leg one row, no variant),
 *       matched by reference; the open EXCHANGE_MULTI_ITEM unlinked row is resolved and its alert is gone
 *   i2  BROEK labels with a SKU Traced doesn't have → needs_mapping, prefill one row per label line (variant
 *       suggested by exact SKU, the unknown one empty); and the 4226201959 shape (Bosta itemsCount 1, the label 3) →
 *       never auto-committed, prefill = the label's 3 rows
 *   i3  Seductionegypt "A - 100 ML + B - 100 ML + C - 100 ML" (3 / 3): the single-item resolver alone WOULD auto-map
 *       it (EXACT) — counts decide: never committed, needs_mapping. MagRina Arabic "عدد 2 بنطلون -و2 تيشيرت" → never
 *       committed, prefill = one row of Bosta's count each way with the Arabic text as hint
 *   i4  outbound 3, no returnSpecs → HELD: no exchanges row, the unlinked row stays as it was
 *   i5  overdue -R with a live Shopify-exchange shadow whose units match (out 1, in 2) → linked to the shadow: no
 *       internal:exchange: order (so no 4th decrement can apply), out row = the shadow's line, in rows = the Return's
 *       two lines (their variants)
 *   i6  the same shadow, Bosta's counts differ (out 2) → needs_mapping, nothing linked, no order
 *   i7  GOLDEN single-item: a 1 / 1 exchange creates exactly the row it always did (columns, raw, the inbound variant
 *       filled from its SKU), no exchange_items, no unlinked change
 *   i8  backfill: dry run writes nothing and reports WOULD_CREATE (auto-commit) / WOULD_LINK_SHADOW / WOULD_HOLD and
 *       SKIP for a disconnected tenant; apply creates / links, resolves the unlinked rows (alert cleared); a rerun is a
 *       no-op
 *   i10 a multi-item exchange never gets ONE inbound variant (ExchangeInbound.fill skipped), even when its original
 *       has a single line
 *   i9  app_user isolation: tenant B's ingest of the same AWB never resolves tenant A's unlinked row; same-tenant
 *       positive control
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeMultiIngestTest {

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
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add(ShopifyShadowOrders.S3C_FLAG, () -> "true");
    }

    @MockBean JobScheduler          jobScheduler;
    @MockBean ShopifyGateway        shopifyGateway;
    @MockBean ShopifyTokenProvider  tokenProvider;
    @MockBean ShopifyReturnsGateway returnsGateway;
    @MockBean BostaGateway          bostaGateway;

    @Autowired JdbcTemplate                jdbc;
    @Autowired ObjectMapper                mapper;
    @Autowired EncryptionService           encryption;
    @Autowired ShopifyReturnsStore         returnsStore;
    @Autowired ShopifyShadowOrders         shadows;
    @Autowired BostaWebhookJob             webhookJob;
    @Autowired BostaIngestionHelper        ingestionHelper;
    @Autowired ExchangeService             exchanges;
    @Autowired ExchangeMatchService        matchService;
    @Autowired ExceptionService            exceptionService;
    @Autowired ExchangeMultiCatchUpService catchUp;
    @Autowired ExchangeStateInterpreter    interpreter;
    @Autowired PlatformTransactionManager  txm;
    @Autowired FulfillService              fulfill;
    @Autowired WaybillResolver             waybills;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;

    static final AtomicLong IDS = new AtomicLong(88_000_000_000_000L);
    static final Instant APPROVED = Instant.parse("2026-11-10T09:30:00Z");

    @BeforeAll
    void appUser() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTxm = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, returnsGateway, bostaGateway);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── i1 ───────────────────────────────────────────────────────────────────

    @Test
    void i1_broekSkuLabels_allKnown_autoCommitted_unlinkedResolved() {
        Shop s = new Shop("I1", false, "BRK-47001-EG");
        UUID sweatL = s.variant("Baggy Sweatpants", "Black / L", "BRK-RSP-2627-BLK-L");
        s.variant("Baggy Sweatpants", "Black / M", "BRK-RSP-2627-BLK-M");
        s.variant("Never Quit Crewneck", "Beige / M", "BRK-CRW-NQM-BEG-M");
        String awb = tn();
        BostaDelivery d = delivery(awb, "BRK-47001-EG:BRK-47001-EG-R1", 1,
            "x1 Baggy Sweatpants – Black - L - SKU: BRK-RSP-2627-BLK-L", 2,
            "x1 Never Quit Motivational Crewneck - Beige - M - SKU: BRK-CRW-NQM-BEG-M // x1 Baggy Sweatpants – Black - M - SKU: BRK-RSP-2627-BLK-M");
        s.unlinked(d);
        assertThat(s.alerts("unmatched_delivery")).hasSize(1);

        ingest(s, d);

        Map<String, Object> ex = s.exchange(awb);
        assertThat(ex.get("auto_matched")).isEqualTo(true);
        assertThat(ex.get("status")).as("matched by its BRK- reference too").isEqualTo("matched");
        UUID exc = s.internalOrder(awb);
        assertThat(ex.get("outbound_order_id")).isEqualTo(exc);
        assertThat(jdbc.queryForList("SELECT variant_id FROM order_items WHERE order_id = ?", UUID.class, exc)).containsExactly(sweatL);
        List<Map<String, Object>> items = s.items(awb);
        assertThat(items).extracting(i -> i.get("direction")).containsExactly("out", "in");
        assertThat(items.get(1).get("variant_id")).as("the inbound leg is never committed").isNull();
        assertThat(items.get(1).get("quantity")).isEqualTo(2);
        assertThat(s.unlinkedResolved(awb)).isTrue();
        assertThat(s.alerts("unmatched_delivery")).isEmpty();
    }

    // ── i2 ───────────────────────────────────────────────────────────────────

    @Test
    void i2_unknownSku_or_labelSumDiffers_neverAutoCommitted_prefillPerLabelLine() {
        Shop s = new Shop("I2", false, "BRK-47002-EG");
        UUID jeans32 = s.variant("Washed Black Relaxed Fit Denim Jeans", "32", "BRK-JEANS-BLK-RF-002");
        String awb = tn();
        ingest(s, delivery(awb, "BRK-47002-EG:BRK-47002-EG-R1", 2,
            "x1 Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002 // x1 Raw Denim Relaxed Fit Jeans - 32 - SKU: BROEK-Raw-Denim-RFD-2",
            2, "x1 Washed Black Relaxed Fit Denim Jeans - 34 - SKU: BRK-JEANS-BLK-RF-003 // x1 Raw Denim Relaxed Fit Jeans - 34 - SKU: BROEK-Raw-Denim-RFD-3"));
        assertThat(s.exchange(awb).get("status")).isEqualTo("needs_mapping");
        assertThat(s.exchange(awb).get("outbound_order_id")).isNull();
        Map<String, Object> pre = s.prefill(awb);
        assertThat(pre.get("labelParsed")).isEqualTo(true);
        List<Map<String, Object>> out = rows(pre, "outbound");
        assertThat(out).hasSize(2);
        assertThat(out.get(0).get("variantId")).isEqualTo(jeans32.toString());
        assertThat(out.get(1).get("variantId")).as("SKU not in the catalog → empty").isNull();
        assertThat(out.get(1).get("sku")).isEqualTo("BROEK-Raw-Denim-RFD-2");

        // 4226201959: Bosta says 1, the label carries 3 — never auto-committed even with every SKU known.
        s.variant("Raw Denim Relaxed Fit Jeans", "32", "BROEK-Raw-Denim-RFD-2");
        s.variant("Gray Relaxed Fit Denim Jeans", "32", "BROEK-Gray-RFD-2");
        String odd = tn();
        ingest(s, delivery(odd, "BRK-47002-EG:BRK-47002-EG-R2", 1,
            "x1 Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002 // x1 Raw Denim Relaxed Fit Jeans - 32 - SKU: BROEK-Raw-Denim-RFD-2 // x1 Gray Relaxed Fit Denim Jeans - 32 - SKU: BROEK-Gray-RFD-2",
            2, "x1 Gray Relaxed Fit Denim Jeans - 34 - SKU: BROEK-Gray-RFD-3 // x1 Raw Denim Relaxed Fit Jeans - 34 - SKU: BROEK-Raw-Denim-RFD-3"));
        assertThat(s.exchange(odd).get("outbound_order_id")).as("label sum ≠ itemsCount").isNull();
        assertThat(s.exchange(odd).get("auto_matched")).isEqualTo(false);
        assertThat(rows(s.prefill(odd), "outbound")).hasSize(3);
    }

    // ── i3 ───────────────────────────────────────────────────────────────────

    @Test
    void i3_freeTextMultiItem_neverAutoCommitted_countsDecide() {
        Shop s = new Shop("I3", false, "#4501");
        UUID heaven = s.variant("Heaven", "100 ML", "SED-HVN-100");
        String desc = "Heaven - 100 ML + Get in Touch - 100 ML + Bold Night - 100 ML";
        assertThat(s.as(() -> new TransactionTemplate(txm).execute(t -> matchService.resolveOutboundVariant(desc)))
            .committedVariantId()).as("the single-item resolver alone would auto-map it").isEqualTo(heaven);
        String awb = tn();
        ingest(s, delivery(awb, "4501", 3, desc, 3, "Crystal Mist - 100 ML + Luxe Royale - 100 ML + Tobacco Butter - 100 ML"));
        assertThat(s.exchange(awb).get("outbound_order_id")).isNull();
        assertThat(s.internalOrder(awb)).isNull();
        assertThat(s.exchange(awb).get("status")).isEqualTo("needs_mapping");

        String ar = tn();
        ingest(s, delivery(ar, null, 2, "قميص جينز مقاس -2 \\ توب بني بكم مقاس 2", 4, "عدد 2 بنطلون -و2 تيشيرت"));
        assertThat(s.exchange(ar).get("status")).isEqualTo("needs_mapping");
        Map<String, Object> pre = s.prefill(ar);
        assertThat(pre.get("labelParsed")).isEqualTo(false);
        assertThat(rows(pre, "outbound")).singleElement().satisfies(r -> {
            assertThat(r.get("quantity")).isEqualTo(2);
            assertThat(r.get("descriptionHint")).isEqualTo("قميص جينز مقاس -2 \\ توب بني بكم مقاس 2");
        });
        assertThat(rows(pre, "inbound")).singleElement().satisfies(r -> {
            assertThat(r.get("quantity")).isEqualTo(4);
            assertThat(r.get("descriptionHint")).isEqualTo("عدد 2 بنطلون -و2 تيشيرت");
        });
    }

    // ── i4 ───────────────────────────────────────────────────────────────────

    @Test
    void i4_outboundMulti_noReturnSpecs_held() {
        Shop s = new Shop("I4", false, "#9301");
        String awb = tn();
        BostaDelivery d = delivery(awb, "hs-sparkle-clothing:#9301", 3,
            "Sweat pants pallon - Brown / L  X 1 \nSweat pants pallon - Black / L  X 1 \nSweat pants pallon - Gray / L  X 1", -1, null);
        s.unlinked(d);
        ingest(s, d);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", Integer.class, s.tenant)).isZero();
        assertThat(s.unlinkedResolved(awb)).as("HELD leaves it as it was").isFalse();
    }

    // ── i5 / i6 ──────────────────────────────────────────────────────────────

    @Test
    void i5_overdueShadow_unitsMatch_linkedToShadow_noInternalOrder() {
        Shop s = new Shop("I5", true, "#OD2604");
        s.shadowReturn("#OD2604-R1");
        UUID shadow = s.shadow();
        String awb = tn();
        ingest(s, delivery(awb, "overdue-9573:#OD2604-R1", 1,
            "x1 ROUND LONG SLEEVE COTTON TOP - BEIGE - Beige / XL", 2,
            "x1 ROUND AERO CONTOUR LONG SLEEVE TOP - OLIVE - Olive / L // x1 ROUND LONG SLEEVE COTTON TOP - BEIGE - Beige / L"));

        assertThat(s.internalOrder(awb)).as("never an internal:exchange: order").isNull();
        Map<String, Object> ex = s.exchange(awb);
        assertThat(ex.get("outbound_order_id")).isEqualTo(shadow);
        assertThat(ex.get("auto_matched")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT order_id FROM shipments WHERE tracking_number = ?", UUID.class, awb)).isEqualTo(shadow);
        List<Map<String, Object>> items = s.items(awb);
        assertThat(items.stream().filter(i -> "out".equals(i.get("direction")))).singleElement().satisfies(i -> {
            assertThat(i.get("variant_id")).isEqualTo(s.v);
            assertThat(i.get("order_item_id")).isNotNull();
        });
        assertThat(items.stream().filter(i -> "in".equals(i.get("direction"))).map(i -> i.get("variant_id")))
            .containsExactlyInAnyOrder(s.w, s.x);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'exchange_dispatch'", Integer.class, s.tenant)).isZero();
    }

    @Test
    void i6_shadow_countsDiffer_needsMapping() {
        Shop s = new Shop("I6", true, "#OD2610");
        s.shadowReturn("#OD2610-R1");
        String awb = tn();
        ingest(s, delivery(awb, "overdue-9573:#OD2610-R1", 2,
            "x1 ROUND LONG SLEEVE COTTON TOP - BEIGE - Beige / XL // x1 ROUND LONG SLEEVE COTTON TOP - GREY - Grey / XL", 2,
            "two tops"));
        Map<String, Object> ex = s.exchange(awb);
        assertThat(ex.get("outbound_order_id")).isNull();
        assertThat(ex.get("status")).isEqualTo("needs_mapping");
        assertThat(s.internalOrder(awb)).isNull();
        assertThat(s.items(awb)).isEmpty();
    }

    // ── i7 ───────────────────────────────────────────────────────────────────

    @Test
    void i7_golden_singleItem_unchanged() {
        Shop s = new Shop("I7", false, "#7001");
        UUID returned = s.variant("Linen Shirt", "White / L", "LIN-W-L");
        String awb = tn();
        BostaDelivery d = delivery(awb, null, 1, "a linen shirt nobody can name", 1, "x1 Linen Shirt - White / L - SKU: LIN-W-L");
        ingest(s, d);
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, outbound_description, inbound_description, inbound_description_ar, cod, goods_value, " +
            "       raw = ?::jsonb AS raw_same, outbound_order_id, outbound_variant_id, inbound_variant_id, matched_order_id, " +
            "       auto_matched, return_request_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            d.raw().toString(), s.tenant, awb);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("status", "needs_mapping");
        expected.put("outbound_description", "a linen shirt nobody can name");
        expected.put("inbound_description", "x1 Linen Shirt - White / L - SKU: LIN-W-L");
        expected.put("inbound_description_ar", null);
        expected.put("cod", new java.math.BigDecimal("0.00"));
        expected.put("goods_value", null);
        expected.put("raw_same", true);
        expected.put("outbound_order_id", null);
        expected.put("outbound_variant_id", null);
        expected.put("inbound_variant_id", returned);
        expected.put("matched_order_id", null);
        expected.put("auto_matched", false);
        expected.put("return_request_id", null);
        assertThat(row).hasSameSizeAs(expected).containsAllEntriesOf(expected);
        assertThat(s.items(awb)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ?", Integer.class, s.tenant)).isZero();
        assertThat(s.prefill(awb)).as("no prefill for a single-item exchange").isNull();
    }

    // ── i8 ───────────────────────────────────────────────────────────────────

    @Test
    void i8_backfill_dryRunThenApply_thenNoop() {
        Shop s = new Shop("I8", true, "BRK-48001-EG");
        s.variant("Baggy Sweatpants", "Black / L", "BRK-RSP-2627-BLK-L-8");
        s.shadowReturn("#OD2646-R1");
        String auto = tn(), shadowAwb = tn(), held = tn();
        BostaDelivery dAuto = delivery(auto, "BRK-48001-EG:BRK-48001-EG-R1", 1,
            "x1 Baggy Sweatpants – Black - L - SKU: BRK-RSP-2627-BLK-L-8", 2, "two things back");
        BostaDelivery dShadow = delivery(shadowAwb, "overdue-9573:#OD2646-R1", 1, "one top", 2, "two tops back");
        BostaDelivery dHeld = delivery(held, null, 2, "two things", -1, null);
        for (BostaDelivery d : List.of(dAuto, dShadow, dHeld)) {
            s.unlinked(d);
            when(bostaGateway.fetchDelivery(anyString(), eq(d.trackingNumber()))).thenReturn(d);
        }
        Shop off = new Shop("I8off", false, "#1");
        jdbc.update("UPDATE stores SET status = 'disconnected' WHERE tenant_id = ?", off.tenant);
        BostaDelivery dOff = delivery(tn(), null, 2, "x", 2, "y");
        off.unlinked(dOff);

        int events = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events", Integer.class);
        List<ExchangeMultiCatchUpService.Row> dry = catchUp.run(s.tenant, false);
        assertThat(verdict(dry, auto)).isEqualTo("WOULD_CREATE");
        assertThat(row(dry, auto).wouldAutoCommit()).isTrue();
        assertThat(row(dry, auto).labelParses()).isTrue();
        assertThat(verdict(dry, shadowAwb)).isEqualTo("WOULD_LINK_SHADOW");
        assertThat(verdict(dry, held)).isEqualTo("WOULD_HOLD");
        assertThat(catchUp.run(off.tenant, false)).singleElement().satisfies(r -> {
            assertThat(r.verdict()).isEqualTo("SKIP");
            assertThat(r.detail()).isEqualTo("store not connected");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", Integer.class, s.tenant)).as("dry run").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events", Integer.class)).isEqualTo(events);
        assertThat(s.unlinkedResolved(auto)).isFalse();

        List<ExchangeMultiCatchUpService.Row> applied = catchUp.run(s.tenant, true);
        assertThat(verdict(applied, auto)).isEqualTo("AUTO_COMMITTED");
        assertThat(verdict(applied, shadowAwb)).isEqualTo("LINKED_SHADOW");
        assertThat(verdict(applied, held)).isEqualTo("HELD");
        assertThat(s.unlinkedResolved(auto)).isTrue();
        assertThat(s.unlinkedResolved(shadowAwb)).isTrue();
        assertThat(s.unlinkedResolved(held)).isFalse();
        assertThat(s.alerts("unmatched_delivery")).as("only the held one is left").hasSize(1);

        List<ExchangeMultiCatchUpService.Row> again = catchUp.run(s.tenant, true);
        assertThat(again).as("rerun: only the held row is still open").singleElement()
            .satisfies(r -> assertThat(r.verdict()).isEqualTo("HELD"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", Integer.class, s.tenant)).isEqualTo(2);
    }

    // ── i9 ───────────────────────────────────────────────────────────────────

    @Test
    void i9_appUser_isolation() {
        Shop a = new Shop("I9A", false, "#9901");
        Shop b = new Shop("I9B", false, "#9902");
        String awb = tn();
        BostaDelivery d = delivery(awb, null, 2, "two", 2, "two back");
        a.unlinked(d);
        ExchangeIngestService app = new ExchangeIngestService(appJdbc, interpreter);
        TransactionTemplate appTx = new TransactionTemplate(appTxm);

        TenantContext.runAs(b.tenant, () -> appTx.execute(t -> app.upsertFromDelivery(b.tenant, awb, d)));
        assertThat(a.unlinkedResolved(awb)).as("B never touches A's unlinked row").isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, a.tenant, awb)).isZero();

        TenantContext.runAs(a.tenant, () -> appTx.execute(t -> app.upsertFromDelivery(a.tenant, awb, d)));
        assertThat(a.unlinkedResolved(awb)).as("positive control").isTrue();
    }

    // ── i10 ──────────────────────────────────────────────────────────────────

    @Test
    void i10_multiItem_neverGetsOneInboundVariant() {
        Shop s = new Shop("I10", false, "#1010");
        jdbc.update("DELETE FROM order_items WHERE order_id = ? AND variant_id = ?", s.original, s.x);  // the original: one line (W)
        String awb = tn();
        ingest(s, delivery(awb, "#1010", 2, "two things out", 2, "two things back"));
        assertThat(jdbc.queryForObject("SELECT matched_order_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            UUID.class, s.tenant, awb)).isEqualTo(s.original);
        assertThat(jdbc.queryForObject("SELECT inbound_variant_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            UUID.class, s.tenant, awb)).as("one variant would misdescribe a 2-item return").isNull();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    // ── i11 ──────────────────────────────────────────────────────────────────

    /**
     * Backfill shape (S3b condition 3): an exchange Bosta already took past pickup (the merchant packed it outside
     * Traced) that is auto-committed now — a multi-line internal:exchange: order — must never be pickable: not in the
     * queue, its waybill does not open it, it is not on the gather (pick) list. Positive control: the same shape still
     * at "Pickup requested" (10) is pickable in all three.
     */
    @Test
    void i11_alreadyPastPickup_autoCommittedExcOrder_neverPickable_queueWaybillGather() {
        Shop s = new Shop("I11", false, "BRK-47011-EG");
        s.variant("Baggy Sweatpants", "Black / L", "BRK-I11-BLK-L");
        s.variant("Never Quit Crewneck", "Beige / M", "BRK-I11-BEG-M");
        String outDesc = "x1 Baggy Sweatpants – Black - L - SKU: BRK-I11-BLK-L // x1 Never Quit Crewneck - Beige - M - SKU: BRK-I11-BEG-M";
        String inDesc = "x2 Baggy Sweatpants – Black - M";
        // 21 picked up from business, 22 picking up from consignee, 24 received at warehouse, 30 between hubs,
        // 45 delivered, 46 returned to business — every past-pickup state the prod backfill rows carry.
        // Each with the timeline milestone prod carries for that state (unlinked rows, 2026-11-19).
        int[][] past = {{21}, {22}, {24}, {30}, {45}, {46}};
        String[] names = {"Picked up from business", "Picking up from consignee", "Received at warehouse",
            "In transit between Hubs", "Delivered", "Returned to business"};
        String[] milestones = {"picked_up", "out_for_exchange", "in_transit", "in_transit", "exchanged_returned", "out_for_return"};
        String[] expectedLeg = {"with_courier", "with_courier", "with_courier", "with_courier", "delivered", "delivered"};
        Set<String> alertsBefore = alertKeys(s);
        Map<String, UUID> gone = new LinkedHashMap<>();
        for (int i = 0; i < past.length; i++) {
            String awb = tn();
            ingest(s, atState(delivery(awb, "BRK-47011-EG:BRK-47011-EG-R" + (i + 1), 2, outDesc, 2, inDesc),
                past[i][0], names[i], milestones[i]));
            UUID exc = s.internalOrder(awb);
            assertThat(exc).as("auto-committed at state %s", past[i][0]).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class, exc))
                .as("multi-line").isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, awb))
                .as("leg advanced at commit, %s", milestones[i]).isEqualTo(expectedLeg[i]);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_status_history h JOIN shipments sh ON sh.id = h.shipment_id " +
                "WHERE sh.tracking_number = ?", Integer.class, awb)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, exc))
                .as("the order's own status is never written").isEqualTo("new");
            gone.put(awb, exc);
        }
        String live = tn();
        ingest(s, atState(delivery(live, "BRK-47011-EG:BRK-47011-EG-R9", 2, outDesc, 2, inDesc), 10, "Pickup requested", "new"));
        UUID liveExc = s.internalOrder(live);
        assertThat(liveExc).isNotNull();
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, live))
            .as("no-op at Pickup requested").isEqualTo("created");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_status_history h JOIN shipments sh ON sh.id = h.shipment_id " +
            "WHERE sh.tracking_number = ?", Integer.class, live)).isZero();

        // Nothing but the leg moved: no alert, no piece / ledger event, no Shopify write of any kind.
        assertThat(alertKeys(s)).as("no alert raised by advancing at commit").isEqualTo(alertsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, s.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", Integer.class, s.tenant)).isZero();
        verifyNoInteractions(shopifyGateway);

        Set<UUID> queue = new HashSet<>();
        s.as(() -> fulfill.getQueue()).forEach(r -> queue.add((UUID) r.get("id")));
        assertThat(queue).as("positive control: still at the merchant → pickable").contains(liveExc);
        assertThat(queue).as("already past pickup → never in the queue").doesNotContainAnyElementsOf(gone.values());

        for (Map.Entry<String, UUID> e : gone.entrySet()) {
            WaybillResolver.Resolution r = s.as(() -> waybills.resolve(e.getKey(), null));
            assertThat(r.code()).as("waybill %s past pickup", e.getKey()).isNotEqualTo(WaybillResolver.Code.OPEN);
        }
        assertThat(s.as(() -> waybills.resolve(live, null)).code()).isEqualTo(WaybillResolver.Code.OPEN);

        List<String> gathered = s.as(() -> fulfill.getGatherList(null)).rows().stream()
            .flatMap(r -> r.orderNumbers().stream()).toList();
        assertThat(gathered).as("positive control").contains("EXC-" + live);
        for (String awb : gone.keySet()) assertThat(gathered).doesNotContain("EXC-" + awb);
    }

    // ── i12 ──────────────────────────────────────────────────────────────────

    /**
     * The alert suppression is for the advanced-at-commit leg ONLY: advanced at commit → no exchange_unmapped_state;
     * the next real Bosta update for the AWB moves the leg through the normal webhook path (with_courier → delivered)
     * and from then on the leg is treated like every other exchange leg (that detector's own behaviour is unchanged).
     */
    @Test
    void i12_advancedAtCommit_laterBostaUpdate_movesLeg_suppressionEnds() {
        Shop s = new Shop("I12", false, "BRK-47012-EG");
        s.variant("Baggy Sweatpants", "Black / L", "BRK-I12-BLK-L");
        s.variant("Never Quit Crewneck", "Beige / M", "BRK-I12-BEG-M");
        String outDesc = "x1 Baggy Sweatpants – Black - L - SKU: BRK-I12-BLK-L // x1 Never Quit Crewneck - Beige - M - SKU: BRK-I12-BEG-M";
        String awb = tn();
        BostaDelivery first = atState(delivery(awb, "BRK-47012-EG:BRK-47012-EG-R1", 2, outDesc, 2, "x2 back"),
            22, "Picking up from consignee", "out_for_exchange");
        ingest(s, first);
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, awb))
            .isEqualTo("with_courier");
        assertThat(alertKeys(s)).noneMatch(k -> k.startsWith("exchange_unmapped_state|"));

        ObjectNode later = (ObjectNode) atState(first, 46, "Returned to business", "out_for_return").raw().deepCopy();
        later.put("updatedAt", "2026-11-14T10:00:00Z");
        ingest(s, BostaDelivery.fromRaw(awb, later));
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, awb))
            .as("the normal type-30 webhook path still moves it").isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_status_history h JOIN shipments sh ON sh.id = h.shipment_id " +
            "WHERE sh.tracking_number = ? AND h.webhook_event_id IS NOT NULL", Integer.class, awb)).isEqualTo(1);
        assertThat(alertKeys(s)).as("suppression ended with the first Bosta update — detector unchanged from here")
            .anyMatch(k -> k.startsWith("exchange_unmapped_state|") && k.endsWith("|" + awb));
    }

    /** {@code d} at Bosta state {@code code}, its timeline's latest completed milestone {@code milestone}. */
    BostaDelivery atState(BostaDelivery d, int code, String value, String milestone) {
        ObjectNode raw = (ObjectNode) d.raw().deepCopy();
        raw.putObject("state").put("code", code).put("value", value);
        ArrayNode tl = raw.putArray("timeline");
        tl.addObject().put("value", "new").put("done", true).put("date", "2026-11-12T10:00:00.000Z");
        if (!"new".equals(milestone)) tl.addObject().put("value", milestone).put("done", true).put("date", "2026-11-13T10:00:00.000Z");
        tl.addObject().put("value", "exchanged_returned_placeholder").put("done", false);
        return BostaDelivery.fromRaw(d.trackingNumber(), raw);
    }

    /** Every open alert of the tenant as type|order|tracking — compared before and after. */
    Set<String> alertKeys(Shop s) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> a : s.as(() -> exceptionService.detectAllOpen())) {
            out.add(a.get("type") + "|" + a.get("order_id") + "|" + a.get("tracking_number"));
        }
        return out;
    }

    static String gid(String kind) { return "gid://shopify/" + kind + "/" + IDS.incrementAndGet(); }
    static String tn() { return String.valueOf(5_000_000_000L + IDS.incrementAndGet() % 4_000_000_000L); }

    static List<Map<String, Object>> rows(Map<String, Object> prefill, String leg) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> l = (List<Map<String, Object>>) prefill.get(leg);
        return l;
    }

    static ExchangeMultiCatchUpService.Row row(List<ExchangeMultiCatchUpService.Row> rows, String tn) {
        return rows.stream().filter(r -> tn.equals(r.trackingNumber())).findFirst().orElseThrow();
    }

    static String verdict(List<ExchangeMultiCatchUpService.Row> rows, String tn) { return row(rows, tn).verdict(); }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        final UUID product;
        final UUID v, w, x, original;
        final String originalGid, number;
        final long restV = IDS.incrementAndGet(), restW = IDS.incrementAndGet(), restX = IDS.incrementAndGet();

        /** {@code returnsAccess}: the store mirrors Shopify Returns (shadows possible). The original order is {@code number}. */
        Shop(String tag, boolean returnsAccess, String number) {
            this.number = number;
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "S3b " + tag);
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, returns_access) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', ?)", store, tenant,
                "s3b-" + tag.toLowerCase() + "-" + tenant.toString().substring(0, 6) + ".myshopify.com", returnsAccess ? "granted" : null);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, ?, 'active')", tenant, encryption.encrypt("key-" + tenant), "wh-" + tenant);
            product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'ROUND LONG SLEEVE COTTON TOP', 'active') RETURNING id", UUID.class, tenant, store, gid("Product"));
            v = variantGid(restV, "Beige / XL");
            w = variantGid(restW, "Beige / L");
            x = variantGid(restX, "Olive / L");
            originalGid = gid("Order");
            original = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
                "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '5 days', '{}'::jsonb) RETURNING id",
                UUID.class, tenant, store, originalGid, number);
            for (UUID var : List.of(w, x)) {
                jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) VALUES (?, ?, ?, 1, ?)",
                    tenant, original, var, gid("LineItem"));
            }
            UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward') RETURNING id", UUID.class, tenant, original, tn());
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, 'delivered')", tenant, leg);
        }

        private UUID variantGid(long rest, String title) {
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, product, "gid://shopify/ProductVariant/" + rest, "S3B-" + rest, title);
        }

        UUID variant(String productTitle, String title, String sku) {
            UUID p = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, ?, 'active') RETURNING id", UUID.class, tenant, store, gid("Product"), productTitle);
            return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, tenant, p, gid("ProductVariant"), sku, title);
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }

        /** The delivery as the old gate recorded it: an open EXCHANGE_MULTI_ITEM unlinked row. */
        void unlinked(BostaDelivery d) {
            jdbc.update("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
                "bosta_order_type, raw, match_reason) VALUES (?, ?, ?, ?, 'EXCHANGE', ?::jsonb, 'EXCHANGE_MULTI_ITEM')",
                tenant, d.trackingNumber(), d.businessReference(), d.stateCode(), d.raw().toString());
        }

        boolean unlinkedResolved(String tn) {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT bool_and(resolved) FROM unlinked_bosta_deliveries " +
                "WHERE tenant_id = ? AND tracking_number = ?", Boolean.class, tenant, tn));
        }

        List<Map<String, Object>> alerts(String type) {
            return as(() -> exceptionService.detectAllOpen().stream().filter(a -> type.equals(a.get("type"))).toList());
        }

        Map<String, Object> exchange(String tn) {
            return jdbc.queryForMap("SELECT id, status, outbound_order_id, outbound_variant_id, auto_matched FROM exchanges " +
                "WHERE tenant_id = ? AND tracking_number = ?", tenant, tn);
        }

        List<Map<String, Object>> items(String tn) {
            return jdbc.queryForList("SELECT ei.direction, ei.variant_id, ei.quantity, ei.order_item_id FROM exchange_items ei " +
                "JOIN exchanges e ON e.id = ei.exchange_id WHERE e.tenant_id = ? AND e.tracking_number = ? " +
                "ORDER BY ei.direction DESC, ei.created_at", tenant, tn);
        }

        UUID internalOrder(String tn) {
            List<UUID> ids = jdbc.queryForList("SELECT id FROM orders WHERE tenant_id = ? AND external_id = ?", UUID.class,
                tenant, "internal:exchange:" + tn);
            return ids.isEmpty() ? null : ids.get(0);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> prefill(String tn) {
            UUID id = (UUID) exchange(tn).get("id");
            return (Map<String, Object>) as(() -> exchanges.detail(id)).get("prefill");
        }

        /** OPEN Shopify Return {@code name}: exchange line V ×1; return lines W ×1 and X ×1 (the original's two lines). */
        void shadowReturn(String name) {
            ObjectNode r = mapper.createObjectNode();
            r.put("id", gid("Return"));
            r.put("name", name);
            r.put("status", "OPEN");
            r.put("createdAt", APPROVED.minusSeconds(3600).toString());
            r.put("requestApprovedAt", APPROVED.toString());
            r.putObject("order").put("id", originalGid);
            ArrayNode rl = r.putObject("returnLineItems").putArray("nodes");
            for (long rest : new long[]{restW, restX}) {
                ObjectNode n = rl.addObject();
                n.put("id", gid("ReturnLineItem"));
                n.put("quantity", 1);
                n.put("processedQuantity", 0);
                ObjectNode fli = n.putObject("fulfillmentLineItem");
                fli.put("id", gid("FulfillmentLineItem"));
                fli.putObject("lineItem").put("id", gid("LineItem")).putObject("variant").put("id", "gid://shopify/ProductVariant/" + rest);
            }
            ObjectNode x1 = r.putObject("exchangeLineItems").putArray("nodes").addObject();
            x1.put("id", gid("ExchangeLineItem"));
            x1.put("variantId", "gid://shopify/ProductVariant/" + restV);
            x1.put("quantity", 1);
            x1.put("processedQuantity", 0);
            x1.putArray("lineItems");
            as(() -> returnsStore.upsertReturn(store, original, r));
            as(() -> shadows.reconcileForOrder(original));
        }

        UUID shadow() {
            return jdbc.queryForObject("SELECT order_id FROM shopify_exchange_shadows WHERE tenant_id = ? AND state = 'live'",
                UUID.class, tenant);
        }
    }

    /** A type 30 delivery; {@code inCount} -1 = no returnSpecs at all (Bosta hasn't told the inbound leg yet). */
    BostaDelivery delivery(String tracking, String reference, int outCount, String outDesc, int inCount, String inDesc) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "d-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.putObject("type").put("code", 30).put("value", "Exchange");
        raw.putObject("state").put("code", 10).put("value", "Created");
        if (reference != null) raw.put("businessReference", reference); else raw.putNull("businessReference");
        raw.put("cod", 0);
        raw.putObject("receiver").put("firstName", "Mona").put("lastName", "Adel").put("phone", "01012345678");
        raw.putObject("specs").putObject("packageDetails").put("itemsCount", outCount).put("description", outDesc);
        if (inCount >= 0) raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", inCount).put("description", inDesc);
        raw.put("createdAt", "2026-11-12T10:00:00.000Z");
        raw.put("updatedAt", Instant.parse("2026-11-12T10:00:00Z").plusSeconds(IDS.incrementAndGet() % 1000).toString());
        return BostaDelivery.fromRaw(tracking, raw);
    }

    void ingest(Shop s, BostaDelivery d) {
        when(bostaGateway.fetchDelivery(anyString(), eq(d.trackingNumber()))).thenReturn(d);
        boolean enqueued = TenantContext.runAs(s.tenant,
            () -> ingestionHelper.ingestDelivery(s.tenant, "key-" + s.tenant, d.trackingNumber(), "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, s.tenant, d.trackingNumber());
        webhookJob.process(eventId, s.tenant);
        TenantContext.clear();
    }
}
