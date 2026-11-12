package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
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

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-12 — Scan returns on a Bosta exchange trip (type 30) expects what comes BACK (the inbound item of the
 * original order), never the replacement the AWB's own EXC order holds (prod: BROEK AWB 4955034034).
 *
 *   r1 the V227 resolver: stored id → SKU (exact; duplicates narrowed by the original order) → the original order's
 *      single other line (only when no SKU names a variant) → otherwise NULL (never guessed)
 *   e1 BROEK shape (untracked inbound line, quantity 0 after a Shopify edit, SKU in the description): the replacement is
 *      NOT expected; the card names the original order + inbound item + Bosta's note; ONE unit row for the inbound
 *      line; Arrived · sellable records the ORIGINAL order, raises Return To Receive for the sweater, handles the leg
 *   e2 tracked inbound: only the original order's inbound-variant piece is expected; the scan lands on this card;
 *      grading it resolves the exchange by inbound_variant_id (a piece of another variant doesn't)
 *   e3 unknown inbound: nothing expected, description + "scan any piece" (exchange.inboundKnown false), unit rows for
 *      every untracked line of the original except the outbound variant; a scanned original-order piece is accepted
 *      and lands on this card
 *   e4 a trip Bosta turned around (Return To Origin) keeps expecting the replacement (no exchange key)
 *   e5 Traced-booked: only the request's piece is expected (not the replacement); after grading the card is complete
 *   f1 ingest fills inbound_variant_id from the description; a stored value is never overwritten; the backfill
 *      (V227's UPDATE re-run) fills NULLs only
 *   a1 app_user: the resolver only sees the caller's tenant (another tenant's same-SKU variant never resolves), the
 *      card and expected pieces work under RLS, another tenant's session shows nothing
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeInboundScanTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired ReturnSessionService sessions;
    @Autowired ReturnService returnService;
    @Autowired ShipmentLinkService shipmentLinkService;
    @Autowired InventoryLedger ledger;
    @Autowired ExceptionService exceptions;
    @Autowired ExchangeIngestService ingest;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler jobScheduler;

    /** jeans = the replacement (outbound); sweaterL = what comes back; sweaterXL = the customer's first size. */
    record T(UUID id, UUID store, UUID location, UUID owner, UUID jeans, UUID sweaterL, UUID sweaterXL) {}
    record X(UUID exchange, UUID original, UUID exc, UUID shipment, String awb, String replacementPiece) {}

    static final String SWEATER_DESC = "x1 Contrast Trim Knit Sweater - Burgundy - L - SKU: SW-L";
    static final String JEANS_DESC = "x1 Dark Washed Relaxed Fit Denim Jeans - 34 - SKU: JN-34";

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── r1: the resolver ──────────────────────────────────────────────────────

    @Test
    void r1_resolver_storedThenSkuThenSingleOriginalLine_elseNull() {
        T t = tenant("r1");
        UUID original = order(t, "#R1");
        line(t, original, t.sweaterL(), 1);
        line(t, original, t.jeans(), 0);    // the Shopify exchange line of the replacement

        assertThat(resolve(t, t.sweaterXL(), SWEATER_DESC, original, t.jeans())).as("stored wins").isEqualTo(t.sweaterXL());
        assertThat(resolve(t, null, SWEATER_DESC, null, t.jeans())).as("exact SKU").isEqualTo(t.sweaterL());
        assertThat(resolve(t, null, "x1 Sweater - L", original, t.jeans())).as("no SKU → the one other line")
            .isEqualTo(t.sweaterL());
        assertThat(resolve(t, null, "x1 Sweater - SKU: NOPE", original, t.jeans()))
            .as("a SKU no variant has → the one other line").isEqualTo(t.sweaterL());

        line(t, original, t.sweaterXL(), 1);
        assertThat(resolve(t, null, "x1 Sweater", original, t.jeans())).as("two other lines → never guessed").isNull();
        assertThat(resolve(t, null, null, null, null)).isNull();
        assertThat(resolve(t, null, "SKU: SW-L and SKU: SW-XL", original, t.jeans())).as("two SKUs → not a SKU").isNull();

        // Several variants share the SKU → the one on the original order; none on it → NULL (no fallback).
        UUID dupe = variant(t, "Sweater dupe", "L", "SW-L");
        assertThat(resolve(t, null, SWEATER_DESC, original, t.jeans())).isEqualTo(t.sweaterL());
        UUID other = order(t, "#R1b");
        line(t, other, t.sweaterXL(), 1);
        assertThat(resolve(t, null, SWEATER_DESC, other, t.jeans())).as("SKU ambiguous, not on the original").isNull();
        assertThat(dupe).isNotNull();
    }

    // ── e1: BROEK shape ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void e1_untrackedInbound_replacementNotExpected_oneUnitRow_arrivedRecordsTheOriginalOrder() {
        T t = tenant("e1");
        UUID original = order(t, "BRK-45979-EG");
        UUID xlLine = line(t, original, t.sweaterXL(), 1);
        UUID lLine = line(t, original, t.sweaterL(), 0);     // the item coming back — quantity 0 after a Shopify edit
        line(t, original, t.jeans(), 0);
        X x = dashboardExchange(t, original, "Exchange", "delivered");
        assertThat(jdbc.queryForObject("SELECT inbound_variant_id FROM exchanges WHERE id = ?", UUID.class, x.exchange())).isNull();

        UUID s = session(t);
        Map<String, Object> scanned = TenantContext.runAs(t.id(), () -> sessions.scan(s, x.awb(), t.location(), t.owner()));
        assertThat(ids((List<Map<String, Object>>) scanned.get("expectedPieces")))
            .as("the replacement went OUT — never expected back").doesNotContain(x.replacementPiece());
        assertThat(scanned.get("description")).isEqualTo(SWEATER_DESC);

        Map<String, Object> card = parcel(t, s, x.awb());
        assertThat(ids((List<Map<String, Object>>) card.get("expectedPieces"))).isEmpty();
        assertThat(card.get("orderNumber")).isEqualTo("BRK-45979-EG");
        Map<String, Object> ex = (Map<String, Object>) card.get("exchange");
        assertThat(ex.get("inboundKnown")).isEqualTo(true);
        assertThat(ex.get("inboundVariantTitle")).isEqualTo("L");
        assertThat(ex.get("tracedRequest")).isEqualTo(false);
        assertThat(((Map<String, Object>) card.get("bosta")).get("description")).isEqualTo(SWEATER_DESC);
        List<Map<String, Object>> units = (List<Map<String, Object>>) card.get("untrackedUnits");
        assertThat(units).extracting(u -> u.get("orderItemId") + "#" + u.get("unitNo")).containsExactly(lLine + "#1");
        assertThat(units.get(0)).as("the unit row shape is unchanged").doesNotContainKey("orderId");
        assertThat(xlLine).isNotNull();

        TenantContext.runAs(t.id(), () -> sessions.unitArrived(s, x.shipment(), lLine, 1, "sellable", t.owner()));
        assertThat(jdbc.queryForObject("SELECT order_id FROM untracked_unit_intakes WHERE shipment_id = ?", UUID.class,
            x.shipment())).as("the order the item came back from").isEqualTo(original);
        List<Map<String, Object>> rtr = TenantContext.runAs(t.id(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "return_to_receive".equals(e.get("type"))).toList();
        assertThat(rtr).hasSize(1);
        assertThat((String) rtr.get(0).get("descriptionEn")).contains("Contrast Trim Knit Sweater");
        assertThat(outcome(x.shipment())).isEqualTo("untracked_units_arrived");
        assertThat(parcel(t, s, x.awb()).get("complete")).isEqualTo(true);

        TenantContext.runAs(t.id(), () -> sessions.undoUnitArrived(s, x.shipment(), lLine, 1, t.owner()));
        assertThat(outcome(x.shipment())).isNull();
    }

    // ── e2: tracked inbound piece ─────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void e2_trackedInbound_onlyItsPieceExpected_scanLandsOnTheCard_gradingResolvesByInboundVariant() {
        T t = tenant("e2");
        UUID original = order(t, "#E2");
        String sweater = trackedPiece(t, original, t.sweaterL());
        String other = trackedPiece(t, original, t.sweaterXL());
        X x = dashboardExchange(t, original, "Exchange", "delivered");
        jdbc.update("UPDATE exchanges SET status = 'matched' WHERE id = ?", x.exchange());

        UUID s = session(t);
        scan(t, s, x.awb());
        Map<String, Object> card = parcel(t, s, x.awb());
        assertThat(ids((List<Map<String, Object>>) card.get("expectedPieces"))).containsExactly(sweater);
        assertThat((List<?>) card.get("untrackedUnits")).isEmpty();

        // Another variant of the original, scanned and restocked first, does not resolve the exchange.
        scan(t, s, barcode(other));
        TenantContext.runAs(t.id(), () -> sessions.disposition(s, other, "restock", null, t.location(), t.owner(), "owner"));
        assertThat(exchangeStatus(x)).isEqualTo("matched");

        scan(t, s, barcode(sweater));
        card = parcel(t, s, x.awb());
        assertThat(ids((List<Map<String, Object>>) card.get("scannedItems"), "piece_id")).contains(sweater);
        assertThat(ids((List<Map<String, Object>>) card.get("expectedPieces"))).isEmpty();
        TenantContext.runAs(t.id(), () -> sessions.disposition(s, sweater, "restock", null, t.location(), t.owner(), "owner"));
        assertThat(exchangeStatus(x)).isEqualTo("return_received");
        assertThat(jdbc.queryForObject("SELECT variant_id FROM pieces WHERE id = ?", UUID.class, sweater))
            .as("the piece restocked is the returned one").isEqualTo(t.sweaterL());
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, x.replacementPiece()))
            .as("the replacement stays with the customer").isEqualTo("delivered");
    }

    // ── e3: inbound unknown — never guessed ───────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void e3_unknownInbound_nothingExpected_unitsForEveryOtherUntrackedLine_anyOriginalPieceAccepted() {
        T t = tenant("e3");
        UUID original = order(t, "#E3");
        UUID xl = line(t, original, t.sweaterXL(), 1);
        UUID l = line(t, original, t.sweaterL(), 1);
        line(t, original, t.jeans(), 0);
        String tracked = trackedPiece(t, original, t.sweaterL());
        X x = dashboardExchange(t, original, "Exchange", "delivered");
        jdbc.update("UPDATE exchanges SET inbound_description = 'x1 Knit Sweater', " +
            "raw = jsonb_set(raw, '{returnSpecs,packageDetails,description}', '\"x1 Knit Sweater\"') WHERE id = ?", x.exchange());
        jdbc.update("UPDATE shipments SET raw = jsonb_set(raw, '{returnSpecs,packageDetails,description}', '\"x1 Knit Sweater\"') " +
            "WHERE id = ?", x.shipment());
        // The original now has a tracked piece of a third line → still two candidate variants: never guessed.
        line(t, original, t.sweaterXL(), 1);

        UUID s = session(t);
        scan(t, s, x.awb());
        Map<String, Object> card = parcel(t, s, x.awb());
        Map<String, Object> ex = (Map<String, Object>) card.get("exchange");
        assertThat(ex.get("inboundKnown")).isEqualTo(false);
        assertThat(ex.get("originalOrderNumber")).isEqualTo("#E3");
        assertThat(ids((List<Map<String, Object>>) card.get("expectedPieces"))).isEmpty();
        assertThat(((Map<String, Object>) card.get("bosta")).get("description")).isEqualTo("x1 Knit Sweater");
        assertThat((List<Map<String, Object>>) card.get("untrackedUnits")).extracting(u -> u.get("orderItemId"))
            .contains(xl.toString(), l.toString());
        assertThat((List<Map<String, Object>>) card.get("untrackedUnits")).extracting(u -> u.get("variantTitle"))
            .doesNotContain("34");
        assertThat(jdbc.queryForObject("SELECT inbound_variant_id FROM exchanges WHERE id = ?", UUID.class, x.exchange())).isNull();

        scan(t, s, barcode(tracked));
        card = parcel(t, s, x.awb());
        assertThat(ids((List<Map<String, Object>>) card.get("scannedItems"), "piece_id")).containsExactly(tracked);
    }

    // ── e4: turned around → the replacement comes back ────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void e4_returnToOrigin_keepsExpectingTheReplacement() {
        T t = tenant("e4");
        UUID original = order(t, "#E4");
        trackedPiece(t, original, t.sweaterL());
        X x = dashboardExchange(t, original, "Return To Origin", "returned");
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", x.replacementPiece());

        UUID s = session(t);
        scan(t, s, x.awb());
        Map<String, Object> card = parcel(t, s, x.awb());
        assertThat(ids((List<Map<String, Object>>) card.get("expectedPieces"))).containsExactly(x.replacementPiece());
        assertThat(card).doesNotContainKey("exchange");
    }

    // ── e5: Traced-booked exchange ────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void e5_tracedBooked_onlyTheRequestPiece_cardCompletesAfterGrading() {
        T t = tenant("e5");
        UUID original = order(t, "#E5");
        String old = trackedPiece(t, original, t.sweaterL());
        X x = dashboardExchange(t, original, "Exchange", "delivered");
        UUID request = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at, booking_status, " +
            "  bosta_tracking_number) VALUES (?, ?, 'exchange', 'pickup_booked', ?, now(), 'booked', ?) RETURNING id",
            UUID.class, t.id(), original, reference(), x.awb());
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, replacement_variant_id, reason_code, " +
            "item_status, active) VALUES (?, ?, ?, ?, ?, 'wrong_size', 'awaiting', true)", t.id(), request, old, t.sweaterL(), t.jeans());
        jdbc.update("UPDATE exchanges SET return_request_id = ?, inbound_variant_id = ?, status = 'matched' WHERE id = ?",
            request, t.sweaterL(), x.exchange());

        UUID s = session(t);
        scan(t, s, x.awb());
        Map<String, Object> card = parcel(t, s, x.awb());
        assertThat(ids((List<Map<String, Object>>) card.get("expectedPieces"))).containsExactly(old);
        assertThat(((Map<String, Object>) card.get("exchange")).get("tracedRequest")).isEqualTo(true);
        Map<String, Object> session = TenantContext.runAs(t.id(), () -> sessions.getSession(s));
        assertThat(ids((List<Map<String, Object>>) session.get("expectedPieces"))).containsExactly(old);

        scan(t, s, barcode(old));
        TenantContext.runAs(t.id(), () -> sessions.disposition(s, old, "restock", null, t.location(), t.owner(), "owner"));
        card = parcel(t, s, x.awb());
        assertThat(ids((List<Map<String, Object>>) card.get("expectedPieces"))).isEmpty();
        assertThat(card.get("complete")).isEqualTo(true);
    }

    // ── f1: fill on ingest, never overwrite, backfill ─────────────────────────

    @Test
    void f1_fillOnIngest_neverOverwrites_backfillFillsNullsOnly() throws Exception {
        T t = tenant("f1");
        UUID original = order(t, "#F1");
        line(t, original, t.sweaterL(), 1);
        X filled = dashboardExchange(t, original, "Exchange", "delivered");
        X picked = dashboardExchange(t, original, "Exchange", "delivered");
        jdbc.update("UPDATE exchanges SET inbound_variant_id = ? WHERE id = ?", t.sweaterXL(), picked.exchange());

        // Ingest (the webhook upsert) fills from the description.
        String raw = jdbc.queryForObject("SELECT raw::text FROM exchanges WHERE id = ?", String.class, filled.exchange());
        TenantContext.runAs(t.id(), () -> ingest.upsertFromDelivery(t.id(), filled.awb(),
            com.traceability.integrations.bosta.BostaDelivery.fromRaw(filled.awb(),
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw))));
        assertThat(inbound(filled)).isEqualTo(t.sweaterL());
        // A stored (a person's) pick is never overwritten.
        assertThat(TenantContext.runAs(t.id(), () -> ExchangeInbound.fill(jdbc, t.id(), picked.awb()))).isZero();
        assertThat(inbound(picked)).isEqualTo(t.sweaterXL());

        // The backfill (V227's UPDATE, re-run: idempotent): fills NULLs, leaves stored values.
        jdbc.update("UPDATE exchanges SET inbound_variant_id = NULL WHERE id = ?", filled.exchange());
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V227__exchange_inbound_variant.sql"));
        jdbc.execute(migration);
        jdbc.execute(migration);
        assertThat(inbound(filled)).isEqualTo(t.sweaterL());
        assertThat(inbound(picked)).isEqualTo(t.sweaterXL());
    }

    // ── a1: app_user ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void a1_appUser_resolverSeesOnlyItsTenant_cardWorksUnderRls_otherTenantSeesNothing() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ReturnSessionService appSessions = new ReturnSessionService(appJdbc, ledger, returnService, shipmentLinkService, Clock.systemUTC());

        T a = tenant("a1a");
        T b = tenant("a1b");
        // B (and every other tenant) has a variant with SKU SW-L; A's own is renamed away → A must NOT resolve to B's.
        jdbc.update("UPDATE variants SET sku = 'GONE-' || id WHERE id = ?", a.sweaterL());
        assertThat(b.sweaterL()).isNotNull();
        UUID original = order(a, "#A1");
        UUID lLine = line(a, original, a.sweaterL(), 1);
        line(a, original, a.sweaterXL(), 1);
        X x = dashboardExchange(a, original, "Exchange", "delivered");
        UUID resolved = TenantContext.runAs(a.id(), () -> appTx.execute(st -> appJdbc.queryForObject(
            "SELECT " + ExchangeInbound.variantSql("e") + " FROM exchanges e WHERE e.id = ?", UUID.class, x.exchange())));
        assertThat(resolved).as("another tenant's same-SKU variant never resolves; two other lines → unknown").isNull();

        jdbc.update("UPDATE variants SET sku = 'SW-L-A' WHERE id = ?", a.sweaterL());
        jdbc.update("UPDATE exchanges SET inbound_description = 'x1 Sweater - SKU: SW-L-A' WHERE id = ?", x.exchange());
        UUID sa = TenantContext.runAs(a.id(), () -> appTx.execute(st -> appSessions.createSession(null, a.owner())));
        TenantContext.runAs(a.id(), () -> appTx.execute(st -> appSessions.scan(sa, x.awb(), a.location(), a.owner())));
        Map<String, Object> detail = TenantContext.runAs(a.id(), () -> appTx.execute(st -> appSessions.getSession(sa)));
        Map<String, Object> card = ((List<Map<String, Object>>) detail.get("parcels")).get(0);
        assertThat(((Map<String, Object>) card.get("exchange")).get("inboundKnown")).isEqualTo(true);
        assertThat((List<Map<String, Object>>) card.get("untrackedUnits")).extracting(u -> u.get("orderItemId"))
            .containsExactly(lLine.toString());
        assertThat(ids((List<Map<String, Object>>) detail.get("expectedPieces"))).doesNotContain(x.replacementPiece());

        // Tenant B, app_user: A's exchange, order lines and units are invisible.
        Integer seen = TenantContext.runAs(b.id(), () -> appTx.execute(st -> appJdbc.queryForObject(
            "SELECT count(*) FROM exchanges", Integer.class)));
        assertThat(seen).isZero();
        Integer noTenant = appTx.execute(st -> appJdbc.queryForObject("SELECT count(*) FROM exchanges", Integer.class));
        assertThat(noTenant).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private UUID resolve(T t, UUID stored, String desc, UUID original, UUID outbound) {
        return jdbc.queryForObject("SELECT exchange_inbound_variant(?, ?, ?, ?, ?)", UUID.class,
            t.id(), stored, desc, original, outbound);
    }

    private UUID inbound(X x) {
        return jdbc.queryForObject("SELECT inbound_variant_id FROM exchanges WHERE id = ?", UUID.class, x.exchange());
    }

    private String exchangeStatus(X x) {
        return jdbc.queryForObject("SELECT status FROM exchanges WHERE id = ?", String.class, x.exchange());
    }

    private String outcome(UUID shipment) {
        return jdbc.queryForObject("SELECT return_intake_outcome FROM shipments WHERE id = ?", String.class, shipment);
    }

    private String barcode(String piece) {
        return jdbc.queryForObject("SELECT barcode FROM pieces WHERE id = ?", String.class, piece);
    }

    private static List<String> ids(List<Map<String, Object>> rows) { return ids(rows, "id"); }

    private static List<String> ids(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(r -> String.valueOf(r.get(key))).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parcel(T t, UUID s, String awb) {
        Map<String, Object> detail = TenantContext.runAs(t.id(), () -> sessions.getSession(s));
        return ((List<Map<String, Object>>) detail.get("parcels")).stream()
            .filter(p -> awb.equals(p.get("awb"))).findFirst().orElseThrow();
    }

    private UUID session(T t) {
        return TenantContext.runAs(t.id(), () -> sessions.createSession(null, t.owner()));
    }

    private void scan(T t, UUID s, String code) {
        TenantContext.runAs(t.id(), () -> sessions.scan(s, code, t.location(), t.owner()));
    }

    private UUID order(T t, String number) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/XI" + k + "-" + t.id(), number);
    }

    private UUID line(T t, UUID order, UUID variant, int qty) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            id, t.id(), order, variant, qty);
        return id;
    }

    /** A delivered piece on a line of its own, packed allocation. */
    private String trackedPiece(T t, UUID order, UUID variant) {
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, " +
            "  current_location_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'X' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, ?, now())",
            piece, t.id(), variant, "XI-" + piece, piece, order, t.location());
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.id(), order, variant);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            t.id(), item, piece);
        return piece;
    }

    /**
     * A dashboard Bosta exchange as ExchangeService.commit leaves it: the EXC-… order with the replacement (jeans)
     * piece delivered to the customer, the type 30 shipment as that order's forward leg, the exchanges row matched to
     * {@code original} with inbound_variant_id NULL.
     */
    private X dashboardExchange(T t, UUID original, String bostaType, String internalState) {
        String awb = String.valueOf(4_955_000_000L + seq.incrementAndGet());
        String raw = "{\"type\": {\"code\": 30, \"value\": \"" + bostaType + "\"}, " +
            "\"state\": {\"code\": 46, \"value\": \"Returned to business\"}, " +
            "\"specs\": {\"packageDetails\": {\"itemsCount\": 1, \"description\": \"" + JEANS_DESC + "\"}}, " +
            "\"returnSpecs\": {\"packageDetails\": {\"itemsCount\": 1, \"description\": \"" + SWEATER_DESC + "\"}}}";
        UUID exc = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, now()) RETURNING id",
            UUID.class, t.id(), t.store(), "internal:exchange:" + awb, "EXC-" + awb);
        String replacement = trackedPiece(t, exc, t.jeans());
        UUID shipment = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', ?::jsonb) RETURNING id",
            UUID.class, t.id(), exc, awb, internalState, raw);
        UUID exchange = jdbc.queryForObject(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, raw, " +
            "  outbound_order_id, outbound_variant_id, matched_order_id, match_method, matched_at) " +
            "VALUES (?, ?, 'mapped', ?, ?, ?::jsonb, ?, ?, ?, 'reference', now()) RETURNING id",
            UUID.class, t.id(), awb, JEANS_DESC, SWEATER_DESC, raw, exc, t.jeans(), original);
        return new X(exchange, original, exc, shipment, awb, replacement);
    }

    private UUID variant(T t, String product, String title, String sku) {
        UUID p = UUID.randomUUID(), v = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            p, t.id(), t.store(), "P-" + p, product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, t.id(), p, "V-" + v, title, sku);
        return v;
    }

    private T tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Exch " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, "exch-" + name + "-" + id.toString().substring(0, 6) + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            owner, id, "owner-" + owner + "@test.local");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        T t0 = new T(id, store, location, owner, null, null, null);
        UUID jeans = variant(t0, "Dark Washed Relaxed Fit Denim Jeans", "34", "JN-34");
        UUID sweaterL = variant(t0, "Contrast Trim Knit Sweater", "L", "SW-L");
        UUID sweaterXL = variant(t0, "Contrast Trim Knit Sweater", "XL", "SW-XL");
        return new T(id, store, location, owner, jeans, sweaterL, sweaterXL);
    }
}
