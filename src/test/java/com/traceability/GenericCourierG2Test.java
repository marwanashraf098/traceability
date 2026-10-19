package com.traceability;

import com.traceability.courier.CourierSettingsService;
import com.traceability.courier.GenericCatchUp;
import com.traceability.courier.GenericCatchUpJob;
import com.traceability.courier.GenericTrackingKey;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Non-Bosta couriers G2 — waybill matching, the queue's AWB link step, the pack-time conflict alert, Settings →
 * Couriers (switch, default carrier, catch-up), "Test a waybill", QP Express.
 *
 *   k*  two-key scan resolution (WaybillScanKeys) in waybill mode: every carrier shape opens the right order,
 *       Arabic layout is refused, Bosta + generic → AMBIGUOUS, piece codes still win first
 *   p*  pack session on a Bosta tenant switched on (the Femine case): a Wijha order packs by its waybill, the Bosta
 *       order next to it exactly as before; a generic waybill scanned while packing is refused
 *   q*  queue AWB link step: generic verify / mismatch; a Bosta AWB on a packed generic order → 409 + a stored HIGH
 *       alert that survives the rollback; the pick screen's order detail
 *   c*  catch-up: eligibility, dry run = run = the prod SQL script, idempotent
 *   s*  settings: roles, audit, switch-off leaves shipments alone and their orders packable
 *   t*  Test a waybill: every verdict, read-only, roles
 *   qp  QP Express is a named carrier in Java and SQL
 * (Flag-off / no-generic isolation: GenericG2IsolationGoldenTest; analytics: GenericAnalyticsIsolationTest.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericCourierG2Test {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate    rest;
    @Autowired JdbcTemplate        jdbc;
    @Autowired JwtService          jwt;
    @Autowired WaybillResolver     resolver;
    @Autowired PackSessionStore    packStore;
    @Autowired PackSessionService  packService;
    @Autowired FulfillService      fulfill;
    @Autowired ShipmentLinkService link;
    @Autowired ExceptionService    exceptions;
    @Autowired GenericCatchUpJob   catchUpJob;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    @BeforeEach void resetMocks() { reset(jobScheduler, bostaGateway); }
    @AfterEach  void clear() { TenantContext.clear(); }

    /** A store: fixtures + a packer + a variant. bosta = an active Bosta account; on = the G2 switch. */
    final class Shop {
        final PackFixtures f;
        final UUID packer, variant;
        Shop(String name, boolean bosta, boolean on) {
            f = new PackFixtures(jdbc, name);
            jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan', generic_courier_enabled = ? WHERE id = ?", on, f.tenant);
            if (bosta) {
                jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (?, 'bosta', 'x', ?, 'active')", f.tenant, "wh-" + f.tenant);
            }
            packer = f.user("Mona", "worker");
            variant = f.variant("Dress", "DR-" + name, null);
        }
        UUID order(String number) {
            UUID id = f.order(number, 1);
            f.item(id, variant, 1);
            return id;
        }
        /** A generic forward leg as the G1 writer stores it. */
        UUID generic(UUID order, String tn, String carrier) {
            return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, " +
                "internal_state, source, shopify_fulfillment_id, carrier_name) VALUES (?, ?, 'generic', ?, ?, 'created', " +
                "'shopify_fulfillment', ?, ?) RETURNING id", UUID.class, f.tenant, order, tn, GenericTrackingKey.of(tn),
                "F-" + UUID.randomUUID(), carrier);
        }
        String bosta(UUID order, String tn) {
            jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
                "VALUES (?, ?, 'bosta', ?, 'created')", f.tenant, order, tn);
            return tn;
        }
        String piece() { return f.piece(variant, packer); }
        UUID user(String name, String role) { return f.user(name, role); }
        String token(UUID user, String role) { return jwt.issueAccessToken(user, f.tenant, role); }
    }

    // ── k: two-key resolution ────────────────────────────────────────────────────────────────────

    @Test
    void k1_everyCarrierShape_opensTheRightOrder_noBostaTenant() {
        Shop s = new Shop("k1", false, true);
        UUID jt = s.order("#K1"), wj = s.order("#K2"), my = s.order("#K3"), dg = s.order("#K4");
        s.generic(jt, "JT1234567890EG", "J&T Express");
        s.generic(wj, "WJ-12345", "Wijha");
        s.generic(my, "MY998877", "Mylerz");
        s.generic(dg, "123456789", "Mylerz");
        TenantContext.set(s.f.tenant);

        Map<String, UUID> expect = new LinkedHashMap<>();
        expect.put("JT1234567890EG", jt);
        expect.put("jt1234567890eg", jt);                                     // Caps Lock (scanner sends lower case)
        expect.put("JT 1234 5678 90EG", jt);                                   // spaced
        expect.put("WJ-12345", wj);
        expect.put("wj-12345", wj);
        expect.put("https://track.example.com/track?awb=MY998877&lang=en", my); // QR URL with an awb param
        expect.put("١٢٣٤٥٦٧٨٩", dg);                                            // Arabic-Indic digits
        expect.put("۱۲۳۴۵۶۷۸۹", dg);                                            // Persian digits
        for (Map.Entry<String, UUID> e : expect.entrySet()) {
            WaybillResolver.Resolution r = resolver.resolve(e.getKey(), s.packer);
            assertThat(r.code()).as(e.getKey()).isEqualTo(WaybillResolver.Code.OPEN);
            assertThat(r.orderId()).as(e.getKey()).isEqualTo(e.getValue());
        }
        // A Caps Lock scan through an Arabic layout: letters became Arabic glyphs → refused, never shortened to digits.
        WaybillResolver.Resolution garbled = resolver.resolve("تف1234567890ثل", s.packer);
        assertThat(garbled.code()).isEqualTo(WaybillResolver.Code.UNRECOGNISED_BARCODE);
        assertThat(garbled.messageEn()).isEqualTo(WaybillScanKeys.LAYOUT_EN);
        // An unknown waybill: courier-neutral, no "Check it's a Bosta waybill".
        WaybillResolver.Resolution unknown = resolver.resolve("ZZ55667788", s.packer);
        assertThat(unknown.code()).isEqualTo(WaybillResolver.Code.UNRECOGNISED_BARCODE);
        assertThat(resolver.resolve("WJ-77777", s.packer).messageEn()).isEqualTo(WaybillScanKeys.NOT_FOUND_EN);
    }

    @Test
    void k2_bostaAndGenericKeysMatchDifferentShipments_ambiguous_neverGuesses() {
        Shop s = new Shop("k2", true, true);
        UUID bostaOrder = s.order("#A1"), genericOrder = s.order("#A2");
        s.bosta(bostaOrder, "9100000001");
        s.generic(genericOrder, "9100000001", "Mylerz");
        TenantContext.set(s.f.tenant);
        WaybillResolver.Resolution r = resolver.resolve("9100000001", s.packer);
        assertThat(r.code()).isEqualTo(WaybillResolver.Code.AMBIGUOUS);
        assertThat(r.orderId()).isNull();
        assertThat(r.messageEn()).isEqualTo(WaybillScanKeys.AMBIGUOUS_EN);
    }

    @Test
    void k3_pieceCodeStillWinsFirst_andGenericWaybillWhilePacking_refused() {
        Shop s = new Shop("k3", false, true);
        UUID o = s.order("#P1");
        s.generic(o, "WJ-424242", "Wijha");
        UUID other = s.order("#P2");
        s.generic(other, "JT0000999", "J&T");
        String piece = s.piece();
        TenantContext.set(s.f.tenant);
        assertThat(resolver.resolve(piece, s.packer).code()).isEqualTo(WaybillResolver.Code.NOT_A_WAYBILL);

        UUID session = packStore.start(s.packer);
        assertThat(packStore.openWaybill(session, "WJ-424242", s.packer).result()).isEqualTo("opened");
        FulfillService.ScanResult r = packStore.scanPiece(session, o, "JT0000999", s.packer);
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("WAYBILL_WHILE_PACKING");
    }

    // ── p: the Femine case ───────────────────────────────────────────────────────────────────────

    @Test
    void p1_bostaTenantSwitchedOn_wijhaOrderPacksByItsWaybill_bostaOrderUnchanged() {
        Shop s = new Shop("p1", true, true);
        UUID wijha = s.order("#F1"), bostaOrder = s.order("#F2");
        UUID leg = s.generic(wijha, "WJ-55555", "Wijha");
        String tn = s.bosta(bostaOrder, "9200000002");
        String p1 = s.piece(), p2 = s.piece();
        TenantContext.set(s.f.tenant);

        UUID session = packStore.start(s.packer);
        PackSessionStore.WaybillOutcome opened = packStore.openWaybill(session, "wj-55555", s.packer);
        assertThat(opened.result()).isEqualTo("opened");
        assertThat(opened.order().get("shipment_provider")).isEqualTo("generic");
        assertThat(opened.order().get("shipment_carrier")).isEqualTo("Wijha");
        PackSessionService.ScanResponse done = packService.scan(session, wijha, p1, s.packer);
        assertThat(done.status()).isEqualTo("completed");
        assertThat(status(wijha)).isEqualTo("awaiting_pickup");
        assertThat(jdbc.queryForList("SELECT DISTINCT shipment_id FROM piece_events WHERE order_id = ? " +
            "AND event_type = 'tracking_linked'", UUID.class, wijha)).containsExactly(leg);

        PackSessionStore.WaybillOutcome bostaOpened = packStore.openWaybill(session, "D-07-" + tn, s.packer);
        assertThat(bostaOpened.result()).isEqualTo("opened");
        assertThat(bostaOpened.order()).doesNotContainKeys("shipment_provider", "shipment_carrier");
        assertThat(packService.scan(session, bostaOrder, p2, s.packer).status()).isEqualTo("completed");
        assertThat(status(bostaOrder)).isEqualTo("awaiting_pickup");
        // Only Bosta shipments in the tenant were ever created by Bosta paths; no Bosta id fetch was scheduled.
        assertThat(count("SELECT count(*) FROM shipments WHERE tenant_id = ? AND provider = 'bosta'", s.f.tenant)).isEqualTo(1);
        verify(jobScheduler, never()).enqueue(any(org.jobrunr.jobs.lambdas.JobLambda.class));
    }

    // ── q: queue AWB link step ───────────────────────────────────────────────────────────────────

    @Test
    void q1_queueLinkStep_genericVerify_mismatch_getOrderHasNoPrintableWaybill() {
        Shop s = new Shop("q1", false, true);
        UUID o = s.order("#Q1");
        UUID leg = s.generic(o, "WJ-12345", "Wijha");
        String piece = s.piece();
        TenantContext.set(s.f.tenant);

        Map<String, Object> detail = fulfill.getOrder(o);
        assertThat(detail.get("shipment_has_courier")).isEqualTo(false);
        assertThat(detail.get("shipment_provider")).isEqualTo("generic");
        assertThat(detail.get("shipment_carrier")).isEqualTo("Wijha");
        assertThat(detail.get("tracking_number")).isEqualTo("WJ-12345");

        fulfill.scan(o, piece, s.packer);
        fulfill.complete(o, s.packer);
        assertThatThrownBy(() -> link.linkByAwbScan(o, "WJ-99999", s.packer))
            .isInstanceOf(AwbMismatchException.class)
            .satisfies(e -> {
                assertThat(((AwbMismatchException) e).getScannedAwb()).isEqualTo("WJ99999");
                assertThat(((AwbMismatchException) e).getExistingAwb()).isEqualTo("WJ-12345");
            });
        assertThatThrownBy(() -> link.linkByAwbScan(o, "JT0001", s.packer)).isInstanceOf(AwbMismatchException.class);
        Map<String, Object> linked = link.linkByAwbScan(o, "wj 12345", s.packer);
        assertThat(linked.get("shipmentId")).isEqualTo(leg.toString());
        assertThat(linked.get("trackingNumber")).isEqualTo("WJ-12345");
        assertThat(status(o)).isEqualTo("awaiting_pickup");
        assertThat(count("SELECT count(*) FROM shipments WHERE order_id = ?", o)).isEqualTo(1);
    }

    @Test
    void q2_bostaAwbOnPackedGenericOrder_409_storedHighAlert_survivesRollback_resolvable() {
        Shop s = new Shop("q2", true, true);
        UUID o = s.order("#Q2");
        s.generic(o, "WJ-31313", "Wijha");
        String piece = s.piece();
        TenantContext.set(s.f.tenant);
        fulfill.scan(o, piece, s.packer);
        fulfill.complete(o, s.packer);

        assertThatThrownBy(() -> link.linkByAwbScan(o, "D-07-9300000003", s.packer))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
        assertThat(count("SELECT count(*) FROM shipments WHERE order_id = ? AND provider = 'bosta'", o)).isZero();
        assertThat(count("SELECT count(*) FROM generic_conflict_scans WHERE order_id = ? AND tracking_number = '9300000003'", o))
            .isEqualTo(1);

        List<Map<String, Object>> alerts = conflictAlerts(s);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0)).containsEntry("severity", "HIGH").containsEntry("source", "pack_scan")
            .containsEntry("order_number", "#Q2");
        assertThat((String) alerts.get(0).get("descriptionEn")).contains("Wijha").contains("9300000003");

        jdbc.update("INSERT INTO exception_resolutions (tenant_id, exception_type, subject_key, resolved_by) " +
            "VALUES (?, 'generic_bosta_conflict', ?, ?)", s.f.tenant, alerts.get(0).get("subject_key"), s.packer);
        assertThat(conflictAlerts(s)).isEmpty();

        // A switched-on tenant with NO Bosta account: digits are just the wrong waybill — mismatch, no alert.
        Shop n = new Shop("q2n", false, true);
        UUID o2 = n.order("#Q3");
        n.generic(o2, "WJ-41414", "Wijha");
        String p2 = n.piece();
        TenantContext.set(n.f.tenant);
        fulfill.scan(o2, p2, n.packer);
        fulfill.complete(o2, n.packer);
        assertThatThrownBy(() -> link.linkByAwbScan(o2, "9300000004", n.packer)).isInstanceOf(AwbMismatchException.class);
        assertThat(count("SELECT count(*) FROM generic_conflict_scans WHERE tenant_id = ?", n.f.tenant)).isZero();
    }

    // ── c: catch-up ──────────────────────────────────────────────────────────────────────────────

    @Test
    void c1_catchUp_eligibility_dryRunEqualsRunEqualsScript_idempotent() throws Exception {
        Shop s = new Shop("c1", false, false);
        jdbc.update("UPDATE stores SET orders_ingest_from = now() - interval '10 days' WHERE tenant_id = ?", s.f.tenant);
        UUID c1 = s.order("#C1"); tracking(s, c1, "MY111111", "Mylerz", "success", 0);
        UUID c2 = s.order("#C2"); tracking(s, c2, "OT222222", "Other", "success", 0);
        UUID c3 = s.order("#C3"); tracking(s, c3, "9400000003", "Bosta", "success", 0);
        UUID c4 = s.order("#C4"); tracking(s, c4, "MY444444", "Mylerz", "cancelled", 0);
        UUID c5 = s.order("#C5"); tracking(s, c5, "MY555555", "Mylerz", "success", 0);
        jdbc.update("UPDATE orders SET status = 'packed' WHERE id = ?", c5);
        UUID c6 = s.order("#C6"); tracking(s, c6, "MY666666", "Mylerz", "success", 0); s.bosta(c6, "9400000006");
        UUID c7 = s.f.order("#C7", 20); s.f.item(c7, s.variant, 1); tracking(s, c7, "MY777777", "Mylerz", "success", 0);
        UUID c8 = s.order("#C8"); tracking(s, c8, "WJ-888", "Wijha", "success", 0); tracking(s, c8, "MY888888", "Mylerz", "success", 5);
        UUID c9 = s.order("#C9"); tracking(s, c9, "QP999999", "QP Express", "success", 0);  // stored 'unknown', now named
        UUID used = s.order("#USED"); s.generic(used, "MY101010", "Mylerz");
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup' WHERE id = ?", used);
        UUID c10 = s.order("#C10"); tracking(s, c10, "my-101010", "Mylerz", "success", 0);       // same key as #USED

        GenericCatchUp.DryRun dry = TenantContext.runAs(s.f.tenant,
            () -> GenericCatchUp.dryRun(jdbc, s.f.tenant, FulfillmentTrackingCapture::carrierClass));
        assertThat(dry.candidates()).extracting(GenericCatchUp.Candidate::orderNumber)
            .containsExactlyInAnyOrder("#C1", "#C2", "#C8", "#C9");
        assertThat(dry.candidates()).filteredOn(c -> c.orderNumber().equals("#C8"))
            .extracting(GenericCatchUp.Candidate::trackingNumber).containsExactly("WJ-888");   // earliest wins
        assertThat(dry.keyInUse()).isEqualTo(1);
        assertThat(dry.byCarrier()).containsEntry("Mylerz", 1).containsEntry("Other", 1)
            .containsEntry("Wijha", 1).containsEntry("QP Express", 1);

        // The prod dry-run script says the same, through real psql.
        Map<String, String> script = scriptRow(s.f.tenant);
        assertThat(script.get("would_create")).isEqualTo("4");
        assertThat(script.get("key_in_use_orders")).isEqualTo("1");
        assertThat(script.get("by_carrier")).isEqualTo("Mylerz=1, Other=1, QP Express=1, Wijha=1");

        // Switch on (owner, HTTP) → catch-up enqueued after commit, audit row; the job creates exactly the dry run.
        UUID owner = s.user("Owner", "owner");
        ResponseEntity<Map> put = http(s.token(owner, "owner"), HttpMethod.PUT, "", Map.of("enabled", true, "defaultCarrier", "Mylerz"));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jobScheduler, times(1)).enqueue(any(IocJobLambda.class));
        assertThat(catchUpJob.run(s.f.tenant)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT o.number FROM shipments sh JOIN orders o ON o.id = sh.order_id " +
            "WHERE sh.tenant_id = ? AND sh.provider = 'generic' AND sh.source = 'shopify_fulfillment' AND o.number <> '#USED'",
            String.class, s.f.tenant)).containsExactlyInAnyOrder("#C1", "#C2", "#C8", "#C9");
        assertThat(catchUpJob.run(s.f.tenant)).as("idempotent").isZero();
        assertThat(TenantContext.runAs(s.f.tenant,
            () -> GenericCatchUp.dryRun(jdbc, s.f.tenant, FulfillmentTrackingCapture::carrierClass)).wouldCreate()).isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'generic_courier_catch_up'", s.f.tenant))
            .isEqualTo(2);
    }

    @Test
    void c2_bostaTenant_unknownCarrier_neverCaughtUp() {
        Shop s = new Shop("c2", true, false);
        UUID o = s.order("#B1"); tracking(s, o, "OT123456", "Other", "success", 0);
        UUID w = s.order("#B2"); tracking(s, w, "WJ-123456", "Wijha", "success", 0);
        GenericCatchUp.DryRun dry = TenantContext.runAs(s.f.tenant,
            () -> GenericCatchUp.dryRun(jdbc, s.f.tenant, FulfillmentTrackingCapture::carrierClass));
        assertThat(dry.candidates()).extracting(GenericCatchUp.Candidate::orderNumber).containsExactly("#B2");
    }

    // ── s: settings ──────────────────────────────────────────────────────────────────────────────

    @Test
    void s1_roles_audit_switchOff_leavesShipmentsAlone_ordersStillPack() {
        Shop s = new Shop("s1", false, false);
        UUID owner = s.user("Owner", "owner"), manager = s.user("Manager", "manager"), worker = s.user("Worker", "worker");
        String ot = s.token(owner, "owner"), mt = s.token(manager, "manager"), wt = s.token(worker, "worker");

        assertThat(http(mt, HttpMethod.GET, "", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http(wt, HttpMethod.GET, "", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http(mt, HttpMethod.PUT, "", Map.of("enabled", true)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(flag(s)).isFalse();

        Map<?, ?> on = http(ot, HttpMethod.PUT, "", Map.of("enabled", true, "defaultCarrier", "  Wijha ")).getBody();
        assertThat(on.get("enabled")).isEqualTo(true);
        assertThat(on.get("defaultCarrier")).isEqualTo("Wijha");
        assertThat((List<Object>) on.get("suggestions")).contains((Object) "Wijha", "Mylerz", "QP Express", "J&T Express");
        assertThat(get("/api/v1/me", ot).get("genericCourier")).isEqualTo(true);

        UUID o = s.order("#S1");
        UUID leg = s.generic(o, "WJ-60606", "Wijha");
        Map<?, ?> off = http(ot, HttpMethod.PUT, "", Map.of("enabled", false, "defaultCarrier", "Wijha")).getBody();
        assertThat(off.get("enabled")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, leg))
            .as("switching off cancels nothing").isEqualTo("created");
        TenantContext.set(s.f.tenant);
        assertThat(resolver.resolve("WJ-60606", s.packer).orderId()).as("still packable after off").isEqualTo(o);

        List<Map<String, Object>> audit = jdbc.queryForList("SELECT action, actor_user_id, metadata::text AS m FROM audit_log " +
            "WHERE tenant_id = ? AND action LIKE 'generic_%' ORDER BY created_at, id", s.f.tenant);
        assertThat(audit).extracting(a -> a.get("action") + " " + a.get("m")).containsExactly(
            "generic_courier_switch {\"to\": true, \"from\": false}",
            "generic_default_carrier {\"to\": \"Wijha\", \"from\": null}",
            "generic_courier_switch {\"to\": false, \"from\": true}");
        assertThat(audit).allSatisfy(a -> assertThat(a.get("actor_user_id")).isEqualTo(owner));
        verify(jobScheduler, times(1)).enqueue(any(IocJobLambda.class));   // only the switch-on
    }

    // ── t: Test a waybill ────────────────────────────────────────────────────────────────────────

    @Test
    void t1_testAWaybill_verdicts_readOnly_roles() {
        Shop s = new Shop("t1", true, true);
        UUID wj = s.order("#T1"); s.generic(wj, "WJ-70707", "Wijha");
        tracking(s, wj, "WJ-70707", "Wijha", "success", 0);
        UUID b = s.order("#T2"); s.bosta(b, "9500000005");
        UUID f = s.order("#T3"); tracking(s, f, "MY8080808", "Mylerz", "success", 0);   // fulfillment, no shipment
        UUID a1 = s.order("#T4"); s.bosta(a1, "9500000009");
        UUID a2 = s.order("#T5"); s.generic(a2, "9500000009", "Mylerz");
        UUID manager = s.user("M", "manager"), worker = s.user("W", "worker");
        String mt = s.token(manager, "manager");
        String piece = s.piece();

        Map<String, Long> before = rowCounts();
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (String code : List.of("wj-70707", "D-07-9500000005", "MY8080808", "ZZ99887766", piece, "8080808", "12AB",
                                   "9500000009", "تف12345")) {
            ResponseEntity<Map> r = http(mt, HttpMethod.POST, "/test-waybill", Map.of("code", code));
            assertThat(r.getStatusCode()).as(code).isEqualTo(HttpStatus.OK);
            verdicts.put(code, r.getBody().get("verdict") + " | " + r.getBody().get("verdictEn"));
            assertThat(r.getBody().get("format")).isEqualTo("unknown");
            assertThat((String) r.getBody().get("cameraHintEn")).contains("hardware scanner");
        }
        assertThat(verdicts.get("wj-70707")).isEqualTo("OPENS_ORDER | This waybill will open order #T1");
        assertThat(verdicts.get("D-07-9500000005")).isEqualTo("OPENS_ORDER | This waybill will open order #T2");
        assertThat(verdicts.get("MY8080808")).startsWith("FULFILLMENT_ONLY | Shopify has this number on order #T3");
        assertThat(verdicts.get("ZZ99887766")).isEqualTo("NO_FULFILLMENT | Shopify has no fulfillment with this number yet.");
        assertThat(verdicts.get(piece)).startsWith("WRONG_BARCODE | This is a piece label");
        assertThat(verdicts.get("8080808")).isEqualTo("WRONG_BARCODE | This looks like the wrong barcode — try the other one on the label.");
        assertThat(verdicts.get("12AB")).startsWith("WRONG_BARCODE");
        assertThat(verdicts.get("9500000009")).startsWith("AMBIGUOUS");
        assertThat(verdicts.get("تف12345")).startsWith("KEYBOARD_LAYOUT");

        Map<?, ?> wjBody = http(mt, HttpMethod.POST, "/test-waybill", Map.of("code", "wj-70707")).getBody();
        assertThat(wjBody.get("bostaKey")).isEqualTo("70707");
        assertThat(wjBody.get("genericKey")).isEqualTo("WJ70707");
        assertThat(wjBody.get("waybillModeCode")).isEqualTo("OPEN");
        assertThat((List<?>) wjBody.get("fulfillments")).hasSize(1);
        assertThat((List<?>) wjBody.get("shipments")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) wjBody.get("shipments")).get(0)).get("carrier")).isEqualTo("Wijha");

        assertThat(rowCounts()).as("read-only: nothing written").isEqualTo(before);
        assertThat(http(s.token(worker, "worker"), HttpMethod.POST, "/test-waybill", Map.of("code", "x")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── qp ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void qp_qpExpressIsNamed_inJavaAndSql() {
        assertThat(FulfillmentTrackingCapture.carrierClass("QP Express", null)).isEqualTo("other_known");
        assertThat(jdbc.queryForObject("SELECT 'qp express' = ANY (other_known_carriers())", Boolean.class)).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void tracking(Shop s, UUID order, String tn, String carrier, String status, int minutesLater) {
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class, " +
            "shopify_fulfillment_id, fulfillment_status, first_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, now() + (? * interval '1 minute'))",
            s.f.tenant, order, tn, carrier, FulfillmentTrackingCapture.carrierClass(carrier, null).equals("other_known")
                && carrier.equals("QP Express") ? "unknown" : FulfillmentTrackingCapture.carrierClass(carrier, null),
            "F-" + UUID.randomUUID(), status, minutesLater);
    }

    private Map<String, String> scriptRow(UUID tenant) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/generic_courier_catchup_dryrun.sql"), "/tmp/dry.sql");
        ExecResult r = POSTGRES.execInContainer("psql", "-U", "postgres", "-d", "traceability_test",
            "-X", "-q", "-A", "-F", "|", "-f", "/tmp/dry.sql");
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        String[] lines = r.getStdout().strip().split("\n");
        String[] header = lines[0].split("\\|", -1);
        for (String line : lines) {
            String[] v = line.split("\\|", -1);
            if (v.length > 1 && v[1].equals(tenant.toString())) {
                Map<String, String> m = new HashMap<>();
                for (int i = 0; i < header.length; i++) m.put(header[i], v[i]);
                return m;
            }
        }
        throw new AssertionError("tenant not in script output:\n" + r.getStdout());
    }

    private List<Map<String, Object>> conflictAlerts(Shop s) {
        return TenantContext.runAs(s.f.tenant, () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "generic_bosta_conflict".equals(e.get("type"))).toList();
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> m = new TreeMap<>();
        for (String t : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' " +
                "AND tablename NOT LIKE 'jobrunr%'", String.class)) {
            m.put(t, jdbc.queryForObject("SELECT count(*) FROM \"" + t + "\"", Long.class));
        }
        return m;
    }

    private boolean flag(Shop s) {
        return jdbc.queryForObject("SELECT generic_courier_enabled FROM tenants WHERE id = ?", Boolean.class, s.f.tenant);
    }

    private String status(UUID order) {
        return jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, order);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private ResponseEntity<Map> http(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/v1/settings/couriers" + path, method,
            new HttpEntity<>(body, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), Map.class).getBody();
    }
}
