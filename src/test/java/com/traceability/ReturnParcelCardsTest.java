package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Step 5 — return-session parcel cards: mark-received / undo for courier-return parcels of
 * orders Traced never tracked, intake outcome + actor on close, the return_to_receive
 * exception, listCrpReturns' received_untracked state, and the session-detail parcel view.
 *
 * CRP legs use the stored production shape (type.code=25, returnSpecs.packageDetails,
 * pickupAddress = customer) with bare numeric AWBs, and are scanned into the session through
 * the real ReturnSessionService.scan() path. The JobRunr server is enabled so the auto-close
 * job bean exists (same as ReturnSessionAutoCloseTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "org.jobrunr.background-job-server.enabled=true")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnParcelCardsTest {

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

    @Autowired JdbcTemplate              jdbc;
    @Autowired ReturnSessionService      sessionSvc;
    @Autowired ShipmentLinkService       linkSvc;
    @Autowired ExceptionService          exceptionSvc;
    @Autowired ReturnSessionAutoCloseJob autoCloseJob;
    @Autowired InventoryLedger           ledger;
    @Autowired ReturnService             returnService;

    @MockBean ShopifyInventoryService shopifyInventory;

    record Tenant(UUID id, UUID actor, UUID store, UUID variant, UUID location) {}

    Tenant a, b;
    ReturnSessionService appUserSessions;
    ExceptionService     appUserExceptions;
    TransactionTemplate  appUserTx;

    @BeforeAll
    void setup() {
        a = newTenant("Parcel A");
        b = newTenant("Parcel B");

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appUserDs);
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        ShipmentLinkService appLink = new ShipmentLinkService(appJdbc, null, null, null, null, null, null, null, null);
        appUserSessions   = new ReturnSessionService(appJdbc, ledger, returnService, appLink, Clock.systemUTC());
        appUserExceptions = new ExceptionService(appJdbc, Clock.systemUTC(), appLink);
    }

    @BeforeEach
    void stubs() {
        when(shopifyInventory.onReturnInspectionAvailable(any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Tenant t : List.of(a, b)) {
            UUID id = t.id();
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", id);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", id);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", id);
        }
    }

    // ── mark-received ─────────────────────────────────────────────────────────

    @Test
    void markReceived_untrackedOrder_scannedInOpenSession_succeeds_withOutcomeByAndSession() {
        Crp crp = untrackedCrp(a);
        UUID s = open(a);
        scan(a, s, crp.awb());

        as(a, () -> sessionSvc.markReceived(s, crp.shipmentId(), a.actor()));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT return_intake_completed_at, return_intake_outcome, return_intake_by, return_intake_session_id " +
            "FROM shipments WHERE id = ?", crp.shipmentId());
        assertThat(row.get("return_intake_completed_at")).isNotNull();
        assertThat(row.get("return_intake_outcome")).isEqualTo("received_untracked");
        assertThat(row.get("return_intake_by")).isEqualTo(a.actor());
        assertThat(row.get("return_intake_session_id")).isEqualTo(s);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, a.id()))
            .as("no piece created or moved").isZero();
    }

    @Test
    void markReceived_409s_trackedOrder_notScannedHere_closedSession_alreadyComplete() {
        // Tracked: an order with a (released) allocation — Traced tracked it once.
        Crp tracked = trackedCrp(a);
        UUID s = open(a);
        scan(a, s, tracked.awb());
        assertStatus(HttpStatus.CONFLICT, "This order has tracked items — scan them instead.",
            () -> as(a, () -> sessionSvc.markReceived(s, tracked.shipmentId(), a.actor())));

        // Not scanned in this session.
        Crp notScanned = untrackedCrp(a);
        assertStatus(HttpStatus.CONFLICT, "Scan this parcel's AWB in this session first.",
            () -> as(a, () -> sessionSvc.markReceived(s, notScanned.shipmentId(), a.actor())));

        // Already-complete intake.
        Crp done = untrackedCrp(a);
        scan(a, s, done.awb());
        jdbc.update("UPDATE shipments SET return_intake_completed_at = now(), return_intake_outcome = 'scanned' WHERE id = ?",
            done.shipmentId());
        assertStatus(HttpStatus.CONFLICT, "This parcel's intake is already complete.",
            () -> as(a, () -> sessionSvc.markReceived(s, done.shipmentId(), a.actor())));

        // Closed session.
        Crp late = untrackedCrp(a);
        scan(a, s, late.awb());
        as(a, () -> sessionSvc.close(s, a.actor()));
        assertStatus(HttpStatus.CONFLICT, null,
            () -> as(a, () -> sessionSvc.markReceived(s, late.shipmentId(), a.actor())));
    }

    @Test
    void markReceived_otherTenantsShipment_404() {
        Crp other = untrackedCrp(b);
        UUID s = open(a);
        assertStatus(HttpStatus.NOT_FOUND, null,
            () -> as(a, () -> sessionSvc.markReceived(s, other.shipmentId(), a.actor())));
    }

    // ── effects of marking ────────────────────────────────────────────────────

    @Test
    void afterMarking_awaitingScanDrops_unscannedSilent_returnToReceiveFires_listShowsReceivedUntracked() {
        Crp crp = untrackedCrp(a);
        jdbc.update("UPDATE shipments SET returned_at = now() - interval '5 days' WHERE id = ?", crp.shipmentId());
        UUID s = open(a);
        scan(a, s, crp.awb());

        assertThat(awaitingScanCount(a)).isEqualTo(1);
        assertThat(exceptionIds(a, "return_leg_unscanned")).contains(crp.shipmentId().toString());

        as(a, () -> sessionSvc.markReceived(s, crp.shipmentId(), a.actor()));

        assertThat(awaitingScanCount(a)).as("no longer waiting to be scanned").isZero();
        assertThat(exceptionIds(a, "return_leg_unscanned")).doesNotContain(crp.shipmentId().toString());
        List<Map<String, Object>> rtr = exceptions(a, "return_to_receive");
        assertThat(rtr).singleElement().satisfies(e -> {
            assertThat(e.get("severity")).isEqualTo("MEDIUM");
            assertThat(e.get("subject_key")).isEqualTo(crp.shipmentId().toString());
            assertThat((String) e.get("descriptionEn")).isEqualTo(
                "Return " + crp.awb() + " for order " + crp.orderNumber() + " arrived, but Traced never tracked this order. " +
                "Add the item in your next Receiving session, or resolve this if it won't go back into stock.");
            assertThat(e.get("actionUrl")).isEqualTo("/receiving");
        });

        Map<String, Object> listed = crpRow(a, crp.awb());
        assertThat(listed.get("inspection_state")).isEqualTo("received_untracked");
        assertThat(listed.get("awaiting_receiving")).isEqualTo(true);

        as(a, () -> exceptionSvc.resolve("return_to_receive", crp.shipmentId().toString(), a.actor(), "added in receiving"));
        assertThat(exceptions(a, "return_to_receive")).isEmpty();
        assertThat(crpRow(a, crp.awb()).get("awaiting_receiving")).isEqualTo(false);
        assertThat(crpRow(a, crp.awb()).get("inspection_state")).isEqualTo("received_untracked");
    }

    // ── undo ──────────────────────────────────────────────────────────────────

    @Test
    void undo_sameOpenSessionOnly_restoresAwaitingScan_andRemovesException() {
        Crp crp = untrackedCrp(a);
        UUID s = open(a);
        scan(a, s, crp.awb());
        as(a, () -> sessionSvc.markReceived(s, crp.shipmentId(), a.actor()));
        assertThat(exceptions(a, "return_to_receive")).hasSize(1);

        as(a, () -> sessionSvc.undoMarkReceived(s, crp.shipmentId(), a.actor()));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT return_intake_completed_at, return_intake_outcome, return_intake_by, return_intake_session_id " +
            "FROM shipments WHERE id = ?", crp.shipmentId());
        assertThat(row.values()).containsOnlyNulls();
        assertThat(awaitingScanCount(a)).isEqualTo(1);
        assertThat(exceptions(a, "return_to_receive")).isEmpty();

        // Re-mark, close the session → undo is no longer possible (closed), nor from a new session.
        as(a, () -> sessionSvc.markReceived(s, crp.shipmentId(), a.actor()));
        as(a, () -> sessionSvc.close(s, a.actor()));
        assertStatus(HttpStatus.CONFLICT, null,
            () -> as(a, () -> sessionSvc.undoMarkReceived(s, crp.shipmentId(), a.actor())));
        UUID s2 = open(a);
        scan(a, s2, crp.awb());
        assertStatus(HttpStatus.CONFLICT, "Only a parcel marked received in this session can be undone.",
            () -> as(a, () -> sessionSvc.undoMarkReceived(s2, crp.shipmentId(), a.actor())));
    }

    // ── close() / auto-close outcome ──────────────────────────────────────────

    @Test
    void close_setsOutcomeScannedAndBy_autoCloseSetsByNull() {
        Crp manual = trackedCrpWithDeliveredPiece(a);
        UUID s = open(a);
        scan(a, s, "PC-" + manual.pieceId());
        as(a, () -> sessionSvc.disposition(s, manual.pieceId(), "restock", null, a.location(), a.actor()));
        as(a, () -> sessionSvc.close(s, a.actor()));
        Map<String, Object> m = jdbc.queryForMap(
            "SELECT return_intake_outcome, return_intake_by, return_intake_session_id FROM shipments WHERE id = ?",
            manual.shipmentId());
        assertThat(m.get("return_intake_outcome")).isEqualTo("scanned");
        assertThat(m.get("return_intake_by")).isEqualTo(a.actor());
        assertThat(m.get("return_intake_session_id")).isEqualTo(s);

        Crp auto = trackedCrpWithDeliveredPiece(a);
        UUID s2 = open(a);
        scan(a, s2, "PC-" + auto.pieceId());
        as(a, () -> sessionSvc.disposition(s2, auto.pieceId(), "restock", null, a.location(), a.actor()));
        jdbc.update("UPDATE return_sessions SET opened_at = now() - interval '13 hours' WHERE id = ?", s2);
        jdbc.update("UPDATE return_session_items SET scanned_at = now() - interval '13 hours', " +
                    "disposition_at = now() - interval '13 hours' WHERE session_id = ?", s2);
        autoCloseJob.processTenant(a.id());
        Map<String, Object> m2 = jdbc.queryForMap(
            "SELECT return_intake_outcome, return_intake_by FROM shipments WHERE id = ?", auto.shipmentId());
        assertThat(m2.get("return_intake_outcome")).isEqualTo("scanned");
        assertThat(m2.get("return_intake_by")).as("system").isNull();
    }

    // ── session detail: parcels ───────────────────────────────────────────────

    @Test
    void sessionDetail_parcelsGroupedByAwb_expectedUnderRightParcel_otherItems_complete_lastScan() {
        Crp two = trackedCrpTwoPieces(a);               // 2 delivered pieces, CRP returned
        Crp untracked = untrackedCrp(a);
        String loosePiece = deliveredPieceOnNewOrder(a, true);   // in-window delivered, no scanned AWB

        UUID s = open(a);
        scan(a, s, two.awb());
        Map<String, Object> d1 = detail(a, s);
        assertThat(lastScan(d1)).containsEntry("kind", "awb").containsEntry("code", two.awb());
        assertThat(parcels(d1)).singleElement().satisfies(p -> {
            assertThat(p.get("leg")).isEqualTo("return");
            assertThat(p.get("tracked")).isEqualTo(true);
            assertThat(p.get("customerShortName")).isEqualTo("Mariam S.");
            assertThat(((Map<?, ?>) p.get("bosta")).get("itemsCount")).isEqualTo(2);
            assertThat((List<?>) p.get("expectedPieces")).hasSize(2);
            assertThat(p.get("counts")).isEqualTo(Map.of("expected", 2, "scanned", 0));
            assertThat(p.get("complete")).isEqualTo(false);
        });

        scan(a, s, "PC-" + two.pieceId());
        scan(a, s, untracked.awb());
        scan(a, s, "PC-" + loosePiece);
        Map<String, Object> d2 = detail(a, s);
        assertThat(lastScan(d2)).containsEntry("kind", "piece");
        List<Map<String, Object>> ps = parcels(d2);
        assertThat(ps).extracting(p -> p.get("awb")).as("newest first").containsExactly(untracked.awb(), two.awb());
        Map<String, Object> twoParcel = ps.get(1);
        assertThat((List<?>) twoParcel.get("expectedPieces")).hasSize(1);
        assertThat((List<?>) twoParcel.get("scannedItems")).hasSize(1);
        assertThat(twoParcel.get("counts")).isEqualTo(Map.of("expected", 2, "scanned", 1));
        Map<String, Object> utParcel = ps.get(0);
        assertThat(utParcel.get("tracked")).isEqualTo(false);
        assertThat((List<?>) utParcel.get("expectedPieces")).isEmpty();
        assertThat(utParcel.get("complete")).isEqualTo(false);
        assertThat(otherItems(d2)).extracting(i -> i.get("piece_id")).containsExactly(loosePiece);

        // Scan + restock the 2nd piece → parcel complete (restock clears current_order_id,
        // the item must still sit under its parcel).
        String second = two.otherPieceId();
        scan(a, s, "PC-" + second);
        as(a, () -> sessionSvc.disposition(s, two.pieceId(), "restock", null, a.location(), a.actor()));
        as(a, () -> sessionSvc.disposition(s, second, "restock", null, a.location(), a.actor()));
        Map<String, Object> twoDone = parcels(detail(a, s)).stream()
            .filter(p -> two.awb().equals(p.get("awb"))).findFirst().orElseThrow();
        assertThat((List<?>) twoDone.get("scannedItems")).hasSize(2);
        assertThat(twoDone.get("complete")).isEqualTo(true);

        as(a, () -> sessionSvc.markReceived(s, untracked.shipmentId(), a.actor()));
        Map<String, Object> d3 = detail(a, s);
        assertThat(lastScan(d3)).containsEntry("kind", "marked_received").containsEntry("code", untracked.awb());
        Map<String, Object> utDone = parcels(d3).get(0);
        assertThat(utDone.get("intakeOutcome")).isEqualTo("received_untracked");
        assertThat(utDone.get("markedBy")).isEqualTo("Worker");
        assertThat(utDone.get("complete")).isEqualTo(true);

        // Compatibility: the old fields are still there.
        assertThat(d3).containsKeys("items", "expectedPieces", "courierReturns");
    }

    @Test
    void sessionDetail_forwardAwbWithNoEligiblePieces_isAParcelWithNothingExpected() {
        UUID order = order(a, "Omar Khaled");
        String fwdAwb = shipment(a, order, "forward", "delivered", null);
        UUID s = open(a);
        scan(a, s, fwdAwb);
        assertThat(parcels(detail(a, s))).singleElement().satisfies(p -> {
            assertThat(p.get("leg")).isEqualTo("forward");
            assertThat(p.get("bosta")).isNull();
            assertThat((List<?>) p.get("expectedPieces")).isEmpty();
            assertThat(p.get("complete")).isEqualTo(false);
        });
    }

    // ── cross-tenant on a real app_user connection ─────────────────────────────

    @Test
    void crossTenant_appUser_markUndoDetector_withSameTenantPositiveControl() {
        // Positive control: tenant A marks its own parcel under app_user + RLS.
        Crp mine = untrackedCrp(a);
        UUID s = open(a);
        scan(a, s, mine.awb());
        appUser(a, () -> appUserSessions.markReceived(s, mine.shipmentId(), a.actor()));
        assertThat(jdbc.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class,
            mine.shipmentId())).isEqualTo("received_untracked");

        // Tenant B's parcel, marked in B's own session, is invisible to tenant A.
        Crp theirs = untrackedCrp(b);
        UUID sb = open(b);
        scan(b, sb, theirs.awb());
        as(b, () -> sessionSvc.markReceived(sb, theirs.shipmentId(), b.actor()));

        Crp theirsUnmarked = untrackedCrp(b);
        assertStatus(HttpStatus.NOT_FOUND, null,
            () -> appUser(a, () -> appUserSessions.markReceived(s, theirsUnmarked.shipmentId(), a.actor())));
        assertStatus(HttpStatus.NOT_FOUND, null,
            () -> appUser(a, () -> appUserSessions.undoMarkReceived(s, theirs.shipmentId(), a.actor())));
        assertThat(jdbc.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class,
            theirsUnmarked.shipmentId())).as("B's row untouched").isNull();
        assertThat(jdbc.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class,
            theirs.shipmentId())).isEqualTo("received_untracked");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seenByA = TenantContext.runAs(a.id(), () -> appUserTx.execute(tx ->
            (List<Map<String, Object>>) appUserExceptions.listExceptions("return_to_receive", null, 0, 50).get("items")));
        assertThat(seenByA).extracting(e -> e.get("subject_key"))
            .contains(mine.shipmentId().toString())
            .doesNotContain(theirs.shipmentId().toString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    record Crp(UUID shipmentId, String awb, UUID orderId, String orderNumber, String pieceId, String otherPieceId) {}

    private Tenant newTenant(String name) {
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID(), store = UUID.randomUUID(),
             product = UUID.randomUUID(), variant = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Worker', ?, 'h', 'worker')", actor, id, "pc-" + actor + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')", store, id, "pc-" + id + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-PC', 'Flipped Pants', 'active')", product, id, store);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-PC', 'Black / XL', '1001-Black-XL')", variant, id, product);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')", location, id);
        return new Tenant(id, actor, store, variant, location);
    }

    private UUID order(Tenant t, String customerName) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), ?) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(),
            "#3853" + ThreadLocalRandom.current().nextInt(100_000, 999_999), customerName);
    }

    private String shipment(Tenant t, UUID order, String leg, String state, String rawJson) {
        String awb = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, " +
                    "    created_at, returned_at, raw) " +
                    "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, now() - interval '5 days', " +
                    "    CASE WHEN ? = 'returned' THEN now() - interval '1 day' END, ?::jsonb)",
                    t.id(), order, awb, state, leg, state, rawJson);
        return awb;
    }

    private static String crpRaw(int items, String description) {
        return "{\"type\":{\"code\":25,\"value\":\"Customer Return Pickup\"}," +
            "\"receiver\":{\"fullName\":\"Mona Customer\",\"phone\":\"+201011112222\"}," +
            "\"sender\":{\"name\":\"Merchant Co\"}," +
            "\"pickupAddress\":{\"firstLine\":\"12 Customer St\",\"city\":{\"_id\":\"c1\",\"name\":\"Giza\"}}," +
            "\"dropOffAddress\":{\"firstLine\":\"Merchant Warehouse\",\"city\":{\"_id\":\"c2\",\"name\":\"New Cairo\"}}," +
            "\"returnSpecs\":{\"packageDetails\":{\"itemsCount\":" + items + ",\"description\":\"" + description + "\"}}}";
    }

    /** A CRP leg ('returned') on an order with NO allocations at all — Traced never tracked it. */
    private Crp untrackedCrp(Tenant t) {
        UUID order = order(t, "Omar Khaled");
        String awb = shipment(t, order, "return", "returned", crpRaw(1, "Flipped Pants in Black - XL x 1 (1001-Black-XL)"));
        return crp(t, awb, order, null, null);
    }

    /** A CRP leg on an order that HAS a (released) allocation — tracked, even with no live piece. */
    private Crp trackedCrp(Tenant t) {
        UUID order = order(t, "Mariam Samir");
        String piece = piece(t, order, "available", 0);
        jdbc.update("UPDATE allocations SET status = 'released' WHERE piece_id = ?", piece);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE id = ?", piece);
        String awb = shipment(t, order, "return", "returned", crpRaw(1, "Flipped Pants in Black - XL x 1 (1001-Black-XL)"));
        return crp(t, awb, order, piece, null);
    }

    private Crp trackedCrpWithDeliveredPiece(Tenant t) {
        UUID order = order(t, "Mariam Samir");
        String piece = piece(t, order, "delivered", 60);
        String awb = shipment(t, order, "return", "returned", crpRaw(1, "Flipped Pants in Black - XL x 1 (1001-Black-XL)"));
        return crp(t, awb, order, piece, null);
    }

    private Crp trackedCrpTwoPieces(Tenant t) {
        UUID order = order(t, "Mariam Samir");
        String p1 = piece(t, order, "delivered", 60);
        String p2 = piece(t, order, "delivered", 60);
        String awb = shipment(t, order, "return", "returned", crpRaw(2, "2 items — Flipped Pants x 2"));
        return crp(t, awb, order, p1, p2);
    }

    private String deliveredPieceOnNewOrder(Tenant t, boolean inWindow) {
        UUID order = order(t, "Salma Adel");
        return piece(t, order, "delivered", inWindow ? 1 : 60);
    }

    private Crp crp(Tenant t, String awb, UUID order, String piece, String other) {
        UUID id = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, awb);
        String number = jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, order);
        return new Crp(id, awb, order, number, piece, other);
    }

    private String piece(Tenant t, UUID order, String status, int daysAgo) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, " +
                    "        now() - (interval '1 day' * ?))",
                    id, t.id(), t.variant(), "PC-" + id, id, status, order, daysAgo);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                    item, t.id(), order, t.variant());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                    t.id(), item, id);
        return id;
    }

    private UUID open(Tenant t) {
        return TenantContext.runAs(t.id(), () -> sessionSvc.createSession(null, t.actor()));
    }

    private void scan(Tenant t, UUID session, String code) {
        TenantContext.runAs(t.id(), () -> sessionSvc.scan(session, code, t.location(), t.actor()));
    }

    private void as(Tenant t, Runnable r) { TenantContext.runAs(t.id(), r); }

    private void appUser(Tenant t, Runnable r) {
        TenantContext.runAs(t.id(), () -> appUserTx.executeWithoutResult(tx -> r.run()));
    }

    private void assertStatus(HttpStatus status, String message, Runnable r) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, r::run);
        assertThat(ex.getStatusCode()).isEqualTo(status);
        if (message != null) assertThat(ex.getReason()).isEqualTo(message);
    }

    private int awaitingScanCount(Tenant t) {
        return TenantContext.runAs(t.id(), () -> (Integer) linkSvc.awaitingScan().get("count"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exceptions(Tenant t, String type) {
        return TenantContext.runAs(t.id(),
            () -> (List<Map<String, Object>>) exceptionSvc.listExceptions(type, null, 0, 100).get("items"));
    }

    private List<String> exceptionIds(Tenant t, String type) {
        return exceptions(t, type).stream().map(e -> String.valueOf(e.get("shipment_id"))).toList();
    }

    private Map<String, Object> crpRow(Tenant t, String awb) {
        return TenantContext.runAs(t.id(), () -> linkSvc.listCrpReturns(0, 100)).stream()
            .filter(r -> awb.equals(r.get("tracking_number"))).findFirst().orElseThrow();
    }

    private Map<String, Object> detail(Tenant t, UUID s) {
        return TenantContext.runAs(t.id(), () -> sessionSvc.getSession(s));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parcels(Map<String, Object> d) {
        return (List<Map<String, Object>>) d.get("parcels");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> otherItems(Map<String, Object> d) {
        return (List<Map<String, Object>>) d.get("otherItems");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastScan(Map<String, Object> d) {
        return (Map<String, Object>) d.get("lastScan");
    }
}
