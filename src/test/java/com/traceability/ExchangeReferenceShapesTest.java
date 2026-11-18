package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExchangeMatchService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.portal.PreConnectParentImport;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exchange reference S1 (2026-11-17) — the prod reference shapes through the real services.
 *
 * <pre>
 *   s1  matchByReference: every prod shape links to its original — (b) "jamilatheboutique:#64968-R1" /
 *       "overdue-9573:#OD2394-R1" / "wjwy1a-ia:#5126", (a) "BRK-47472-EG:BRK-47472-EG-R2", (c) "#LT-22267",
 *       (d) "13313", (e) null → NO_REFERENCE. Revert check: the -R strip (resolveTrip).
 *   s2  forward linking never strips -R: a SEND "shop:#N-R1" with no shadow is NOT linked to order #N
 *       (OrderReference.resolve stays empty; resolveTrip finds it)
 *   s3  ambiguity: the two sides name two different orders → AMBIGUOUS, nothing written; two orders with the same
 *       number → AMBIGUOUS
 *   s4  any case: "#lt-22268" → "#LT-22268"
 *   s5  pre-connect filter: a post-connect exchange "wjwy1a-ia:#5126" below the lowest ingested #5139 is kept and
 *       needs its pre-connect parent (revert check: the after-':' rule); "overdue-9573:#OD2394-R1" too; a SEND with
 *       the same reference IS ignored (approved 2026-11-17); an unclear "1234:5678", a number above the lowest and a
 *       shape the tenant doesn't use are never ignored
 *   s6  the pre-connect parent import searches Shopify for the number AFTER the ':' ("64968", not the shop handle)
 *       and the exchange gets the imported parent
 *   s7  catch-up: dry run → WOULD_MATCH / WOULD_IMPORT_PARENT, nothing written, no Shopify call; apply → MATCHED /
 *       MATCHED_PRE_CONNECT; a rerun finds nothing
 *   s8  app_user isolation: another tenant's order never resolves (RLS, not just the filter); same-tenant positive
 *       control
 *   s9  an already MAPPED exchange (its EXC order holds a forward leg with the AWB) of a pre-connect order: the
 *       parent is still needed (its own EXC leg isn't "Traced's own" there) → catch-up WOULD_IMPORT_PARENT, then
 *       MATCHED_PRE_CONNECT (unmatched → matched); the ignore rule still treats the AWB as Traced's (kept); a
 *       CRP whose AWB is a return leg on an order stays Traced's own (no parent)
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeReferenceShapesTest {

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

    static final Instant CUTOFF = Instant.parse("2026-10-27T10:00:00Z");
    static final String AFTER   = "Fri Oct 30 2026 09:00:00 GMT+0000 (Coordinated Universal Time)";
    static final AtomicLong IDS = new AtomicLong(6_100_000_000L);

    @Autowired JdbcTemplate                    jdbc;
    @Autowired ObjectMapper                    mapper;
    @Autowired PlatformTransactionManager      txm;
    @Autowired EncryptionService               encryption;
    @Autowired ExchangeMatchService            matchService;
    @Autowired ShipmentLinkService             links;
    @Autowired BostaStateMapper                stateMapper;
    @Autowired PreConnectDeliveryFilter        filter;
    @Autowired PreConnectParentImport          parentImport;
    @Autowired ExchangeReferenceCatchUpService catchUp;
    @Autowired BostaWebhookJob                 webhookJob;
    @MockBean  BostaGateway                    bostaGateway;
    @MockBean  JobScheduler                    jobScheduler;
    @MockBean  ShopifyGateway                  shopify;
    @MockBean  ShopifyTokenProvider            tokens;

    private TransactionTemplate tx;
    private JdbcTemplate appUserJdbc;
    private TransactionTemplate appUserTx;

    @BeforeAll
    void setup() {
        tx = new TransactionTemplate(txm);
        TenantAwareDataSource appDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
    }

    @BeforeEach
    void stubs() {
        reset(bostaGateway, shopify, tokens);
        parentImport.clearCaches();
        when(tokens.getValidToken(any())).thenReturn("shpat-test");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── s1 ───────────────────────────────────────────────────────────────────

    @Test
    void s1_everyProdShape_linksToItsOriginal() {
        Shop s = new Shop("s1");
        Map<String, UUID> orders = Map.of(
            "jamilatheboutique:#64968-R1", s.order("#64968"),
            "overdue-9573:#OD2394-R1",      s.order("#OD2394"),
            "wjwy1a-ia:#5126",              s.order("#5126"),
            "BRK-47472-EG:BRK-47472-EG-R2", s.order("BRK-47472-EG"),
            "#LT-22267",                    s.order("#LT-22267"),
            "13313",                        s.order("13313"));
        for (Map.Entry<String, UUID> e : orders.entrySet()) {
            String tn = s.exchange("unmatched", e.getKey());
            ExchangeMatchService.ReferenceMatch m = s.as(() -> matchService.matchByReference(s.tenant, tn, false));
            assertThat(m.verdict()).as(e.getKey()).isEqualTo(ExchangeMatchService.ReferenceVerdict.MATCHED);
            assertThat(exchange(s, tn).get("matched_order_id")).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(exchange(s, tn).get("status")).isEqualTo("matched");
            assertThat(exchange(s, tn).get("match_method")).isEqualTo("reference");
        }
        String none = s.exchange("needs_mapping", null);
        assertThat(s.as(() -> matchService.matchByReference(s.tenant, none, false)).verdict())
            .isEqualTo(ExchangeMatchService.ReferenceVerdict.NO_REFERENCE);

        // needs_mapping keeps its status (the replacement still needs a person).
        UUID o = s.order("#64999");
        String nm = s.exchange("needs_mapping", "jamilatheboutique:#64999-R1");
        assertThat(s.as(() -> matchService.matchByReference(s.tenant, nm, false)).verdict())
            .isEqualTo(ExchangeMatchService.ReferenceVerdict.MATCHED);
        assertThat(exchange(s, nm).get("matched_order_id")).isEqualTo(o);
        assertThat(exchange(s, nm).get("status")).isEqualTo("needs_mapping");
    }

    // ── s2 ───────────────────────────────────────────────────────────────────

    @Test
    void s2_forwardLinking_neverStripsTheReturnSuffix() {
        Shop s = new Shop("s2");
        UUID original = s.order("#77001");
        String ref = "someshop:#77001-R1";
        assertThat(OrderReference.resolve(jdbc, s.tenant, ref, null)).as("forward rule: kept -R").isEmpty();
        assertThat(OrderReference.resolveTrip(jdbc, s.tenant, ref, null)).containsExactly(original);

        String awb = tn();
        BostaDelivery send = BostaDelivery.fromRaw(awb, raw(awb, 10, "Send", ref, AFTER));
        ShipmentLinkService.LinkResult r = s.as(() -> tx.execute(t ->
            links.tryMatchDelivery(s.tenant, awb, send, stateMapper.map(10, "SEND"))));
        assertThat(r.orderId()).as("a SEND for a Return is never the original's forward leg").isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, original)).isZero();
    }

    // ── s3 ───────────────────────────────────────────────────────────────────

    @Test
    void s3_ambiguous_neverGuesses() {
        Shop s = new Shop("s3");
        s.order("A-100");
        s.order("#200");
        String tn = s.exchange("unmatched", "A-100:#200-R1");
        assertThat(s.as(() -> matchService.matchByReference(s.tenant, tn, false)).verdict())
            .isEqualTo(ExchangeMatchService.ReferenceVerdict.AMBIGUOUS);
        assertThat(exchange(s, tn).get("matched_order_id")).isNull();
        assertThat(exchange(s, tn).get("status")).isEqualTo("unmatched");

        s.order("#300");
        s.order("300");
        String tn2 = s.exchange("unmatched", "shop:#300-R1");
        assertThat(s.as(() -> matchService.matchByReference(s.tenant, tn2, false)).verdict())
            .isEqualTo(ExchangeMatchService.ReferenceVerdict.AMBIGUOUS);
        assertThat(exchange(s, tn2).get("matched_order_id")).isNull();
    }

    // ── s4 ───────────────────────────────────────────────────────────────────

    @Test
    void s4_anyCase() {
        Shop s = new Shop("s4");
        UUID o = s.order("#LT-22268");
        String tn = s.exchange("unmatched", "#lt-22268");
        assertThat(s.as(() -> matchService.matchByReference(s.tenant, tn, false)).verdict())
            .isEqualTo(ExchangeMatchService.ReferenceVerdict.MATCHED);
        assertThat(exchange(s, tn).get("matched_order_id")).isEqualTo(o);
    }

    // ── s5 ───────────────────────────────────────────────────────────────────

    @Test
    void s5_preConnectFilter_cleanNumberOnly() {
        Shop mavis = new Shop("s5m");
        mavis.order("#5139");
        mavis.order("#5140");
        Shop overdue = new Shop("s5o");
        overdue.order("#OD2582");

        // A post-connect exchange of a pre-connect order: kept, and its parent is needed.
        BostaDelivery ex = delivery(30, "Exchange", "wjwy1a-ia:#5126");
        assertThat(ignore(mavis, ex)).isFalse();
        assertThat(needsParent(mavis, ex)).as("the number after ':' (5126 < 5139)").isTrue();
        BostaDelivery exR = delivery(30, "Exchange", "overdue-9573:#OD2394-R1");
        assertThat(ignore(overdue, exR)).isFalse();
        assertThat(needsParent(overdue, exR)).as("OD2394 < OD2582, -R stripped").isTrue();

        // A SEND of a pre-connect order, booked after the connect: ignored (approved 2026-11-17).
        assertThat(ignore(mavis, delivery(10, "Send", "wjwy1a-ia:#5126"))).isTrue();
        // Never ignored: unclear, above the lowest, a shape the tenant doesn't use, no reference.
        assertThat(ignore(mavis, delivery(10, "Send", "1234:5678"))).as("two different plain values").isFalse();
        assertThat(ignore(mavis, delivery(10, "Send", "wjwy1a-ia:#5200"))).as("above the lowest").isFalse();
        assertThat(ignore(mavis, delivery(10, "Send", "wjwy1a-ia:#XY100"))).as("prefix the tenant never used").isFalse();
        assertThat(ignore(mavis, delivery(10, "Send", null))).isFalse();
        assertThat(needsParent(mavis, delivery(30, "Exchange", "1234:5678"))).isFalse();
    }

    // ── s6 ───────────────────────────────────────────────────────────────────

    @Test
    void s6_parentImport_searchesTheNumberAfterTheColon() {
        Shop s = new Shop("s6");
        s.order("#64754");
        s.catalog();
        shopifyAnswers(s, "#64530");

        String awb = tn();
        ObjectNode raw = raw(awb, 30, "Exchange", "jamilatheboutique:#64530-R1", AFTER);
        process(s, raw);

        verify(shopify, times(1)).findOrdersByName(eq(s.shop), anyString(), eq("64530"));
        UUID parent = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#64530' " +
            "AND origin = 'portal_pre_connect'", UUID.class, s.tenant);
        assertThat(exchange(s, awb).get("matched_order_id")).isEqualTo(parent);
    }

    // ── s7 ───────────────────────────────────────────────────────────────────

    @Test
    void s7_catchUp_dryRunThenApply() {
        Shop s = new Shop("s7");
        s.order("#5139");
        UUID orig = s.order("#5300");
        s.catalog();
        String byRef = s.exchange("unmatched", "wjwy1a-ia:#5300-R1");
        String preConnect = s.exchange("unmatched", "wjwy1a-ia:#5126");
        String nothing = s.exchange("needs_mapping", "wjwy1a-ia:#5299");     // above the lowest, no such order

        List<ExchangeReferenceCatchUpService.Row> dry = catchUp.run(s.tenant, false);
        assertThat(row(dry, byRef).verdict()).isEqualTo("WOULD_MATCH");
        assertThat(row(dry, preConnect).verdict()).isEqualTo("WOULD_IMPORT_PARENT");
        assertThat(row(dry, preConnect).orderNumber()).isEqualTo("5126");
        assertThat(row(dry, nothing).verdict()).isEqualTo("SKIP");
        assertThat(row(dry, nothing).reason()).isEqualTo("NO_MATCH");
        verify(shopify, never()).findOrdersByName(anyString(), anyString(), anyString());
        assertThat(exchange(s, byRef).get("matched_order_id")).as("dry run writes nothing").isNull();
        assertThat(exchange(s, preConnect).get("matched_order_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND origin = 'portal_pre_connect'",
            Integer.class, s.tenant)).isZero();

        shopifyAnswers(s, "#5126");
        List<ExchangeReferenceCatchUpService.Row> applied = catchUp.run(s.tenant, true);
        assertThat(row(applied, byRef).verdict()).isEqualTo("MATCHED");
        assertThat(row(applied, preConnect).verdict()).isEqualTo("MATCHED_PRE_CONNECT");
        assertThat(exchange(s, byRef).get("matched_order_id")).isEqualTo(orig);
        UUID parent = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#5126' " +
            "AND origin = 'portal_pre_connect'", UUID.class, s.tenant);
        assertThat(exchange(s, preConnect).get("matched_order_id")).isEqualTo(parent);
        assertThat(exchange(s, nothing).get("matched_order_id")).isNull();

        List<ExchangeReferenceCatchUpService.Row> rerun = catchUp.run(s.tenant, true);
        assertThat(rerun.stream().filter(r -> "exchange".equals(r.kind())).map(ExchangeReferenceCatchUpService.Row::trackingNumber))
            .containsExactly(nothing);
    }

    // ── s8 ───────────────────────────────────────────────────────────────────

    @Test
    void s8_appUserIsolation() {
        Shop a = new Shop("s8a");
        Shop b = new Shop("s8b");
        UUID origA = a.order("#64970");
        String exA = a.exchange("unmatched", "jamilatheboutique:#64970-R1");
        String exB = b.exchange("unmatched", "jamilatheboutique:#64970-R1");
        ExchangeMatchService appMatch = new ExchangeMatchService(appUserJdbc, mapper);

        List<UUID> aUnderB = TenantContext.runAs(b.tenant, () -> appUserTx.execute(t ->
            OrderReference.resolveTrip(appUserJdbc, a.tenant, "jamilatheboutique:#64970-R1", null)));
        assertThat(aUnderB).as("RLS, not just the filter").isEmpty();
        ExchangeMatchService.ReferenceMatch bOwn = TenantContext.runAs(b.tenant, () -> appUserTx.execute(t ->
            appMatch.matchByReference(b.tenant, exB, false)));
        assertThat(bOwn.verdict()).as("B has no such order").isEqualTo(ExchangeMatchService.ReferenceVerdict.NO_MATCH);
        ExchangeMatchService.ReferenceMatch cross = TenantContext.runAs(b.tenant, () -> appUserTx.execute(t ->
            appMatch.matchByReference(a.tenant, exA, false)));
        assertThat(cross.verdict()).isEqualTo(ExchangeMatchService.ReferenceVerdict.NOT_ELIGIBLE);
        assertThat(exchange(a, exA).get("matched_order_id")).isNull();

        ExchangeMatchService.ReferenceMatch own = TenantContext.runAs(a.tenant, () -> appUserTx.execute(t ->
            appMatch.matchByReference(a.tenant, exA, false)));
        assertThat(own.verdict()).as("positive control").isEqualTo(ExchangeMatchService.ReferenceVerdict.MATCHED);
        assertThat(exchange(a, exA).get("matched_order_id")).isEqualTo(origA);
        assertThat(exchange(b, exB).get("matched_order_id")).isNull();
    }

    // ── s9 ───────────────────────────────────────────────────────────────────

    @Test
    void s9_mappedExchange_ownExcLeg_stillGetsItsPreConnectParent() {
        Shop s = new Shop("s9");
        s.order("#5139");
        s.catalog();
        String awb = s.exchange("unmatched", "wjwy1a-ia:#4810");
        UUID exc = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id", UUID.class, s.tenant, s.store,
            "internal:exchange:" + awb, "EXC-" + awb);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", s.tenant, exc, awb);
        jdbc.update("UPDATE exchanges SET outbound_order_id = ? WHERE tenant_id = ? AND tracking_number = ?", exc, s.tenant, awb);
        BostaDelivery d = BostaDelivery.fromRaw(awb, raw(awb, 30, "Exchange", "wjwy1a-ia:#4810", AFTER));

        assertThat(needsParent(s, d)).isTrue();
        assertThat(ignore(s, d)).as("the ignore rule still sees the AWB as Traced's").isFalse();

        assertThat(row(catchUp.run(s.tenant, false), awb).verdict()).isEqualTo("WOULD_IMPORT_PARENT");
        shopifyAnswers(s, "#4810");
        assertThat(row(catchUp.run(s.tenant, true), awb).verdict()).isEqualTo("MATCHED_PRE_CONNECT");
        UUID parent = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#4810' " +
            "AND origin = 'portal_pre_connect'", UUID.class, s.tenant);
        assertThat(exchange(s, awb).get("matched_order_id")).isEqualTo(parent);
        assertThat(exchange(s, awb).get("status")).isEqualTo("matched");

        // A CRP whose AWB is already a return leg on an order: Traced's own, no parent import.
        UUID other = s.order("#5200");
        String crp = tn();
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created', 'return')", s.tenant, other, crp);
        assertThat(needsParent(s, BostaDelivery.fromRaw(crp, raw(crp, 25, "Customer Return Pickup", "wjwy1a-ia:#4811", AFTER))))
            .isFalse();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID();
        final String shop;
        UUID variant;
        String variantGid;

        Shop(String name) {
            shop = "ers-" + name + "-" + tenant.toString().substring(0, 8) + ".myshopify.com";
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "ERS-" + name);
            jdbc.update("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main Warehouse', true)", tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryption.encrypt("key-" + tenant));
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, tenant, shop, Timestamp.from(CUTOFF));
        }

        UUID order(String number) {
            return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, 'new'::order_status, ?) RETURNING id", UUID.class, tenant, store,
                "gid://shopify/Order/" + IDS.incrementAndGet(), number, Timestamp.from(CUTOFF.plusSeconds(3600)));
        }

        void catalog() {
            UUID product = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Dress', 'active')",
                product, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            variant = UUID.randomUUID();
            variantGid = "gid://shopify/ProductVariant/" + IDS.incrementAndGet();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
                variant, tenant, product, variantGid, "DR-" + variant);
        }

        /** A dashboard exchanges row (type 30, one item each way, created after the cutoff). */
        String exchange(String status, String ref) {
            String tn = tn();
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, raw) " +
                "VALUES (?, ?, ?, 'Out', 'In', ?::jsonb)", tenant, tn, status, raw(tn, 30, "Exchange", ref, AFTER).toString());
            return tn;
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }
    }

    static String tn() { return String.valueOf(IDS.incrementAndGet()); }

    ObjectNode raw(String tn, int typeCode, String typeValue, String ref, String createdAt) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        if (ref != null) raw.put("businessReference", ref); else raw.putNull("businessReference");
        raw.put("createdAt", createdAt);
        raw.put("updatedAt", "2026-10-30T09:00:00.000Z");
        raw.put("cod", 0);
        raw.putObject("type").put("code", typeCode).put("value", typeValue);
        raw.putObject("state").put("code", 10).put("value", "Pickup requested");
        if (typeCode == 30) {
            raw.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Exchange item");
            raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Old item");
        }
        return raw;
    }

    BostaDelivery delivery(int typeCode, String typeValue, String ref) {
        String tn = tn();
        return BostaDelivery.fromRaw(tn, raw(tn, typeCode, typeValue, ref, AFTER));
    }

    boolean ignore(Shop s, BostaDelivery d) {
        return Boolean.TRUE.equals(s.as(() -> tx.execute(t -> filter.shouldIgnore(s.tenant, d.trackingNumber(), d))));
    }

    boolean needsParent(Shop s, BostaDelivery d) {
        return Boolean.TRUE.equals(s.as(() -> tx.execute(t -> filter.needsPreConnectParent(s.tenant, d.trackingNumber(), d))));
    }

    /** Shopify answers findOrdersByName with one pre-connect order {@code name} of one line of the shop's variant. */
    void shopifyAnswers(Shop s, String name) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("name", name);
        ArrayNode edges = raw.putObject("lineItems").putArray("edges");
        ObjectNode node = edges.addObject().putObject("node");
        node.put("id", "gid://shopify/LineItem/" + IDS.incrementAndGet());
        node.put("quantity", 1);
        node.put("currentQuantity", 1);
        node.putObject("variant").put("id", s.variantGid);
        ShopifyGateway.Order order = new ShopifyGateway.Order(
            "gid://shopify/Order/" + IDS.incrementAndGet(), name, null, null, null, "PENDING", List.of("Cash on Delivery (COD)"),
            new BigDecimal("500.00"), List.of(), CUTOFF.minusSeconds(86_400 * 5), raw);
        when(shopify.findOrdersByName(eq(s.shop), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(order), true));
    }

    void process(Shop s, ObjectNode raw) {
        String tracking = raw.path("trackingNumber").asText();
        when(bostaGateway.fetchDelivery(eq("key-" + s.tenant), eq(tracking))).thenReturn(BostaDelivery.fromRaw(tracking, raw));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", tracking);
        payload.put("state", 10);
        payload.put("updatedAt", raw.path("updatedAt").asText());
        Long id = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, s.tenant, payload.toString());
        webhookJob.process(id, s.tenant);
        TenantContext.clear();
    }

    Map<String, Object> exchange(Shop s, String tn) {
        return jdbc.queryForMap("SELECT status, matched_order_id, match_method FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            s.tenant, tn);
    }

    static ExchangeReferenceCatchUpService.Row row(List<ExchangeReferenceCatchUpService.Row> rows, String tn) {
        return rows.stream().filter(r -> "exchange".equals(r.kind()) && tn.equals(r.trackingNumber())).findFirst().orElseThrow();
    }
}
