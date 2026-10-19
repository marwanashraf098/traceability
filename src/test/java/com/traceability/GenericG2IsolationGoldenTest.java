package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.*;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-Bosta couriers G2 — Bosta isolation, golden. One realistic Bosta tenant (bare-numeric AWBs, hub
 * prefixes, the spaced top barcode, the BOSTA_ QR, Arabic-Indic digits) runs every scan surface G2 touches:
 * waybill-mode resolve, a whole pack session (open → piece → waybill-while-packing → complete + link),
 * the queue's AWB link step (verify, mismatch, unreadable, a new AWB on a packed order with no leg),
 * the pick screen's order detail, a pickup scan, a returns scan, Lookup and the open exceptions.
 *
 * The golden file was recorded on origin/main cd6441b — BEFORE any G2 code — with
 * {@code -Dgolden.write=true}; this test then proves the same output after G2:
 *   g1  flag OFF → byte-identical to the golden;
 *   g2  flag ON, still no generic shipment (the Femine case before any Wijha order) → identical except the
 *       two "no order uses this waybill" texts, which G2 makes courier-neutral for switched-on tenants.
 * Volatile values (ids, piece codes, timestamps) are replaced by stable tokens in order of appearance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericG2IsolationGoldenTest {

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

    static final Path GOLDEN = Path.of("src/test/resources/golden/g2-bosta-isolation.json");

    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired WaybillResolver       resolver;
    @Autowired PackSessionStore      packStore;
    @Autowired PackSessionService    packService;
    @Autowired FulfillService        fulfill;
    @Autowired ShipmentLinkService   link;
    @Autowired PickupSessionService  pickups;
    @Autowired ReturnSessionService  returns;
    @Autowired LookupService         lookup;
    @Autowired ExceptionService      exceptions;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  JobScheduler          jobScheduler;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void g1_flagOff_bostaTenant_identicalToPreG2Golden() throws Exception {
        String out = scenario("Golden-off", 0L, false);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/g2-bosta-isolation.actual.json"), out);
        if (Boolean.getBoolean("golden.write")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, out);
        }
        assertThat(out).isEqualTo(Files.readString(GOLDEN));
    }

    @Test
    void g2_flagOn_bostaTenant_noGenericLeg_identicalExceptNeutralNotFoundTexts() throws Exception {
        String out = scenario("Golden-on", 1_000_000L, true);
        Map<String, Object> on = mapper.readValue(out, Map.class);
        Map<String, Object> golden = mapper.readValue(Files.readString(GOLDEN), Map.class);

        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> rOn = (Map<String, Map<String, Object>>) on.get("resolve");
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> rGold = (Map<String, Map<String, Object>>) golden.get("resolve");
        for (String scan : rGold.keySet()) {
            Map<String, Object> a = new TreeMap<>(rOn.get(scan)), b = new TreeMap<>(rGold.get(scan));
            if ("NOT_FOUND".equals(b.get("code")) && b.get("detail") == null || "UNRECOGNISED_BARCODE".equals(b.get("code"))) {
                assertThat((String) a.get("messageEn")).as(scan).isNotEqualTo(b.get("messageEn")).doesNotContain("Bosta");
                assertThat(a.get("messageAr")).as(scan).isNotEqualTo(b.get("messageAr"));
                a.remove("messageEn"); a.remove("messageAr"); b.remove("messageEn"); b.remove("messageAr");
            }
            assertThat(a).as(scan).isEqualTo(b);
        }
        on.remove("resolve");
        golden.remove("resolve");
        assertThat(on).isEqualTo(golden);
    }

    // ── The scenario ─────────────────────────────────────────────────────────────────────────────

    /** Runs everything on a fresh Bosta tenant; tracking numbers are base + a fixed offset per role. */
    private String scenario(String name, long offset, boolean genericOn) throws Exception {
        PackFixtures f = new PackFixtures(jdbc, name);
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", f.tenant);
        if (genericOn) jdbc.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", f.tenant);
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', 'x', ?, 'active')", f.tenant, "wh-" + f.tenant);
        UUID packer = f.user("Mona", "worker");
        UUID variant = f.variant("Shirt", "SH-" + name, null);
        long b = 8_484_805_000L + offset;

        UUID open1 = f.order("#2001", 1);                    String tOpen   = leg(f, open1, "forward", "created", b + 1);
        f.item(open1, variant, 2);                           // one line of 2: the session can scan a waybill mid-order
        UUID ret   = order(f, "#2002", variant, 1);          leg(f, ret, "forward", "delivered", b + 2);
        String tReturn = leg(f, ret, "return", "with_courier", b + 3);
        UUID canc  = order(f, "#2003", variant, 1);          String tCanc   = leg(f, canc, "forward", "created", b + 4);
        jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ?", canc);
        UUID ended = order(f, "#2004", variant, 1);          String tEnded  = leg(f, ended, "forward", "terminated", b + 5);
        UUID moved = order(f, "#2005", variant, 1);          String tMoved  = leg(f, moved, "forward", "with_courier", b + 6);
        UUID held  = order(f, "#2006", variant, 1);          String tHeld   = leg(f, held, "forward", "created", b + 7);
        jdbc.update("UPDATE orders SET on_hold = true WHERE id = ?", held);
        UUID old   = order(f, "#2007", variant, 60);         String tOld    = leg(f, old, "forward", "created", b + 8);
        String tUnlinked = String.valueOf(b + 9);
        jdbc.update("INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, bosta_state_code, bosta_order_type, raw) " +
            "VALUES (?, ?, 24, 'SEND', '{}'::jsonb)", f.tenant, tUnlinked);
        String piece1 = f.piece(variant, packer);
        String piece2 = f.piece(variant, packer);
        String piece3 = f.piece(variant, packer);

        Map<String, Object> out = new LinkedHashMap<>();
        TenantContext.set(f.tenant);

        // 1. waybill-mode resolve
        Map<String, Object> resolve = new LinkedHashMap<>();
        String arabic = toArabicDigits(tOpen);
        for (String[] scan : new String[][]{
                {"plain", tOpen}, {"hubPrefix", "D-07-" + tOpen}, {"spacedTop", spaced("G-02-" + tOpen)},
                {"qr", "BOSTA_" + tOpen}, {"arabicDigits", arabic}, {"returnLeg", tReturn}, {"cancelled", tCanc},
                {"legEnded", tEnded}, {"moved", tMoved}, {"onHold", tHeld}, {"tooOld", tOld},
                {"unlinked", tUnlinked}, {"unknownDigits", String.valueOf(b + 99)}, {"pieceCode", piece1},
                {"jtLetters", "JT1234567890EG"}, {"wijha", "WJ-12345"}, {"url", "https://track.example.com/t?awb=MY998877"}}) {
            resolve.put(scan[0], resolver.resolve(scan[1], packer));
        }
        out.put("resolve", resolve);

        // 2. a whole pack session on #2001
        UUID session = packStore.start(packer);
        out.put("packOpen", packStore.openWaybill(session, "D-07-" + tOpen, packer));
        out.put("packScan1", packService.scan(session, open1, piece1, packer));
        out.put("packWaybillWhilePacking", attempt(() -> packService.scan(session, open1, tHeld, packer)));
        out.put("packWijhaWhilePacking", attempt(() -> packService.scan(session, open1, "WJ-12345", packer)));
        out.put("packScan2", attempt(() -> packService.scan(session, open1, piece3, packer)));
        out.put("packView", packStore.view(session, packer));
        out.put("open1Status", jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, open1));
        packStore.end(session, packer);

        // 3. queue: the pick screen's order detail, then the AWB link step
        UUID q1 = order(f, "#2010", variant, 1);             String tQ1 = leg(f, q1, "forward", "created", b + 10);
        out.put("getOrderBefore", fulfill.getOrder(q1));
        out.put("queueScan", fulfill.scan(q1, piece2, packer));
        out.put("queueComplete", fulfill.complete(q1, packer));
        out.put("linkUnreadable", attempt(() -> link.linkByAwbScan(q1, "JT1234567890EG", packer)));
        out.put("linkMismatch", attempt(() -> link.linkByAwbScan(q1, String.valueOf(b + 98), packer)));
        out.put("linkWijha", attempt(() -> link.linkByAwbScan(q1, "WJ-12345", packer)));
        out.put("linkVerify", attempt(() -> link.linkByAwbScan(q1, "D-07-" + tQ1, packer)));
        out.put("getOrderAfter", fulfill.getOrder(q1));

        UUID q2 = order(f, "#2011", variant, 1);
        jdbc.update("UPDATE orders SET status = 'packed' WHERE id = ?", q2);
        out.put("linkNewAwb", attempt(() -> link.linkByAwbScan(q2, String.valueOf(b + 11), packer)));
        out.put("q2Shipments", jdbc.queryForList("SELECT provider::text, tracking_number, internal_state::text, " +
            "shipment_leg, source, tracking_key, carrier_name FROM shipments WHERE order_id = ?", q2));

        // 4. pickup, returns, lookup
        UUID pickup = pickups.openSession(f.tenant, packer, LocalDate.of(2026, 10, 20), "10:00-13:00", null);
        TenantContext.set(f.tenant);
        out.put("pickupScan", attempt(() -> pickups.scan(f.tenant, pickup, packer, "D-07-" + tQ1)));
        out.put("pickupUnknown", attempt(() -> pickups.scan(f.tenant, pickup, packer, String.valueOf(b + 97))));
        out.put("pickupWijha", attempt(() -> pickups.scan(f.tenant, pickup, packer, "WJ-12345")));
        TenantContext.set(f.tenant);
        UUID rs = returns.createSession(null, packer);
        out.put("returnScanLeg", attempt(() -> returns.scan(rs, tReturn, f.location, packer)));
        out.put("returnScanUnknown", attempt(() -> returns.scan(rs, String.valueOf(b + 96), f.location, packer)));
        out.put("returnScanWijha", attempt(() -> returns.scan(rs, "WJ-12345", f.location, packer)));
        out.put("lookupTracking", attempt(() -> lookup.lookupTracking("D-07-" + tQ1)));
        out.put("lookupWijha", attempt(() -> lookup.lookupTracking("WJ-12345")));
        out.put("lookupOrder", attempt(() -> lookup.lookupOrder("#2010")));
        out.put("exceptions", exceptions.detectAllOpen().stream()
            .map(e -> e.get("type") + "|" + e.get("severity") + "|" + e.get("subject_type")).sorted().toList());

        String json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out);
        return stabilise(json, name);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private UUID order(PackFixtures f, String number, UUID variant, int daysAgo) {
        UUID id = f.order(number, daysAgo);
        f.item(id, variant, 1);
        return id;
    }

    private String leg(PackFixtures f, UUID order, String leg, String state, long tn) {
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?)", f.tenant, order, String.valueOf(tn), state, leg);
        if (!"created".equals(state)) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
                "SELECT tenant_id, id, internal_state FROM shipments WHERE tracking_number = ?", String.valueOf(tn));
        }
        return String.valueOf(tn);
    }

    /** The call's result, or the refusal it ended in (class + status + reason) — both are behaviour. */
    private Object attempt(Supplier<Object> call) {
        try {
            return call.get();
        } catch (ResponseStatusException e) {
            return Map.of("refused", e.getStatusCode().value(), "reason", String.valueOf(e.getReason()));
        } catch (RuntimeException e) {
            return Map.of("refused", e.getClass().getSimpleName(), "reason", String.valueOf(e.getMessage()));
        }
    }

    private static String spaced(String s) {
        return String.join(" ", s.split(""));
    }

    private static String toArabicDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(Character.isDigit(c) ? (char) ('٠' + (c - '0')) : c);
        return b.toString();
    }

    /** Ids, piece codes, timestamps and the tenant's offset tracking numbers → stable tokens. */
    private static String stabilise(String json, String name) {
        Map<String, String> seen = new LinkedHashMap<>();
        String[] patterns = {
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",      // uuid
            "PC-[0-9A-HJKMNP-TV-Z]{26}", "\\b[0-9A-HJKMNP-TV-Z]{26}\\b",          // piece barcode / id
            "\\bP[0-9]{6}\\b",                                                     // piece short code
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}(:\\d{2})?)?", // iso / pg time
            "\\b1[6-9]\\d{8}\\.\\d+\\b", "\\b1[6-9]\\d{11}\\b"                    // epoch s.frac / millis
        };
        String s = json.replace(name, "<tenant>");
        for (String p : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(s);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                String token = seen.computeIfAbsent(m.group(), k -> "<v" + seen.size() + ">");
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(token));
            }
            m.appendTail(b);
            s = b.toString();
        }
        // Tracking numbers: the second run uses base + 1 000 000 — fold both onto offsets from the base.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("84[0-9]{8}").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            long v = Long.parseLong(m.group());
            m.appendReplacement(b, "TN+" + ((v - 8_484_805_000L) % 1_000_000L));
        }
        m.appendTail(b);
        return b.toString();
    }
}
