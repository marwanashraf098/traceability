package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaPickupService;
import com.traceability.integrations.bosta.SimulatedWaybillRenderer;
import com.traceability.inventory.TrackingNumberNormalizer;
import com.traceability.inventory.WaybillResolver;
import com.traceability.inventory.WaybillResolver.Code;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Review mode S2 — a simulated-courier tenant (V130) prints, scans and hands over without Bosta.
 *
 *   e1 print a simulated waybill → render page 1 → decode its TOP barcode with ZXing (the real
 *      Bosta shape, AwbSpacedBarcodeTest / TrackingNumberSpacesTest fixture) → normalize →
 *      WaybillResolver opens the right order + shipment; AND the real waybill-scan flow: open a
 *      pack session, POST the decoded raw string to /pack-sessions/{id}/waybill → that order and
 *      shipment load
 *   e2 the QR decodes to BOSTA_<tn> and opens the same order
 *   e3 phone-as-scanner (S6): the phone POSTs the decoded raw string to /scan-pair/scan; the relay
 *      stores it byte-for-byte, and the tablet's POST of that stored code to the same /waybill
 *      endpoint opens the order (the phone has no other server path)
 *   p1 single print: a PDF, Bosta never called (the A4 sample is saved to target/)
 *   p2 batch print: one merged PDF, page order verified, batch recorded
 *   p3 shipment_has_courier: true for a simulated tenant, false for a real one with no account
 *   p4 a real tenant with no account still gets NO_BOSTA_ACCOUNT
 *   k1 schedule pickup: SIM-PU- id, courier_account_id NULL, shipments linked, createPickup never called
 *   k2 the simulated pickup lists, opens and gives its manifest (both manifest endpoints)
 *   k3 pickup session: scan the simulated TOP barcode, close → shipment + piece with_courier
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatedCourierFlowTest {

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
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @Autowired WaybillResolver  resolver;
    @Autowired ObjectMapper     json;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  JobScheduler     jobScheduler;

    private static final AtomicLong TRACKING = new AtomicLong(7_770_000_000_000L + 1_000_000L * (System.nanoTime() % 9_000L));

    /** One simulated tenant with an owner, a worker and a simulated forward shipment on a 'new' order. */
    private final class Sim {
        final PackFixtures f;
        final UUID owner, worker, order, shipment, variant;
        final String ownerToken, workerToken, tracking;

        Sim(String name, boolean simulated) {
            f = new PackFixtures(jdbc, name);
            if (simulated) jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'test')", f.tenant);
            jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", f.tenant);
            owner  = f.user("Owner", "owner");
            worker = f.user("Ahmed", "worker");
            ownerToken  = jwt.issueAccessToken(owner, f.tenant, "owner");
            workerToken = jwt.issueAccessToken(worker, f.tenant, "worker");
            variant = f.variant("Shirt", "S-" + name, null);
            order = f.order("#" + name, 1);
            f.item(order, variant, 1);
            tracking = simulated ? String.valueOf(TRACKING.incrementAndGet()) : PackFixtures.nextTracking();
            shipment = jdbc.queryForObject(
                "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'created', 'forward') RETURNING id", UUID.class, f.tenant, order, tracking);
        }
    }

    @AfterEach
    void resetMocks() { clearInvocations(bostaGateway, jobScheduler); }

    // ── Scan: the generated PDF works end to end ─────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void e1_topBarcode_decoded_resolves_andOpensInTheRealWaybillScanFlow() throws Exception {
        Sim s = new Sim("E1", true);
        Map<String, String> decoded = decodePage1(printSingle(s));
        String raw = decoded.get("top");
        assertThat(raw).as("decoded top barcode is the real Bosta shape").isEqualTo(SimulatedWaybillRenderer.topBarcode(s.tracking));
        assertThat(raw).startsWith("G - 0 2 - ").contains(" ");

        // Resolver
        assertThat(TrackingNumberNormalizer.normalize(raw)).isEqualTo(s.tracking);
        WaybillResolver.Resolution r = TenantContext.runAs(s.f.tenant, () -> resolver.resolve(raw, s.worker));
        assertThat(r.code()).isEqualTo(Code.OPEN);
        assertThat(r.orderId()).isEqualTo(s.order);
        assertThat(r.shipmentId()).isEqualTo(s.shipment);

        // The real waybill-scan flow
        UUID session = UUID.fromString((String) packPost(s.workerToken, "", null).get("id"));
        Map<String, Object> out = packPost(s.workerToken, "/" + session + "/waybill", Map.of("code", raw));
        assertThat(out.get("result")).isEqualTo("opened");
        Map<String, Object> card = (Map<String, Object>) out.get("order");
        assertThat(card.get("id")).isEqualTo(s.order.toString());
        assertThat(card.get("shipment_id")).isEqualTo(s.shipment.toString());
        assertThat(card.get("tracking_number")).isEqualTo(s.tracking);
        verifyNoInteractions(bostaGateway);
    }

    @Test
    void e2_qr_decodes_toBostaPrefix_andResolves() throws Exception {
        Sim s = new Sim("E2", true);
        String qr = decodePage1(printSingle(s)).get("qr");
        assertThat(qr).isEqualTo("BOSTA_" + s.tracking);
        WaybillResolver.Resolution r = TenantContext.runAs(s.f.tenant, () -> resolver.resolve(qr, s.worker));
        assertThat(r.code()).isEqualTo(Code.OPEN);
        assertThat(r.orderId()).isEqualTo(s.order);
    }

    @Test
    @SuppressWarnings("unchecked")
    void e3_phoneAsScanner_relaysTheRawTopBarcode_unchanged_andItOpensTheOrder() throws Exception {
        Sim s = new Sim("E3", true);
        String raw = decodePage1(printSingle(s)).get("top");
        UUID session = UUID.fromString((String) packPost(s.workerToken, "", null).get("id"));

        // Tablet pairs a phone; phone claims and scans.
        Map<String, Object> pairing = packPost(s.workerToken, "/" + session + "/pairings", null);
        String url = (String) pairing.get("pairUrl");
        String pairCode = url.substring(url.lastIndexOf('/') + 1);
        JsonNode claimed = phone(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", pairCode), null);
        String secret = claimed.get("deviceSecret").asText();
        JsonNode scanned = phone(HttpMethod.POST, "/api/v1/scan-pair/scan", Map.of("seq", 1, "code", raw), secret);
        UUID eventId = UUID.fromString(scanned.get("eventId").asText());

        String relayed = jdbc.queryForObject("SELECT code FROM scan_relay_events WHERE id = ?", String.class, eventId);
        assertThat(relayed).as("the relay keeps the raw top barcode byte-for-byte").isEqualTo(raw);

        // The tablet applies a relayed scan through the same endpoint as its own scanner.
        Map<String, Object> out = packPost(s.workerToken, "/" + session + "/waybill", Map.of("code", relayed));
        assertThat(out.get("result")).isEqualTo("opened");
        assertThat(((Map<String, Object>) out.get("order")).get("id")).isEqualTo(s.order.toString());
    }

    // ── Print ─────────────────────────────────────────────────────────────────

    @Test
    void p1_singlePrint_pdf_noBostaCall_sampleSaved() throws Exception {
        Sim s = new Sim("P1", true);
        byte[] pdf = printSingle(s);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "simulated-waybill-sample-A4.pdf"), pdf);
        verifyNoInteractions(bostaGateway);
    }

    @Test
    @SuppressWarnings("unchecked")
    void p2_batchPrint_mergedPdf_orderGuaranteed_batchRecorded() {
        Sim s = new Sim("P2", true);
        UUID order2 = s.f.order("#P2b", 1);
        s.f.item(order2, s.variant, 1);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", s.f.tenant, order2, String.valueOf(TRACKING.incrementAndGet()));

        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/fulfill/print-batches", HttpMethod.POST,
            new HttpEntity<>(Map.of("scope", "new", "paper", "A4", "sort", "oldest"), auth(s.ownerToken)), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = r.getBody();
        assertThat(body.get("pdfBase64")).isNotNull();
        assertThat(body.get("waybillCount")).isEqualTo(2);
        assertThat(body.get("orderGuaranteed")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batch_items WHERE tenant_id = ?",
            Integer.class, s.f.tenant)).isEqualTo(2);
        verifyNoInteractions(bostaGateway);
    }

    @Test
    void p3_shipmentHasCourier_trueForSimulated_falseForRealWithoutAccount() {
        Sim sim  = new Sim("P3s", true);
        Sim real = new Sim("P3r", false);
        assertThat(order(sim).get("shipment_has_courier")).isEqualTo(true);
        assertThat(order(real).get("shipment_has_courier")).isEqualTo(false);
    }

    @Test
    void p4_realTenantWithoutAccount_stillNoBostaAccount() {
        Sim real = new Sim("P4", false);
        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/bosta/awb/print", HttpMethod.POST,
            new HttpEntity<>(Map.of("shipmentIds", List.of(real.shipment)), auth(real.ownerToken)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "NO_BOSTA_ACCOUNT");
    }

    // ── Pickups ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void k1_k2_schedulePickup_simulated_listedOpenedManifested_neverCreatePickup() {
        Sim s = new Sim("K1", true);
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup' WHERE id = ?", s.order);

        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/bosta/pickup/schedule", HttpMethod.POST,
            new HttpEntity<>(Map.of("scheduledDate", nextWorkingDay().toString()), auth(s.ownerToken)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        Map<String, Object> m = r.getBody();
        assertThat(m.get("mode")).isEqualTo("SIMULATED");
        assertThat((String) m.get("providerPickupId")).startsWith(BostaPickupService.SIMULATED_PICKUP_PREFIX);
        assertThat(m.get("parcelCount")).isEqualTo(1);
        UUID pickupId = UUID.fromString((String) m.get("pickupId"));
        assertThat(jdbc.queryForObject("SELECT courier_account_id FROM pickups WHERE id = ?", UUID.class, pickupId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pickup_shipments WHERE pickup_id = ?", Integer.class, pickupId)).isEqualTo(1);
        verifyNoInteractions(bostaGateway);

        // k2 — list, detail, both manifests
        List<Map<String, Object>> list = rest.exchange(base() + "/api/v1/pickup-sessions", HttpMethod.GET,
            new HttpEntity<>(auth(s.ownerToken)), List.class).getBody();
        assertThat(list).anySatisfy(p -> assertThat(String.valueOf(p.get("id"))).isEqualTo(pickupId.toString()));
        assertThat(rest.exchange(base() + "/api/v1/pickup-sessions/" + pickupId, HttpMethod.GET,
            new HttpEntity<>(auth(s.ownerToken)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange(base() + "/api/v1/pickup-sessions/" + pickupId + "/manifest", HttpMethod.GET,
            new HttpEntity<>(auth(s.ownerToken)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> manifest = rest.exchange(base() + "/api/v1/bosta/pickup/manifest/" + pickupId, HttpMethod.GET,
            new HttpEntity<>(auth(s.ownerToken)), Map.class);
        assertThat(manifest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) manifest.getBody().get("providerPickupId")).startsWith(BostaPickupService.SIMULATED_PICKUP_PREFIX);
    }

    @Test
    void k3_pickupSession_scanSimulatedTopBarcode_close_withCourier() throws Exception {
        Sim s = new Sim("K3", true);
        String piece = s.f.piece(s.variant, s.worker);
        String raw = decodePage1(printSingle(s)).get("top");

        // Pack it through the waybill-scan flow with the decoded top barcode.
        UUID session = UUID.fromString((String) packPost(s.workerToken, "", null).get("id"));
        assertThat(packPost(s.workerToken, "/" + session + "/waybill", Map.of("code", raw)).get("result")).isEqualTo("opened");
        assertThat(packPost(s.workerToken, "/" + session + "/orders/" + s.order + "/scan", Map.of("code", piece)).get("status"))
            .isEqualTo("completed");

        // Hand over: open a pickup session, scan the same label, close.
        ResponseEntity<Map> open = rest.exchange(base() + "/api/v1/pickup-sessions", HttpMethod.POST,
            new HttpEntity<>(Map.of("scheduledDate", nextWorkingDay().toString()), auth(s.workerToken)), Map.class);
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.OK);
        String pickup = (String) open.getBody().get("sessionId");
        ResponseEntity<Map> scan = rest.exchange(base() + "/api/v1/pickup-sessions/" + pickup + "/scans", HttpMethod.POST,
            new HttpEntity<>(Map.of("trackingNumber", raw), auth(s.workerToken)), Map.class);
        assertThat(scan.getStatusCode().is2xxSuccessful()).as(String.valueOf(scan.getBody())).isTrue();
        ResponseEntity<Map> close = rest.exchange(base() + "/api/v1/pickup-sessions/" + pickup + "/close", HttpMethod.POST,
            new HttpEntity<>(auth(s.workerToken)), Map.class);
        assertThat(close.getStatusCode().is2xxSuccessful()).as(String.valueOf(close.getBody())).isTrue();

        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, s.shipment))
            .isEqualTo("with_courier");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, s.f.pieceId(piece)))
            .isEqualTo("with_courier");
        verifyNoInteractions(bostaGateway);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private byte[] printSingle(Sim s) {
        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/bosta/awb/print", HttpMethod.POST,
            new HttpEntity<>(Map.of("shipmentIds", List.of(s.shipment), "format", "A4"), auth(s.ownerToken)), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked") List<String> pdfs = (List<String>) r.getBody().get("pdfBase64List");
        assertThat(pdfs).hasSize(1);
        return Base64.getDecoder().decode(pdfs.get(0));
    }

    /** Renders page 1 at 300 dpi and decodes every barcode on it: "top" (Code 128, G - …), "bottom", "qr". */
    private static Map<String, String> decodePage1(byte[] pdf) throws Exception {
        BufferedImage img;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            img = new PDFRenderer(doc).renderImageWithDPI(0, 300);
        }
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128, BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        Result[] results = new GenericMultipleBarcodeReader(new MultiFormatReader())
            .decodeMultiple(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))), hints);
        Map<String, String> out = new HashMap<>();
        for (Result res : results) {
            if (res.getBarcodeFormat() == BarcodeFormat.QR_CODE) out.put("qr", res.getText());
            else if (res.getText().startsWith("G - ")) out.put("top", res.getText());
            else out.put("bottom", res.getText());
        }
        assertThat(out).as("decoded: " + out).containsKeys("top", "qr");
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> order(Sim s) {
        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/fulfill/" + s.order, HttpMethod.GET,
            new HttpEntity<>(auth(s.ownerToken)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> packPost(String token, String path, Object body) {
        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/pack-sessions" + path, HttpMethod.POST,
            new HttpEntity<>(body, auth(token)), Map.class);
        assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private JsonNode phone(HttpMethod m, String path, Object body, String secret) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)");
        if (secret != null) h.set("X-Device-Secret", secret);
        ResponseEntity<String> r = rest.exchange(base() + path, m, new HttpEntity<>(body, h), String.class);
        assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.OK);
        return json.readTree(r.getBody());
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static LocalDate nextWorkingDay() {
        LocalDate d = LocalDate.now(ZoneId.of("Africa/Cairo")).plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.FRIDAY) d = d.plusDays(1);
        return d;
    }

    private String base() { return "http://localhost:" + port; }
}
