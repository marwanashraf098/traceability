package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
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

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issue 1 (V152, design signed off 2026-10-08) — Scan returns: untracked parcel items, one row per
 * unit, through the real ReturnSessionService paths.
 *
 *   p1 untracked order, courier return, no request → one row per unit (qty 2 → 2 rows); mixed order
 *      keeps its tracked pieces AND lists the untracked units
 *   p2 returned-to-sender forward leg → Bosta status/note on the card, Mark parcel received allowed,
 *      raises the leg's Return To Receive; a plain (non-RTO) forward leg still refused
 *   p3 Arrived · sellable → exactly one Return To Receive; a double tap adds nothing; Undo removes it
 *      and reopens the leg; damaged raises nothing; the live-slot unique index refuses a second row
 *   p4 partial return: 2 of 3 marked → the parcel is handled (leg stamped untracked_units_arrived);
 *      the whole-parcel mark is refused once a unit is marked
 *   p5 a live intake row IS scan evidence under the canonical returnLegScanEvidenceSql
 *   p6 none of it writes to Shopify
 *   p7 via phone: a unit on a parcel whose AWB came from the paired phone carries the marker
 *   x1 cross-tenant on a real app_user connection, with a same-tenant positive control
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UntrackedParcelUnitsTest {

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
    @Autowired ReturnSessionService sessions;
    @Autowired ReturnService returnService;
    @Autowired ShipmentLinkService shipmentLinkService;
    @Autowired InventoryLedger ledger;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler jobScheduler;

    record T(UUID id, UUID store, UUID location, UUID owner, UUID shirt, UUID scarf) {}
    record Parcel(UUID order, UUID shipment, String awb) {}

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── p1: one row per unit ───────────────────────────────────────────────────

    @Test
    void p1_untrackedCourierReturn_noRequest_listsOneRowPerUnit_mixedKeepsTrackedPieces() {
        T t = tenant("p1");
        Parcel p = courierReturn(t);
        UUID shirtLine = line(t, p.order(), t.shirt(), 2);
        UUID scarfLine = line(t, p.order(), t.scarf(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());

        Map<String, Object> card = parcel(t, s, p.awb());
        List<Map<String, Object>> units = units(card);
        assertThat(units).hasSize(3);
        assertThat(units).extracting(u -> u.get("orderItemId") + "#" + u.get("unitNo"))
            .containsExactlyInAnyOrder(shirtLine + "#1", shirtLine + "#2", scarfLine + "#1");
        assertThat(units).allSatisfy(u -> assertThat(u.get("intakeId")).isNull());
        assertThat(card.get("canMarkReceived")).isEqualTo(true);
        assertThat(card.get("complete")).isEqualTo(false);

        // Mixed: the same kind of parcel on an order with a tracked piece too → both.
        Parcel m = courierReturn(t);
        trackedLine(t, m.order(), t.scarf());
        UUID mixedLine = line(t, m.order(), t.shirt(), 1);
        scan(t, s, m.awb());
        Map<String, Object> mixed = parcel(t, s, m.awb());
        assertThat(units(mixed)).extracting(u -> u.get("orderItemId")).containsExactly(mixedLine.toString());
        assertThat((List<?>) mixed.get("expectedPieces")).hasSize(1);
        assertThat(mixed.get("canMarkReceived")).as("the whole-parcel mark is for untracked orders only").isEqualTo(false);
    }

    // ── p2: returned-to-sender forward leg ─────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void p2_returnedToSenderForwardLeg_showsBosta_canBeMarkedReceived_plainForwardRefused() {
        T t = tenant("p2");
        Parcel rto = forwardLeg(t, "Return to Origin");
        line(t, rto.order(), t.shirt(), 1);
        Parcel plain = forwardLeg(t, "Send");
        line(t, plain.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, rto.awb());
        scan(t, s, plain.awb());

        Map<String, Object> card = parcel(t, s, rto.awb());
        assertThat(card.get("returnedToSender")).isEqualTo(true);
        Map<String, Object> bosta = (Map<String, Object>) card.get("bosta");
        assertThat(bosta.get("state")).isEqualTo("Returned to business");
        assertThat(bosta.get("description")).isEqualTo("Linen Shirt x 1");
        assertThat(units(card)).hasSize(1);
        assertThat(card.get("canMarkReceived")).isEqualTo(true);
        assertThat(units(parcel(t, s, plain.awb()))).as("a plain forward leg gets no unit rows").isEmpty();

        TenantContext.runAs(t.id(), () -> sessions.markReceived(s, rto.shipment(), t.owner()));
        assertThat(outcome(rto.shipment())).isEqualTo("received_untracked");
        assertThat(toReceive(t)).extracting(e -> e.get("subject_key")).containsExactly(rto.shipment().toString());

        assertThatThrownBy(() -> TenantContext.runAs(t.id(), () -> sessions.markReceived(s, plain.shipment(), t.owner())))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(outcome(plain.shipment())).isNull();
    }

    // ── p3: Return To Receive once, double tap, undo, unique slot ──────────────

    @Test
    void p3_sellableRaisesReturnToReceiveOnce_doubleTapNoDuplicate_undoRemoves_damagedRaisesNothing() {
        T t = tenant("p3");
        Parcel p = courierReturn(t);
        UUID shirtLine = line(t, p.order(), t.shirt(), 2);
        UUID s = session(t);
        scan(t, s, p.awb());

        arrived(t, s, p, shirtLine, 1, "sellable");
        arrived(t, s, p, shirtLine, 1, "sellable");   // double tap
        assertThat(liveRows(p.shipment())).isEqualTo(1);
        List<Map<String, Object>> rtr = toReceive(t);
        assertThat(rtr).hasSize(1);
        assertThat((String) rtr.get(0).get("descriptionEn")).contains("Linen Shirt").contains("came back sellable");
        assertThat(outcome(p.shipment())).isEqualTo("untracked_units_arrived");

        arrived(t, s, p, shirtLine, 2, "damaged");
        assertThat(toReceive(t)).as("damaged is recorded only").hasSize(1);

        // The live-slot index itself refuses a second live row for the same unit.
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO untracked_unit_intakes (tenant_id, return_session_id, shipment_id, order_id, order_item_id, " +
            "unit_no, condition, actor_user_id) VALUES (?, ?, ?, ?, ?, 1, 'sellable', ?)",
            t.id(), s, p.shipment(), p.order(), shirtLine, t.owner()))
            .isInstanceOf(DataIntegrityViolationException.class);

        undo(t, s, p, shirtLine, 1);
        assertThat(toReceive(t)).isEmpty();
        assertThat(outcome(p.shipment())).as("one unit still marked → still handled").isEqualTo("untracked_units_arrived");
        undo(t, s, p, shirtLine, 2);
        assertThat(outcome(p.shipment())).as("undo of the last mark reopens the parcel").isNull();
        assertThat(liveRows(p.shipment())).isZero();
    }

    // ── p4: partial return is handled; whole-parcel mark refused after a unit ──

    @Test
    void p4_partialReturn_isHandled_wholeParcelMarkRefusedOnceAUnitIsMarked() {
        T t = tenant("p4");
        Parcel p = courierReturn(t);
        UUID shirtLine = line(t, p.order(), t.shirt(), 2);
        line(t, p.order(), t.scarf(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());

        arrived(t, s, p, shirtLine, 1, "sellable");
        arrived(t, s, p, shirtLine, 2, "damaged");
        Map<String, Object> card = parcel(t, s, p.awb());
        assertThat(card.get("unitsIn")).isEqualTo(2L);
        assertThat(units(card)).hasSize(3);
        assertThat(card.get("complete")).as("2 of 3 in — handled").isEqualTo(true);
        assertThat(card.get("canMarkReceived")).isEqualTo(false);
        assertThatThrownBy(() -> TenantContext.runAs(t.id(), () -> sessions.markReceived(s, p.shipment(), t.owner())))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));

        TenantContext.runAs(t.id(), () -> sessions.close(s, t.owner()));   // never blocked by unmarked units
        assertThat(jdbc.queryForObject("SELECT status FROM return_sessions WHERE id = ?", String.class, s)).isEqualTo("closed");
    }

    // ── p5: the canonical evidence rule ───────────────────────────────────────

    @Test
    void p5_liveIntakeRow_isScanEvidence_forItsLeg_andItsSession() {
        T t = tenant("p5");
        Parcel p = courierReturn(t);
        UUID shirtLine = line(t, p.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());
        assertThat(evidence(p.shipment(), null)).isFalse();

        arrived(t, s, p, shirtLine, 1, "sellable");
        assertThat(evidence(p.shipment(), null)).isTrue();
        assertThat(evidence(p.shipment(), s)).isTrue();
        assertThat(evidence(p.shipment(), UUID.randomUUID())).as("session-scoped").isFalse();

        undo(t, s, p, shirtLine, 1);
        assertThat(evidence(p.shipment(), null)).isFalse();
    }

    // ── p6: no Shopify ────────────────────────────────────────────────────────

    @Test
    void p6_noShopifyWrites_fromAnyOfTheseActions() {
        T t = tenant("p6");
        Parcel p = courierReturn(t);
        UUID shirtLine = line(t, p.order(), t.shirt(), 2);
        Parcel rto = forwardLeg(t, "Return to Origin");
        line(t, rto.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());
        scan(t, s, rto.awb());

        arrived(t, s, p, shirtLine, 1, "sellable");
        arrived(t, s, p, shirtLine, 2, "damaged");
        undo(t, s, p, shirtLine, 2);
        TenantContext.runAs(t.id(), () -> sessions.markReceived(s, rto.shipment(), t.owner()));
        TenantContext.runAs(t.id(), () -> sessions.undoMarkReceived(s, rto.shipment(), t.owner()));
        TenantContext.runAs(t.id(), () -> sessions.close(s, t.owner()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?",
            Integer.class, t.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pieces WHERE tenant_id = ?", Integer.class, t.id())).isZero();
        verifyNoInteractions(shopifyGateway);
    }

    // ── p7: via phone ─────────────────────────────────────────────────────────

    @Test
    void p7_unitOnAPhoneScannedParcel_carriesTheViaPhoneMarker() {
        T t = tenant("p7");
        Parcel p = courierReturn(t);
        UUID shirtLine = line(t, p.order(), t.shirt(), 1);
        UUID s = session(t);
        scan(t, s, p.awb());
        // PhoneScanSource verified this AWB scan (fixture: its outcome, as scanAwb records it).
        jdbc.update("UPDATE return_session_shipments SET via_phone = true WHERE session_id = ? AND awb = ?", s, p.awb());

        arrived(t, s, p, shirtLine, 1, "sellable");
        assertThat(jdbc.queryForObject("SELECT via_phone FROM untracked_unit_intakes WHERE shipment_id = ?", Boolean.class, p.shipment()))
            .isTrue();
        assertThat(units(parcel(t, s, p.awb())).get(0).get("viaPhone")).isEqualTo(true);
    }

    // ── x1: cross-tenant, app_user ─────────────────────────────────────────────

    @Test
    void x1_crossTenant_appUser_noRowsNoWrites_sameTenantPositiveControl() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ReturnSessionService appSessions = new ReturnSessionService(appJdbc, ledger, returnService, shipmentLinkService, Clock.systemUTC());

        T a = tenant("xa");
        T b = tenant("xb");
        Parcel mine = courierReturn(a);
        UUID mineLine = line(a, mine.order(), a.shirt(), 1);
        Parcel theirs = courierReturn(b);
        UUID theirsLine = line(b, theirs.order(), b.shirt(), 1);
        UUID sa = session(a);
        UUID sb = session(b);
        scan(a, sa, mine.awb());
        scan(b, sb, theirs.awb());
        arrived(b, sb, theirs, theirsLine, 1, "sellable");

        // Tenant A, app_user: B's rows are invisible, B's shipment is a 404, nothing written.
        Integer visible = TenantContext.runAs(a.id(), () -> appTx.execute(x ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_intakes", Integer.class)));
        assertThat(visible).isZero();
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appTx.execute(x -> {
            appSessions.unitArrived(sa, theirs.shipment(), theirsLine, 1, "sellable", a.owner()); return null; })))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThat(liveRows(theirs.shipment())).isEqualTo(1);
        // No tenant set at all → nothing.
        Integer noTenant = appTx.execute(x -> appJdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_intakes", Integer.class));
        assertThat(noTenant).isZero();

        // Positive control: tenant A marks its own unit on app_user and sees exactly that row.
        TenantContext.runAs(a.id(), () -> appTx.execute(x -> {
            appSessions.unitArrived(sa, mine.shipment(), mineLine, 1, "damaged", a.owner()); return null; }));
        Integer own = TenantContext.runAs(a.id(), () -> appTx.execute(x ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_intakes", Integer.class)));
        assertThat(own).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_policies WHERE tablename = 'untracked_unit_intakes' " +
            "AND policyname = 'tenant_isolation'", Integer.class)).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void arrived(T t, UUID s, Parcel p, UUID line, int unit, String condition) {
        TenantContext.runAs(t.id(), () -> sessions.unitArrived(s, p.shipment(), line, unit, condition, t.owner()));
    }

    private void undo(T t, UUID s, Parcel p, UUID line, int unit) {
        TenantContext.runAs(t.id(), () -> sessions.undoUnitArrived(s, p.shipment(), line, unit, t.owner()));
    }

    private List<Map<String, Object>> toReceive(T t) {
        return TenantContext.runAs(t.id(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "return_to_receive".equals(e.get("type"))).toList();
    }

    private boolean evidence(UUID shipment, UUID session) {
        String expr = session == null ? null : "'" + session + "'::uuid";
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT " + ShipmentLinkService.returnLegScanEvidenceSql(expr) + " FROM shipments s WHERE s.id = ?",
            Boolean.class, shipment));
    }

    private int liveRows(UUID shipment) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM untracked_unit_intakes WHERE shipment_id = ? AND undone_at IS NULL",
            Integer.class, shipment);
    }

    private String outcome(UUID shipment) {
        return jdbc.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class, shipment);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parcel(T t, UUID s, String awb) {
        Map<String, Object> detail = TenantContext.runAs(t.id(), () -> sessions.getSession(s));
        return ((List<Map<String, Object>>) detail.get("parcels")).stream()
            .filter(p -> awb.equals(p.get("awb"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> units(Map<String, Object> card) {
        return (List<Map<String, Object>>) card.get("untrackedUnits");
    }

    private UUID session(T t) {
        return TenantContext.runAs(t.id(), () -> sessions.createSession(null, t.owner()));
    }

    private void scan(T t, UUID s, String awb) {
        TenantContext.runAs(t.id(), () -> sessions.scan(s, awb, t.location(), t.owner()));
    }

    private UUID order(T t) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/UP" + k + "-" + t.id(), "#UP" + k);
    }

    /** A courier-return leg Bosta reported back at the warehouse, no request on it. */
    private Parcel courierReturn(T t) {
        UUID o = order(t);
        String awb = String.valueOf(7_300_000_000L + seq.incrementAndGet());
        UUID id = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at, " +
            "  raw) VALUES (?, ?, 'bosta', ?, 'returned'::shipment_internal_state, 'return', now() - interval '1 day', " +
            "  '{\"returnSpecs\": {\"packageDetails\": {\"itemsCount\": 1, \"description\": \"Linen Shirt x 1\"}}}'::jsonb) RETURNING id",
            UUID.class, t.id(), o, awb);
        return new Parcel(o, id, awb);
    }

    /** A forward leg back at the merchant ('returned'), of the given Bosta type. */
    private Parcel forwardLeg(T t, String bostaType) {
        UUID o = order(t);
        String awb = String.valueOf(7_400_000_000L + seq.incrementAndGet());
        UUID id = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'returned'::shipment_internal_state, 'forward', ?::jsonb) RETURNING id",
            UUID.class, t.id(), o, awb,
            "{\"type\": {\"code\": 20, \"value\": \"" + bostaType + "\"}, \"state\": {\"code\": 46, \"value\": \"Returned to business\"}, " +
            "\"changedToRTODate\": \"2026-10-05T10:00:00Z\", \"specs\": {\"packageDetails\": {\"itemsCount\": 1, \"description\": \"Linen Shirt x 1\"}}}");
        return new Parcel(o, id, awb);
    }

    private UUID line(T t, UUID order, UUID variant, int qty) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            id, t.id(), order, variant, qty);
        return id;
    }

    /** A tracked line: a delivered piece with a packed allocation. */
    private void trackedLine(T t, UUID order, UUID variant) {
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'U' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
            piece, t.id(), variant, "UP-" + piece, piece, order);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.id(), order, variant);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            t.id(), item, piece);
    }

    private T tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Units " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, "units-" + name + "-" + id.toString().substring(0, 6) + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            owner, id, "owner-" + owner + "@test.local");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID shirtP = UUID.randomUUID(), scarfP = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            shirtP, id, store, "P-shirt-" + id);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Wool Scarf', 'active')",
            scarfP, id, store, "P-scarf-" + id);
        UUID shirt = UUID.randomUUID(), scarf = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'White / M', 'SH-M')",
            shirt, id, shirtP, "V-" + shirt);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'Grey', 'SC-G')",
            scarf, id, scarfP, "V-" + scarf);
        return new T(id, store, location, owner, shirt, scarf);
    }
}
