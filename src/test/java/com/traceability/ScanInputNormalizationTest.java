package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.PickupSessionService;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.StockTakeService;
import com.traceability.inventory.TransferService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1 (approved 2026-10-16) — a hardware scanner with Caps Lock on, or on an Arabic OS keyboard
 * layout, types {@code p000123} / {@code P٠٠٠١٢٣} / {@code ؛000123} for the label {@code P000123}, and
 * {@code ٨٤٨٤٨٠٥٦٩٩} for a Bosta AWB. Every piece resolver and every tracking path must still find
 * the piece / shipment, through the real services; raw evidence columns keep what was sent; a
 * garbled ULID resolves to nothing; another tenant's piece stays out of reach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanInputNormalizationTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate     rest;
    @Autowired JdbcTemplate         jdbc;
    @Autowired JwtService           jwt;
    @Autowired FulfillService       fulfill;
    @Autowired StockTakeService     stockTake;
    @Autowired TransferService      transfers;
    @Autowired ReturnSessionService returns;
    @Autowired PickupSessionService pickups;
    @MockBean  BostaGateway         bostaGateway;
    @MockBean  JobScheduler         jobScheduler;

    /** A label code ("P" + 6 digits) as a scanner types it on each keyboard setup. */
    record Label(String digits) {
        String canonical()   { return "P" + digits; }
        String capsLock()    { return "p" + digits; }                     // US layout, Caps Lock on
        String macArabic()   { return "[" + arabic(digits); }             // Shift+P on macOS "Arabic"
        String macArabicPc() { return "؛" + arabic(digits); }             // Shift+P on macOS "Arabic - PC"
        String win101()      { return "؛" + digits; }                     // Shift+P on Windows Arabic 101
        String pDigitsArabic() { return "P" + arabic(digits); }           // P reached, digits Arabic-Indic
        List<String> typedForms() { return List.of(capsLock(), pDigitsArabic(), win101(), macArabic(), macArabicPc()); }
    }

    static String arabic(String asciiDigits) {
        StringBuilder b = new StringBuilder();
        for (char c : asciiDigits.toCharArray()) b.append((char) ('٠' + (c - '0')));
        return b.toString();
    }

    /** The PC-<ULID> label typed on macOS "Arabic" (Step 0 harness): ambiguous as text, must not resolve. */
    static final String GARBLED_ULID_MAC = "[ئ-٠١آ'٤٫٧ؤ٩ًَ٢ِ٣ٍ٥ُ٦ٌ٨ء٠أإ«";

    // ── Fixtures ──────────────────────────────────────────────────────────────

    final class Shop {
        final PackFixtures f;
        final UUID worker;
        final String token;
        final UUID variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            worker = f.user("Mona", "worker");
            token = jwt.issueAccessToken(worker, f.tenant, "worker");
            variant = f.variant("Cotton tee", "CT-BLK-M", null);
        }

        /** An available piece at the shop's location with a chosen short code. */
        Label piece() { return piece("available"); }

        Label piece(String status) {
            String digits = String.format("%06d", ThreadLocalRandom.current().nextInt(100_000, 999_999));
            String id = UlidGenerator.generate();
            jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, " +
                "                    last_event_at, last_user_id) " +
                "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, now(), ?)",
                id, f.tenant, variant, "PC-" + id, "P" + digits, status, f.location, worker);
            jdbc.update(
                "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
                "VALUES (?, ?, 'received', ?, ?, NULL, 'available'::piece_status)",
                f.tenant, id, worker, f.location);
            return new Label(digits);
        }

        String pieceId(Label l) { return f.pieceId(l.canonical()); }

        <T> T as(java.util.concurrent.Callable<T> task) { return TenantContext.runAs(f.tenant, task); }
    }

    // ── Fulfill pick scan ─────────────────────────────────────────────────────

    @Test
    void fulfillScan_resolvesEveryTypedFormOfTheShortCode() {
        Shop s = new Shop("Fulfill");
        for (String typedFormKind : List.of("caps", "pDigitsArabic", "win101", "macArabic", "macArabicPc")) {
            Label label = s.piece();
            String typed = switch (typedFormKind) {
                case "caps" -> label.capsLock();
                case "pDigitsArabic" -> label.pDigitsArabic();
                case "win101" -> label.win101();
                case "macArabic" -> label.macArabic();
                default -> label.macArabicPc();
            };
            UUID order = s.f.order("#F-" + typedFormKind, 0);
            s.f.item(order, s.variant, 1);
            FulfillService.ScanResult r = s.as(() -> fulfill.scan(order, typed, s.worker));
            assertThat(r.success()).as(typedFormKind + " " + typed + " → " + r.code()).isTrue();
            assertThat(r.pieceId()).isEqualTo(s.pieceId(label));
        }
    }

    @Test
    void fulfillScan_garbledUlid_resolvesNothing() {
        Shop s = new Shop("FulfillGarbled");
        s.piece();
        UUID order = s.f.order("#G-1", 0);
        s.f.item(order, s.variant, 1);
        FulfillService.ScanResult r = s.as(() -> fulfill.scan(order, GARBLED_ULID_MAC, s.worker));
        assertThat(r.success()).isFalse();
        assertThat(r.code()).isEqualTo("PIECE_NOT_FOUND");
    }

    // ── Pack session (waybill mode): Arabic-digit waybill, Arabic-layout piece, raw evidence kept ─

    @Test
    void packSession_arabicDigitWaybillOpens_arabicLayoutPieceScans_rawScansKeptVerbatim() {
        Shop s = new Shop("Pack");
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", s.f.tenant);
        UUID order = s.f.order("#P-1", 0);
        s.f.item(order, s.variant, 1);
        String tn = s.f.forward(order);
        Label label = s.piece();

        UUID session = UUID.fromString((String) packPost(s.token, "", null).get("id"));
        String rawWaybill = "D-07-" + arabic(tn);                      // macOS Arabic digits
        Map<String, Object> opened = packPost(s.token, "/" + session + "/waybill", Map.of("code", rawWaybill));
        assertThat(opened.get("result")).as(String.valueOf(opened)).isEqualTo("opened");
        assertThat(jdbc.queryForObject("SELECT current_waybill_scan FROM pack_sessions WHERE id = ?",
            String.class, session)).isEqualTo(rawWaybill);

        Map<String, Object> scanned = packPost(s.token, "/" + session + "/orders/" + order + "/scan",
            Map.of("code", label.macArabicPc()));
        assertThat(scanned.get("status")).as(String.valueOf(scanned)).isEqualTo("completed");

        // The tracking_linked evidence carries the waybill exactly as it was scanned.
        List<String> rawScans = jdbc.queryForList(
            "SELECT raw_scan FROM piece_events WHERE order_id = ? AND event_type = 'tracking_linked'",
            String.class, order);
        assertThat(rawScans).isNotEmpty().containsOnly(rawWaybill);
        assertThat(jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE order_id = ?",
            String.class, order)).isEqualTo(tn);
    }

    // ── Stock take ────────────────────────────────────────────────────────────

    @Test
    void stockTakeScan_resolvesTypedForms_unknownScanStoresRawBarcode() {
        Shop s = new Shop("StockTake");
        jdbc.update("UPDATE locations SET is_fulfillment = true WHERE id = ?", s.f.location);
        Label a = s.piece(), b = s.piece();
        Map<String, Object> opened = s.as(() -> stockTake.openSession("all", null, s.f.location, null, s.worker));
        UUID session = UUID.fromString(String.valueOf(opened.get("sessionId") != null ? opened.get("sessionId") : opened.get("id")));

        Map<String, Object> r1 = s.as(() -> stockTake.scan(session, a.capsLock(), "good", s.worker));
        assertThat(r1.get("pieceId")).as(String.valueOf(r1)).isEqualTo(s.pieceId(a));
        Map<String, Object> r2 = s.as(() -> stockTake.scan(session, b.macArabic(), "good", s.worker));
        assertThat(r2.get("pieceId")).as(String.valueOf(r2)).isEqualTo(s.pieceId(b));
        assertThat(r2.get("barcode")).isEqualTo(b.macArabic());               // echo stays raw

        Map<String, Object> r3 = s.as(() -> stockTake.scan(session, GARBLED_ULID_MAC, "good", s.worker));
        assertThat(r3.get("classification")).isEqualTo("unknown");
        assertThat(jdbc.queryForList(
            "SELECT raw_barcode FROM stock_take_scans WHERE session_id = ? AND piece_id IS NULL",
            String.class, session)).containsExactly(GARBLED_ULID_MAC);
    }

    // ── Transfers: scan-out, reconcile scan-back ─────────────────────────────

    @Test
    void transferScanOutAndScanBack_resolveTypedForms() {
        Shop s = new Shop("Transfer");
        jdbc.update("UPDATE locations SET is_fulfillment = true WHERE id = ?", s.f.location);
        UUID showroom = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Showroom')", showroom, s.f.tenant);
        Label a = s.piece(), b = s.piece();

        UUID transfer = s.as(() -> transfers.createTransfer("showroom", showroom, null, null, s.worker));
        TransferService.ScanOutResult out1 = s.as(() -> transfers.scanOut(transfer, a.win101(), s.worker));
        assertThat(out1.success()).as(out1.code()).isTrue();
        assertThat(out1.pieceId()).isEqualTo(s.pieceId(a));
        TransferService.ScanOutResult out2 = s.as(() -> transfers.scanOut(transfer, b.pDigitsArabic(), s.worker));
        assertThat(out2.success()).as(out2.code()).isTrue();

        s.as(() -> { transfers.markSent(transfer, s.worker); return null; });
        s.as(() -> { transfers.beginReconcile(transfer, s.worker); return null; });
        TransferService.ScanBackResult back = s.as(() -> transfers.reconcileScanBack(transfer, a.capsLock(), "good", s.worker));
        assertThat(back.success()).as(back.code()).isTrue();
        assertThat(back.pieceId()).isEqualTo(s.pieceId(a));
    }

    @Test
    void transferReturnScanOut_resolvesTypedForm() {
        Shop s = new Shop("TransferReturn");
        UUID main = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", main, s.f.tenant);
        Label a = s.piece("transferred_out");   // relocated earlier to the shop's (non-fulfillment) location
        UUID transfer = s.as(() -> transfers.createTransfer("other", main, null, null, s.worker,
            "relocate_return", s.f.location));
        TransferService.ScanOutResult r = s.as(() -> transfers.returnScanOut(transfer, a.macArabic(), s.worker));
        assertThat(r.success()).as(String.valueOf(r)).isTrue();
        assertThat(r.pieceId()).isEqualTo(s.pieceId(a));
    }

    // ── Returns scan ──────────────────────────────────────────────────────────

    @Test
    void returnsScan_resolvesTypedFormOfAPiece() {
        Shop s = new Shop("Returns");
        Label a = s.piece();
        UUID session = s.as(() -> returns.createSession(null, s.worker));
        Map<String, Object> r = s.as(() -> returns.scan(session, a.macArabicPc(), null, s.worker));
        assertThat(String.valueOf(r)).contains(s.pieceId(a));
    }

    // ── Lookup (HTTP: controller routing + service) ───────────────────────────

    @Test
    void lookup_resolvesTypedForms_garbledIs404() {
        Shop s = new Shop("Lookup");
        Label a = s.piece();
        for (String typed : a.typedForms()) {
            ResponseEntity<Map> r = lookup(s.token, typed);
            assertThat(r.getStatusCode()).as(typed).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody().get("barcode")).as(typed).isEqualTo("PC-" + s.pieceId(a));
        }
        String ulid = s.pieceId(a);
        assertThat(lookup(s.token, "pc-" + ulid.toLowerCase()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookup(s.token, ulid.toLowerCase()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookup(s.token, GARBLED_ULID_MAC).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Pickups: Arabic-digit AWBs (today: 400 Unreadable) ────────────────────

    @Test
    void pickupScan_arabicDigitAwb_andSpacedTopBarcodeWithArabicDigits_accepted() {
        Shop s = new Shop("Pickup");
        String tn1 = packedOrder(s, "#K-1");
        String tn2 = packedOrder(s, "#K-2");
        UUID pickup = pickups.openSession(s.f.tenant, s.worker, LocalDate.now(), "morning", null);

        PickupSessionService.ScanResult r1 = pickups.scan(s.f.tenant, pickup, s.worker, arabic(tn1));
        assertThat(r1.outcome()).isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        assertThat(r1.entry().trackingNumber()).isEqualTo(tn1);

        String spacedTop = "G - " + arabic("0") + " " + arabic("2") + " - " + String.join(" ", arabic(tn2).split(""));
        PickupSessionService.ScanResult r2 = pickups.scan(s.f.tenant, pickup, s.worker, spacedTop);
        assertThat(r2.outcome()).isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        assertThat(r2.entry().trackingNumber()).isEqualTo(tn2);
    }

    // ── Cross-tenant ──────────────────────────────────────────────────────────

    @Test
    void crossTenant_normalizedLookupNeverReachesAnotherTenantsPiece_ownPieceIsFound() {
        Shop mine = new Shop("Mine");
        Shop theirs = new Shop("Theirs");
        Label own = mine.piece();
        Label foreign = theirs.piece();

        UUID order = mine.f.order("#X-1", 0);
        mine.f.item(order, mine.variant, 2);
        for (String typed : foreign.typedForms()) {
            FulfillService.ScanResult r = mine.as(() -> fulfill.scan(order, typed, mine.worker));
            assertThat(r.code()).as(typed).isEqualTo("PIECE_NOT_FOUND");
            assertThat(lookup(mine.token, typed).getStatusCode()).as(typed).isEqualTo(HttpStatus.NOT_FOUND);
        }
        // Positive control: the same normalized forms DO resolve this tenant's own piece.
        FulfillService.ScanResult ok = mine.as(() -> fulfill.scan(order, own.capsLock(), mine.worker));
        assertThat(ok.success()).as(ok.code()).isTrue();
        assertThat(lookup(mine.token, own.win101()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** An order whose one piece is packed and awaiting pickup on a 'created' forward leg; returns its AWB. */
    private String packedOrder(Shop s, String number) {
        UUID order = s.f.order(number, 0);
        s.f.item(order, s.variant, 1);
        String tn = s.f.forward(order);
        Label l = s.piece("awaiting_pickup");
        UUID item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, order);
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) " +
                    "VALUES (?, ?, ?, 'packed'::allocation_status)", s.pieceId(l), item, s.f.tenant);
        return tn;
    }

    private ResponseEntity<Map> lookup(String token, String q) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        java.net.URI uri = UriComponentsBuilder.fromHttpUrl("http://localhost:" + port + "/api/v1/lookup")
            .queryParam("q", q).encode().build().toUri();
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> packPost(String token, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + "/api/v1/pack-sessions" + path,
            HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }
}
