package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ExchangeMatchService;
import com.traceability.inventory.ExchangeService;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reference linking for exchanges and customer-return pickups (2026-10-05).
 *
 *   r1  OrderReference: every reference shape — "BRK-n-EG:BRK-n-EG-Rk" (part before ':'), "#n" and "n",
 *       "store:#n" (part after ':'), null; internal EXC-… orders never match; Shopify id fallback
 *   r2  one / zero / several matches
 *   r3  a CRP with a ':' reference links as a RETURN leg of the original through the normal pipeline
 *   r4  an exchange still needs_mapping gets its original order (status untouched); mapping it → matched
 *   r5  mapped / unmatched → matched by reference; ambiguous → needs_confirmation, nothing set; no match →
 *       the phone matcher decides as before
 *   r6  a Traced-booked exchange row (return_request_id) is never touched
 *   r7  a pre-connect reference (no such order) stays ignored — exchange and CRP
 *   r8  fulfillment linking: the AWB is a shipment on the EXC order of an exchange matched to / referencing
 *       this order → linked, "linked via exchange EXC-…", no Bosta call, no new shipment, no exception;
 *       an exchange of ANOTHER order → conflict as before
 *   r9  catch-up: dry run writes nothing; apply links exchange, CRP and fulfillment; rerun is a no-op
 *   r10 app_user isolation: the resolver and the exchange step only ever see the caller's tenant
 *   r11 the shipping badge never reads a fulfillment linked via an exchange as "Bosta tracking not linked"
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeReferenceLinkTest {

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

    static final String CREATED = "Mon Oct 05 2026 07:56:20 GMT+0000 (Coordinated Universal Time)";

    @Autowired JdbcTemplate                    jdbc;
    @Autowired ObjectMapper                    mapper;
    @Autowired PlatformTransactionManager      txm;
    @Autowired EncryptionService               encryptionService;
    @Autowired ExchangeMatchService            matchService;
    @Autowired ExchangeService                 exchangeService;
    @Autowired BostaFulfillmentLinkService     linkService;
    @Autowired ExchangeReferenceCatchUpService catchUp;
    @Autowired BostaWebhookJob                 webhookJob;
    @Autowired ExceptionService                exceptionService;
    @MockBean  BostaGateway                    bostaGateway;
    @MockBean  JobScheduler                    jobScheduler;

    record Shop(UUID tenantId, UUID storeId) {}

    private TransactionTemplate tx;
    private JdbcTemplate appUserJdbc;
    private TransactionTemplate appUserTx;

    @BeforeAll
    void setup() {
        tx = new TransactionTemplate(txm);
        TenantAwareDataSource appDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc = new JdbcTemplate(appDs);
        PlatformTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        appUserTx = new TransactionTemplate(appTxm);
    }

    @BeforeEach void resetMocks() { reset(bostaGateway, jobScheduler); }
    @AfterEach  void clear()      { TenantContext.clear(); }

    // ── r1 / r2 ──────────────────────────────────────────────────────────────

    @Test
    void r1_everyReferenceShape() {
        Shop s = shop("r1");
        UUID brk = order(s, "BRK-44868-EG", 1001);
        UUID hashed = order(s, "#2212115474", 1002);
        UUID plain = order(s, "70370", 1003);
        UUID blnco = order(s, "#515956", 1004);
        UUID exc = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, placed_at) " +
            "VALUES (?, ?, 'internal:exchange:5550001', 'EXC-5550001', now()) RETURNING id", UUID.class, s.tenantId(), s.storeId());

        assertThat(resolve(s, "BRK-44868-EG:BRK-44868-EG-R1", null)).containsExactly(brk);
        assertThat(resolve(s, "BRK-44868-EG", null)).containsExactly(brk);
        assertThat(resolve(s, "#2212115474", null)).containsExactly(hashed);
        assertThat(resolve(s, "2212115474", null)).containsExactly(hashed);
        assertThat(resolve(s, "#70370", null)).containsExactly(plain);
        assertThat(resolve(s, "blncoeg:#515956", null)).containsExactly(blnco);
        assertThat(resolve(s, null, null)).isEmpty();
        assertThat(resolve(s, "  ", null)).isEmpty();
        assertThat(resolve(s, "EXC-5550001", null)).as("an internal order never matches").isEmpty();
        assertThat(resolve(s, null, "1004")).as("Shopify id fallback").containsExactly(blnco);
        assertThat(exc).isNotNull();
    }

    @Test
    void r2_oneZeroSeveral() {
        Shop s = shop("r2");
        UUID one = order(s, "BRK-50001-EG", 2001);
        order(s, "#9001", 2002);
        order(s, "9001", 2003);   // same number with and without '#': two orders

        assertThat(resolve(s, "BRK-50001-EG:BRK-50001-EG-R2", null)).containsExactly(one);
        assertThat(resolve(s, "BRK-49999-EG:BRK-49999-EG-R1", null)).isEmpty();
        assertThat(resolve(s, "#9001", null)).hasSize(2);
    }

    // ── r3 ───────────────────────────────────────────────────────────────────

    @Test
    void r3_crpWithColonReference_linksAsReturnLeg() {
        Shop s = shop("r3");
        UUID original = order(s, "BRK-44903-EG", 3001);
        forwardShipment(s, original, "3100000001", "delivered");
        delivery(s, "3100000002", 25, "Customer Return Pickup", 10, "BRK-44903-EG:BRK-44903-EG-R1");

        long ev = event(s, "3100000002", 10);
        webhookJob.process(ev, s.tenantId());

        Map<String, Object> leg = jdbc.queryForMap(
            "SELECT order_id, shipment_leg FROM shipments WHERE tracking_number = '3100000002'");
        assertThat(leg.get("order_id")).isEqualTo(original);
        assertThat(leg.get("shipment_leg")).isEqualTo("return");
        assertThat(count("SELECT COUNT(*) FROM piece_events WHERE order_id = '" + original + "'")).isZero();
    }

    // ── r4 / r5 / r6 ─────────────────────────────────────────────────────────

    @Test
    void r4_needsMapping_getsOriginal_thenMappingMakesItMatched() {
        Shop s = shop("r4");
        UUID original = order(s, "BRK-44868-EG", 4001);
        UUID[] variants = variants(s);
        UUID ex = exchange(s, "4100000001", "needs_mapping", "BRK-44868-EG:BRK-44868-EG-R1");

        TenantContext.runAs(s.tenantId(), () -> tx.execute(t -> { matchService.attemptMatch("4100000001"); return null; }));
        Map<String, Object> row = exchangeRow(ex);
        assertThat(row.get("matched_order_id")).isEqualTo(original);
        assertThat(row.get("match_method")).isEqualTo("reference");
        assertThat(row.get("status")).as("still waits for its replacement").isEqualTo("needs_mapping");

        Map<String, Object> mapped = TenantContext.runAs(s.tenantId(), () ->
            exchangeService.map(ex, variants[0], variants[1]));
        assertThat(mapped.get("status")).isEqualTo("matched");
        assertThat(exchangeRow(ex).get("status")).isEqualTo("matched");
        assertThat(exchangeRow(ex).get("matched_order_id")).isEqualTo(original);
    }

    @Test
    void r5_mappedUnmatchedMatched_ambiguousNeedsConfirmation_noMatchFallsThrough() {
        Shop s = shop("r5");
        UUID o1 = order(s, "BRK-45001-EG", 5001);
        UUID o2 = order(s, "BRK-45002-EG", 5002);
        order(s, "#7777", 5003);
        order(s, "7777", 5004);
        UUID mapped = exchange(s, "5100000001", "mapped", "BRK-45001-EG:BRK-45001-EG-R1");
        UUID unmatched = exchange(s, "5100000002", "unmatched", "BRK-45002-EG:BRK-45002-EG-R2");
        UUID ambiguous = exchange(s, "5100000003", "mapped", "#7777");
        UUID none = exchange(s, "5100000004", "mapped", "BRK-40000-EG:BRK-40000-EG-R1");

        for (String tn : List.of("5100000001", "5100000002", "5100000003", "5100000004")) {
            TenantContext.runAs(s.tenantId(), () -> tx.execute(t -> { matchService.attemptMatch(tn); return null; }));
        }
        assertThat(exchangeRow(mapped)).containsEntry("status", "matched").containsEntry("matched_order_id", o1)
            .containsEntry("match_method", "reference");
        assertThat(exchangeRow(unmatched)).containsEntry("status", "matched").containsEntry("matched_order_id", o2);
        assertThat(exchangeRow(ambiguous)).containsEntry("status", "needs_confirmation");
        assertThat(exchangeRow(ambiguous).get("matched_order_id")).isNull();
        // No reference match → the phone matcher (no phone in this raw → 'unmatched', as before).
        assertThat(exchangeRow(none)).containsEntry("status", "unmatched");
        assertThat(exchangeRow(none).get("matched_order_id")).isNull();
    }

    @Test
    void r6_tracedBookedRow_untouched() {
        Shop s = shop("r6");
        UUID original = order(s, "BRK-46001-EG", 6001);
        UUID request = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, reference) " +
            "VALUES (?, ?, 'exchange', 'RR-R6AB') RETURNING id", UUID.class, s.tenantId(), original);
        UUID ex = exchange(s, "6100000001", "needs_mapping", "BRK-46001-EG");
        jdbc.update("UPDATE exchanges SET return_request_id = ? WHERE id = ?", request, ex);

        ExchangeMatchService.ReferenceMatch m = TenantContext.runAs(s.tenantId(), () ->
            tx.execute(t -> matchService.matchByReference(s.tenantId(), "6100000001", false)));
        assertThat(m.verdict()).isEqualTo(ExchangeMatchService.ReferenceVerdict.NOT_ELIGIBLE);
        TenantContext.runAs(s.tenantId(), () -> tx.execute(t -> { matchService.attemptMatch("6100000001"); return null; }));
        assertThat(exchangeRow(ex).get("matched_order_id")).isNull();
        assertThat(exchangeRow(ex).get("status")).isEqualTo("needs_mapping");
        assertThat(catchUp.run(s.tenantId(), true)).noneMatch(r -> r.trackingNumber().equals("6100000001"));
    }

    // ── r7 ───────────────────────────────────────────────────────────────────

    @Test
    void r7_preConnectReference_staysIgnored() {
        Shop s = shop("r7");
        order(s, "BRK-44900-EG", 7001);
        UUID ex = exchange(s, "7100000001", "unmatched", "BRK-44604-EG:BRK-44604-EG-R1");   // pre-connect: no order
        unlinkedCrp(s, "7100000002", "BRK-44605-EG:BRK-44605-EG-R1");

        List<ExchangeReferenceCatchUpService.Row> rows = catchUp.run(s.tenantId(), true);

        assertThat(rows).filteredOn(r -> r.trackingNumber().equals("7100000001")).singleElement()
            .satisfies(r -> { assertThat(r.verdict()).isEqualTo("SKIP"); assertThat(r.reason()).isEqualTo("NO_MATCH"); });
        assertThat(rows).filteredOn(r -> r.trackingNumber().equals("7100000002")).singleElement()
            .satisfies(r -> assertThat(r.reason()).isEqualTo("reference matches no order"));
        assertThat(exchangeRow(ex).get("matched_order_id")).isNull();
        assertThat(count("SELECT COUNT(*) FROM webhook_events WHERE tenant_id = '" + s.tenantId() + "'")).isZero();
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
    }

    // ── r8 ───────────────────────────────────────────────────────────────────

    @Test
    void r8_fulfillmentLinkedViaExchange_otherwiseConflict() {
        Shop s = shop("r8");
        UUID original = order(s, "BRK-44868-EG", 8001);
        forwardShipment(s, original, "8100000001", "delivered");
        UUID exc = excOrder(s, "8854860251", "BRK-44868-EG:BRK-44868-EG-R1", null);
        tracking(s, original, "8854860251");

        BostaFulfillmentLinkService.Result r = linkService.attempt(s.tenantId(), original, "8854860251", false);

        assertThat(r.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(r.reason()).isEqualTo("linked via exchange EXC-8854860251");
        Map<String, Object> ft = trackingRow(s, original, "8854860251");
        assertThat(ft.get("link_status")).isEqualTo("linked");
        assertThat(ft.get("link_reason")).isEqualTo("linked via exchange EXC-8854860251");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE tracking_number = '8854860251'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT order_id FROM shipments WHERE tracking_number = '8854860251'", UUID.class))
            .isEqualTo(exc);
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
        assertThat(TenantContext.runAs(s.tenantId(), () -> exceptionService.detectAllOpen()).stream()
            .filter(e -> "fulfillment_link_problem".equals(e.get("type")))).isEmpty();

        // An exchange of ANOTHER order: still a conflict.
        UUID other = order(s, "BRK-44869-EG", 8002);
        forwardShipment(s, other, "8100000002", "delivered");
        excOrder(s, "8854860252", "BRK-44868-EG:BRK-44868-EG-R2", null);
        tracking(s, other, "8854860252");
        BostaFulfillmentLinkService.Result r2 = linkService.attempt(s.tenantId(), other, "8854860252", false);
        assertThat(r2.verdict()).isEqualTo(BostaFulfillmentLinkService.Verdict.SKIP);
        assertThat(trackingRow(s, other, "8854860252").get("link_status")).isEqualTo("conflict");
    }

    // ── r9 ───────────────────────────────────────────────────────────────────

    @Test
    void r9_catchUp_dryRunWritesNothing_applyLinks_rerunNoOp() {
        Shop s = shop("r9");
        UUID origEx = order(s, "BRK-44843-EG", 9001);
        forwardShipment(s, origEx, "9100000001", "delivered");
        UUID exc = excOrder(s, "8982134649", "BRK-44843-EG:BRK-44843-EG-R2", "unmatched");
        tracking(s, origEx, "8982134649");
        jdbc.update("UPDATE order_fulfillment_tracking SET link_status = 'skipped', " +
            "link_reason = 'order already has an active forward leg (9100000001)' WHERE tracking_number = '8982134649'");
        UUID origCrp = order(s, "BRK-44985-EG", 9002);
        forwardShipment(s, origCrp, "9100000002", "delivered");
        unlinkedCrp(s, "6994431795", "BRK-44985-EG:BRK-44985-EG-R1");
        delivery(s, "6994431795", 25, "Customer Return Pickup", 10, "BRK-44985-EG:BRK-44985-EG-R1");
        // An exchange matched earlier (by hand) whose original's fulfillment was flagged 'conflict' (BRK-44868's case).
        UUID origConflict = order(s, "BRK-44868-EG", 9003);
        forwardShipment(s, origConflict, "9100000003", "delivered");
        UUID exc2 = excOrder(s, "8954860251", "BRK-44868-EG:BRK-44868-EG-R1", "matched");
        jdbc.update("UPDATE exchanges SET matched_order_id = ?, match_method = 'manual' WHERE outbound_order_id = ?",
            origConflict, exc2);
        tracking(s, origConflict, "8954860251");
        jdbc.update("UPDATE order_fulfillment_tracking SET link_status = 'conflict', " +
            "link_reason = 'tracking number is already a shipment on order EXC-8954860251' WHERE tracking_number = '8954860251'");

        String before = snapshot(s);
        List<ExchangeReferenceCatchUpService.Row> dry = catchUp.run(s.tenantId(), false);
        assertThat(dry).extracting(ExchangeReferenceCatchUpService.Row::kind, ExchangeReferenceCatchUpService.Row::verdict)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("exchange", "WOULD_MATCH"),
                org.assertj.core.groups.Tuple.tuple("crp", "WOULD_LINK"),
                org.assertj.core.groups.Tuple.tuple("fulfillment", "WOULD_LINK"),
                org.assertj.core.groups.Tuple.tuple("fulfillment", "WOULD_LINK"));
        assertThat(snapshot(s)).as("dry run writes nothing").isEqualTo(before);
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());

        List<ExchangeReferenceCatchUpService.Row> applied = catchUp.run(s.tenantId(), true);
        assertThat(applied).extracting(ExchangeReferenceCatchUpService.Row::kind, ExchangeReferenceCatchUpService.Row::verdict)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("exchange", "MATCHED"),
                org.assertj.core.groups.Tuple.tuple("crp", "LINKED"),
                // 8982134649's fulfillment was already linked by the exchange step (the live path) — only
                // the earlier-matched exchange's conflict row is left for the fulfillment pass.
                org.assertj.core.groups.Tuple.tuple("fulfillment", "LINKED"));
        assertThat(applied).filteredOn(r -> r.kind().equals("fulfillment")).singleElement()
            .satisfies(r -> assertThat(r.trackingNumber()).isEqualTo("8954860251"));
        Map<String, Object> exRow = jdbc.queryForMap(
            "SELECT status, matched_order_id, match_method FROM exchanges WHERE outbound_order_id = ?", exc);
        assertThat(exRow).containsEntry("status", "matched").containsEntry("matched_order_id", origEx)
            .containsEntry("match_method", "reference");
        assertThat(jdbc.queryForMap("SELECT order_id, shipment_leg FROM shipments WHERE tracking_number = '6994431795'"))
            .containsEntry("order_id", origCrp).containsEntry("shipment_leg", "return");
        assertThat(jdbc.queryForObject("SELECT resolved FROM unlinked_bosta_deliveries WHERE tracking_number = '6994431795'",
            Boolean.class)).isTrue();
        assertThat(trackingRow(s, origEx, "8982134649")).containsEntry("link_status", "linked")
            .containsEntry("link_reason", "linked via exchange EXC-8982134649");
        assertThat(trackingRow(s, origConflict, "8954860251")).containsEntry("link_status", "linked")
            .containsEntry("link_reason", "linked via exchange EXC-8954860251");
        assertThat(count("SELECT COUNT(*) FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.tenant_id = '" + s.tenantId() + "'")).as("no ledger writes").isZero();

        String afterApply = snapshot(s);
        assertThat(catchUp.run(s.tenantId(), true)).as("nothing left to do").isEmpty();
        assertThat(snapshot(s)).isEqualTo(afterApply);
    }

    // ── r10 ──────────────────────────────────────────────────────────────────

    @Test
    void r10_appUserIsolation() {
        Shop a = shop("r10a");
        Shop b = shop("r10b");
        UUID origA = order(a, "BRK-47001-EG", 10001);
        UUID exA = exchange(a, "1010000001", "mapped", "BRK-47001-EG:BRK-47001-EG-R1");
        order(b, "BRK-47002-EG", 10002);
        ExchangeMatchService appMatch = new ExchangeMatchService(appUserJdbc, mapper);

        // B can't see A's order through the reference, nor A's exchange.
        List<UUID> seenByB = TenantContext.runAs(b.tenantId(), () -> appUserTx.execute(t ->
            OrderReference.resolve(appUserJdbc, b.tenantId(), "BRK-47001-EG", null)));
        assertThat(seenByB).isEmpty();
        List<UUID> aIdUnderB = TenantContext.runAs(b.tenantId(), () -> appUserTx.execute(t ->
            OrderReference.resolve(appUserJdbc, a.tenantId(), "BRK-47001-EG", null)));
        assertThat(aIdUnderB).as("RLS, not just the filter").isEmpty();
        ExchangeMatchService.ReferenceMatch cross = TenantContext.runAs(b.tenantId(), () -> appUserTx.execute(t ->
            appMatch.matchByReference(a.tenantId(), "1010000001", false)));
        assertThat(cross.verdict()).isEqualTo(ExchangeMatchService.ReferenceVerdict.NOT_ELIGIBLE);
        assertThat(exchangeRow(exA).get("matched_order_id")).isNull();

        // Positive control: A, as app_user, matches its own.
        ExchangeMatchService.ReferenceMatch own = TenantContext.runAs(a.tenantId(), () -> appUserTx.execute(t ->
            appMatch.matchByReference(a.tenantId(), "1010000001", false)));
        assertThat(own.verdict()).isEqualTo(ExchangeMatchService.ReferenceVerdict.MATCHED);
        assertThat(exchangeRow(exA).get("matched_order_id")).isEqualTo(origA);
    }

    // ── r11 ──────────────────────────────────────────────────────────────────

    @Test
    void r11_badge_linkedViaExchange_isNoLinkProblem() {
        Shop s = shop("r11");
        UUID original = order(s, "BRK-48001-EG", 11001);   // no live forward leg: the badge reaches the link check
        excOrder(s, "1110000001", "BRK-48001-EG:BRK-48001-EG-R1", null);
        tracking(s, original, "1110000001");
        jdbc.update("UPDATE order_fulfillment_tracking SET first_seen_at = now() - INTERVAL '3 hours' " +
            "WHERE tracking_number = '1110000001'");
        String problemSql = "SELECT " + com.traceability.fulfillment.OrderShippingBadge.bostaLinkProblemSql(60) +
            " FROM orders o WHERE o.id = ?";
        assertThat(jdbc.queryForObject(problemSql, Boolean.class, original)).as("before: unlinked > 1 h").isTrue();

        assertThat(linkService.attempt(s.tenantId(), original, "1110000001", false).verdict())
            .isEqualTo(BostaFulfillmentLinkService.Verdict.LINKED);
        assertThat(jdbc.queryForObject(problemSql, Boolean.class, original)).as("linked via exchange").isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Shop shop(String tag) {
        UUID tenantId = UUID.randomUUID(), storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "ERL-" + tag);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', ?)", storeId, tenantId,
            "erl-" + tenantId.toString().substring(0, 8) + ".myshopify.com", Timestamp.from(Instant.parse("2026-09-29T10:51:17Z")));
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, ?, 'active')", tenantId, encryptionService.encrypt("key-" + tenantId), "wh-" + tenantId);
        return new Shop(tenantId, storeId);
    }

    private UUID order(Shop s, String number, long shopifyId) {
        return jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, ?) RETURNING id", UUID.class,
            s.tenantId(), s.storeId(), "gid://shopify/Order/" + s.tenantId().toString().substring(0, 4) + shopifyId, number,
            Timestamp.from(Instant.parse("2026-09-30T12:00:00Z")));
    }

    private List<UUID> resolve(Shop s, String ref, String shopifyId) {
        String id = shopifyId == null ? null : s.tenantId().toString().substring(0, 4) + shopifyId;
        return OrderReference.resolve(jdbc, s.tenantId(), ref, id);
    }

    private void forwardShipment(Shop s, UUID order, String tn, String state) {
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')", s.tenantId(), order, tn, state);
    }

    private ObjectNode raw(String tn, int typeCode, String typeValue, int state, String ref) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "bosta-" + tn);
        raw.put("trackingNumber", tn);
        if (ref != null) raw.put("businessReference", ref); else raw.putNull("businessReference");
        raw.put("createdAt", CREATED);
        raw.put("updatedAt", "2026-10-05T08:00:00.000Z");
        raw.put("cod", 0);
        if (typeCode == 30) raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1);
        raw.putObject("type").put("code", typeCode).put("value", typeValue);
        raw.putObject("state").put("code", state).put("value", "Pickup requested");
        return raw;
    }

    private void delivery(Shop s, String tn, int typeCode, String typeValue, int state, String ref) {
        when(bostaGateway.fetchDelivery(eq("key-" + s.tenantId()), eq(tn)))
            .thenReturn(BostaDelivery.fromRaw(tn, raw(tn, typeCode, typeValue, state, ref)));
    }

    private long event(Shop s, String tn, int state) {
        ObjectNode p = mapper.createObjectNode();
        p.put("trackingNumber", tn);
        p.put("state", state);
        p.put("type", "CUSTOMER_RETURN_PICKUP");
        p.put("updatedAt", "2026-10-05T08:00:00.000Z");
        return jdbc.queryForObject("INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, s.tenantId(), p.toString());
    }

    private UUID exchange(Shop s, String tn, String status, String ref) {
        ObjectNode raw = raw(tn, 30, "Exchange", 10, ref);
        return jdbc.queryForObject("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, " +
            "inbound_description, raw) VALUES (?, ?, ?, 'Out', 'In', ?::jsonb) RETURNING id",
            UUID.class, s.tenantId(), tn, status, raw.toString());
    }

    /** An auto-mapped dashboard exchange: EXC-<tn> order + its forward leg + the exchanges row. */
    private UUID excOrder(Shop s, String tn, String ref, String status) {
        UUID exc = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, placed_at) " +
            "VALUES (?, ?, ?, ?, now()) RETURNING id", UUID.class, s.tenantId(), s.storeId(),
            "internal:exchange:" + tn, "EXC-" + tn);
        forwardShipment(s, exc, tn, "created");
        UUID ex = exchange(s, tn, status == null ? "mapped" : status, ref);
        jdbc.update("UPDATE exchanges SET outbound_order_id = ?, auto_matched = true WHERE id = ?", exc, ex);
        return exc;
    }

    private UUID[] variants(Shop s) {
        UUID product = UUID.randomUUID(), in = UUID.randomUUID(), out = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Shirt')",
            product, s.tenantId(), s.storeId(), "P-" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            in, s.tenantId(), product, "V-" + in, "SKU-" + in);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'L', ?)",
            out, s.tenantId(), product, "V-" + out, "SKU-" + out);
        return new UUID[]{out, in};
    }

    private void unlinkedCrp(Shop s, String tn, String ref) {
        jdbc.update("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "  bosta_order_type, match_reason, resolved, raw) VALUES (?, ?, ?, 10, 'CUSTOMER RETURN PICKUP', 'NO_MATCH', false, ?::jsonb)",
            s.tenantId(), tn, ref, raw(tn, 25, "Customer Return Pickup", 10, ref).toString());
    }

    private void tracking(Shop s, UUID order, String tn) {
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, " +
            "carrier_class, shopify_fulfillment_id, fulfillment_status) VALUES (?, ?, ?, 'Bosta', 'bosta', '1', 'success')",
            s.tenantId(), order, tn);
    }

    private Map<String, Object> trackingRow(Shop s, UUID order, String tn) {
        return jdbc.queryForMap("SELECT link_status, link_reason FROM order_fulfillment_tracking " +
            "WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?", s.tenantId(), order, tn);
    }

    private Map<String, Object> exchangeRow(UUID id) {
        return jdbc.queryForMap("SELECT status, matched_order_id, match_method FROM exchanges WHERE id = ?", id);
    }

    /** Everything the catch-up could write, for this tenant. */
    private String snapshot(Shop s) {
        return jdbc.queryForObject(
            "SELECT concat_ws(' | ', " +
            " (SELECT string_agg(tracking_number || ':' || status || ':' || coalesce(matched_order_id::text, '-'), ',' ORDER BY tracking_number) FROM exchanges WHERE tenant_id = ?), " +
            " (SELECT string_agg(tracking_number || ':' || resolved, ',' ORDER BY tracking_number) FROM unlinked_bosta_deliveries WHERE tenant_id = ?), " +
            " (SELECT string_agg(tracking_number || ':' || coalesce(link_status, '-') || ':' || coalesce(link_reason, '-'), ',' ORDER BY tracking_number) FROM order_fulfillment_tracking WHERE tenant_id = ?), " +
            " (SELECT string_agg(tracking_number || ':' || order_id || ':' || internal_state, ',' ORDER BY tracking_number) FROM shipments WHERE tenant_id = ?), " +
            " (SELECT count(*)::text FROM webhook_events WHERE tenant_id = ?))",
            String.class, s.tenantId(), s.tenantId(), s.tenantId(), s.tenantId(), s.tenantId());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
