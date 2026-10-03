package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaFulfillmentLinkService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySyncService;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Review mode S3 — auto-shipment on order ingest (SimulatedShipments, V132), simulated tenants only.
 *
 *   a1  webhook ingest → exactly one 'created' forward shipment, 777… tracking, the order's COD
 *   a2  the same webhook again + an orders/updated → still one shipment, same number, sequence untouched
 *   a3  import path (ingestMissingOrder → upsertOrder) → shipment created
 *   a4  REAL tenant: webhook and import → 0 shipments
 *   a5  pre-cutoff order (FR-18) → no order, no shipment; born-cancelled order → no shipment
 *   a6  cancelled later → shipment unchanged ('created'), order out of the queue
 *   a7  unmapped variant → shipment created, order held (not in queue); released → in queue
 *   a8  two concurrent ingests of the same order → exactly one shipment
 *   a9  end to end: in GET /fulfill/queue (the endpoint both Pick &amp; Pack modes read) in queue mode
 *       AND waybill-scan mode, awaiting-waybill count 0, a batch-print candidate; single print →
 *       decoded top barcode opens it in a waybill-scan session → packs; no provider-id fetch; Bosta
 *       never called
 *   a10 DB: a 777… tracking number on a real tenant → check_violation; on a simulated tenant → ok
 *   a11 fulfillment-link on a simulated order with a captured Bosta tracking number → skipped, no Bosta call
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulatedAutoShipmentTest {

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
    @Autowired TestRestTemplate  rest;
    @Autowired JdbcTemplate      jdbc;
    @Autowired JwtService        jwt;
    @Autowired ObjectMapper      json;
    @Autowired ShopifySyncService sync;
    @Autowired BostaFulfillmentLinkService fulfillmentLink;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    private static final AtomicLong IDS = new AtomicLong(910_000_000L);

    /** A tenant (simulated or real) with a store, a mapped variant, an owner and a worker. */
    private final class T {
        final PackFixtures f;
        final UUID owner, worker, variant;
        final long variantRestId;
        final String ownerToken, workerToken;

        T(String name, boolean simulated) {
            f = new PackFixtures(jdbc, name);
            if (simulated) jdbc.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'test')", f.tenant);
            owner  = f.user("Owner", "owner");
            worker = f.user("Ahmed", "worker");
            ownerToken  = jwt.issueAccessToken(owner, f.tenant, "owner");
            workerToken = jwt.issueAccessToken(worker, f.tenant, "worker");
            variantRestId = IDS.incrementAndGet();
            UUID product = jdbc.queryForObject(
                "INSERT INTO products (tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'Shirt', 'active') RETURNING id",
                UUID.class, f.tenant, f.store, "gid://shopify/Product/" + IDS.incrementAndGet());
            variant = jdbc.queryForObject(
                "INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, 'M') RETURNING id",
                UUID.class, f.tenant, product, "gid://shopify/ProductVariant/" + variantRestId, "SKU-" + name);
        }

        /** An orders/create REST payload: one line of the mapped variant (or an unknown one), COD 350. */
        ObjectNode payload(long orderRestId, String name, boolean mapped, Instant createdAt, String cancelledAt) {
            ObjectNode p = json.createObjectNode();
            p.put("admin_graphql_api_id", "gid://shopify/Order/" + orderRestId);
            p.put("id", orderRestId);
            p.put("name", name);
            p.put("created_at", createdAt.toString());
            p.put("financial_status", "pending");
            p.putArray("payment_gateway_names").add("Cash on Delivery (COD)");
            p.put("current_total_price", "350.00");
            if (cancelledAt != null) p.put("cancelled_at", cancelledAt);
            ArrayNode lines = p.putArray("line_items");
            ObjectNode line = lines.addObject();
            line.put("id", orderRestId * 10);
            line.put("admin_graphql_api_id", "gid://shopify/LineItem/" + orderRestId * 10);
            line.put("variant_id", mapped ? variantRestId : 999_999_999L);
            line.put("quantity", 1);
            return p;
        }

        UUID ingest(ObjectNode payload) {
            TenantContext.runAs(f.tenant, () -> { sync.ingestOrderWebhook(f.store, f.tenant, payload); return null; });
            return orderId(payload.get("admin_graphql_api_id").asText());
        }

        UUID orderId(String gid) {
            List<UUID> ids = jdbc.queryForList("SELECT id FROM orders WHERE tenant_id = ? AND external_id = ?", UUID.class, f.tenant, gid);
            return ids.isEmpty() ? null : ids.get(0);
        }

        List<Map<String, Object>> forwardLegs(UUID orderId) {
            return jdbc.queryForList(
                "SELECT tracking_number, internal_state::text AS state, cod_amount FROM shipments " +
                "WHERE tenant_id = ? AND order_id = ? AND shipment_leg = 'forward'", f.tenant, orderId);
        }
    }

    @AfterEach
    void resetMocks() { clearInvocations(bostaGateway, jobScheduler); }

    // ── a1–a3: created once, on both ingest paths ───────────────────────────

    @Test
    void a1_webhookIngest_oneCreatedForwardShipment_reservedTracking_orderCod() {
        T t = new T("A1", true);
        UUID order = t.ingest(t.payload(IDS.incrementAndGet(), "#A1", true, Instant.now(), null));
        List<Map<String, Object>> legs = t.forwardLegs(order);
        assertThat(legs).hasSize(1);
        assertThat((String) legs.get(0).get("tracking_number")).matches("^777\\d{10}$");
        assertThat(legs.get(0).get("state")).isEqualTo("created");
        assertThat((BigDecimal) legs.get(0).get("cod_amount")).isEqualByComparingTo("350.00");
        verifyNoInteractions(bostaGateway);
    }

    @Test
    void a2_replayAndEdit_stillOneShipment_sameNumber_sequenceUntouched() {
        T t = new T("A2", true);
        long rest = IDS.incrementAndGet();
        UUID order = t.ingest(t.payload(rest, "#A2", true, Instant.now(), null));
        String tn = (String) t.forwardLegs(order).get(0).get("tracking_number");
        long seqBefore = seqValue();

        t.ingest(t.payload(rest, "#A2", true, Instant.now(), null));            // replay
        ObjectNode edited = t.payload(rest, "#A2", true, Instant.now(), null);  // orders/updated: qty change
        ((ObjectNode) edited.get("line_items").get(0)).put("quantity", 2);
        t.ingest(edited);

        List<Map<String, Object>> legs = t.forwardLegs(order);
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).get("tracking_number")).isEqualTo(tn);
        assertThat(seqValue()).as("no tracking number consumed by a replay / edit").isEqualTo(seqBefore);
    }

    @Test
    void a3_importPath_ingestMissingOrder_createsTheShipment() {
        T t = new T("A3", true);
        String gid = "gid://shopify/Order/" + IDS.incrementAndGet();
        ShopifyGateway.Order o = new ShopifyGateway.Order(gid, "#A3", "Mona Ali", null, null, "PENDING",
            List.of("Cash on Delivery (COD)"), new BigDecimal("420.00"),
            List.of(new ShopifyGateway.LineItem("gid://shopify/LineItem/" + IDS.incrementAndGet(), 1,
                "gid://shopify/ProductVariant/" + t.variantRestId)),
            Instant.now(), json.createObjectNode());
        TenantContext.runAs(t.f.tenant, () -> { sync.ingestMissingOrder(t.f.store, t.f.tenant, o); return null; });
        List<Map<String, Object>> legs = t.forwardLegs(t.orderId(gid));
        assertThat(legs).hasSize(1);
        assertThat((String) legs.get(0).get("tracking_number")).matches("^777\\d{10}$");
    }

    // ── a4: real tenants never ──────────────────────────────────────────────

    @Test
    void a4_realTenant_webhookAndImport_noShipment() {
        T t = new T("A4", false);
        UUID viaWebhook = t.ingest(t.payload(IDS.incrementAndGet(), "#A4w", true, Instant.now(), null));
        String gid = "gid://shopify/Order/" + IDS.incrementAndGet();
        ShopifyGateway.Order o = new ShopifyGateway.Order(gid, "#A4i", null, null, null, "PENDING",
            List.of("Cash on Delivery (COD)"), new BigDecimal("100.00"),
            List.of(new ShopifyGateway.LineItem("gid://shopify/LineItem/" + IDS.incrementAndGet(), 1,
                "gid://shopify/ProductVariant/" + t.variantRestId)),
            Instant.now(), json.createObjectNode());
        TenantContext.runAs(t.f.tenant, () -> { sync.ingestMissingOrder(t.f.store, t.f.tenant, o); return null; });

        assertThat(viaWebhook).isNotNull();
        assertThat(t.orderId(gid)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE tenant_id = ?", Integer.class, t.f.tenant))
            .as("a real tenant never gets an auto-shipment").isZero();
    }

    // ── a5–a7: cutoff, cancellation, hold ───────────────────────────────────

    @Test
    void a5_preCutoff_noOrder_bornCancelled_noShipment() {
        T t = new T("A5", true);
        jdbc.update("UPDATE stores SET orders_ingest_from = now() WHERE id = ?", t.f.store);
        UUID old = t.ingest(t.payload(IDS.incrementAndGet(), "#A5old", true, Instant.now().minus(3, ChronoUnit.DAYS), null));
        assertThat(old).as("FR-18: pre-connection order not ingested").isNull();

        UUID cancelled = t.ingest(t.payload(IDS.incrementAndGet(), "#A5c", true, Instant.now(), Instant.now().toString()));
        assertThat(cancelled).isNotNull();
        assertThat(t.forwardLegs(cancelled)).as("born-cancelled order gets no shipment").isEmpty();
    }

    @Test
    void a6_cancelledLater_shipmentUnchanged_orderLeavesQueue() {
        T t = new T("A6", true);
        UUID order = t.ingest(t.payload(IDS.incrementAndGet(), "#A6", true, Instant.now(), null));
        String tn = (String) t.forwardLegs(order).get(0).get("tracking_number");
        assertThat(queueIds(t)).contains(order.toString());

        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/fulfill/" + order + "/cancel", HttpMethod.POST,
            new HttpEntity<>(Map.of(), auth(t.ownerToken)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();

        List<Map<String, Object>> legs = t.forwardLegs(order);
        assertThat(legs).hasSize(1);
        assertThat(legs.get(0).get("tracking_number")).isEqualTo(tn);
        assertThat(legs.get(0).get("state")).isEqualTo("created");
        assertThat(queueIds(t)).doesNotContain(order.toString());
    }

    @Test
    void a7_unmappedVariant_shipmentCreated_heldOutOfQueue_releasedIn() {
        T t = new T("A7", true);
        UUID order = t.ingest(t.payload(IDS.incrementAndGet(), "#A7", false, Instant.now(), null));
        assertThat(t.forwardLegs(order)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT on_hold FROM orders WHERE id = ?", Boolean.class, order)).isTrue();
        assertThat(queueIds(t)).doesNotContain(order.toString());

        jdbc.update("UPDATE orders SET on_hold = false, hold_reason = NULL WHERE id = ?", order);
        assertThat(queueIds(t)).contains(order.toString());
    }

    // ── a8: race ────────────────────────────────────────────────────────────

    @Test
    void a8_concurrentIngests_exactlyOneShipment() throws Exception {
        T t = new T("A8", true);
        long rest = IDS.incrementAndGet();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                t.ingest(t.payload(rest, "#A8", true, Instant.now(), null));
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        UUID order = t.orderId("gid://shopify/Order/" + rest);
        assertThat(t.forwardLegs(order)).hasSize(1);
    }

    // ── a9: end to end ──────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void a9_inBothPickPackModes_printsSimulatedWaybill_packsViaWaybillScan() throws Exception {
        T t = new T("A9", true);
        UUID order = t.ingest(t.payload(IDS.incrementAndGet(), "#A9", true, Instant.now(), null));
        UUID shipment = jdbc.queryForObject("SELECT id FROM shipments WHERE order_id = ?", UUID.class, order);

        // The queue both Pick & Pack modes read (Fulfill.tsx queue view, WaybillPackPage tiles).
        for (String mode : List.of("order_queue", "waybill_scan")) {   // V126 CHECK values
            jdbc.update("UPDATE tenants SET pick_pack_mode = ? WHERE id = ?", mode, t.f.tenant);
            assertThat(queueIds(t)).as(mode).contains(order.toString());
            ResponseEntity<Map> awaiting = rest.exchange(base() + "/api/v1/fulfill/queue/awaiting-waybill-count",
                HttpMethod.GET, new HttpEntity<>(auth(t.workerToken)), Map.class);
            assertThat(awaiting.getBody()).as(mode).containsEntry("count", 0);
        }

        // Single print → simulated PDF → decoded top barcode opens it in a waybill-scan session.
        ResponseEntity<Map> print = rest.exchange(base() + "/api/v1/bosta/awb/print", HttpMethod.POST,
            new HttpEntity<>(Map.of("shipmentIds", List.of(shipment)), auth(t.ownerToken)), Map.class);
        assertThat(print.getStatusCode()).as(String.valueOf(print.getBody())).isEqualTo(HttpStatus.OK);
        byte[] pdf = Base64.getDecoder().decode(((List<String>) print.getBody().get("pdfBase64List")).get(0));
        String raw = decodeTop(pdf);

        String piece = t.f.piece(t.variant, t.worker);
        UUID session = UUID.fromString((String) pack(t.workerToken, "", null).get("id"));
        Map<String, Object> opened = pack(t.workerToken, "/" + session + "/waybill", Map.of("code", raw));
        assertThat(opened.get("result")).isEqualTo("opened");
        assertThat(((Map<String, Object>) opened.get("order")).get("id")).isEqualTo(order.toString());
        assertThat(pack(t.workerToken, "/" + session + "/orders/" + order + "/scan", Map.of("code", piece)).get("status"))
            .isEqualTo("completed");

        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, order)).isEqualTo("awaiting_pickup");
        assertThat(jdbc.queryForObject("SELECT provider_id_fetch_failed FROM shipments WHERE id = ?", Boolean.class, shipment))
            .as("packing the existing shipment never tries a Bosta provider-id fetch").isNotEqualTo(Boolean.TRUE);
        verifyNoInteractions(bostaGateway);
    }

    @Test
    void a9b_batchPrintCandidate() {
        T t = new T("A9b", true);
        UUID order = t.ingest(t.payload(IDS.incrementAndGet(), "#A9b", true, Instant.now(), null));
        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/fulfill/print-batches", HttpMethod.POST,
            new HttpEntity<>(Map.of("scope", "new", "paper", "A4", "sort", "oldest"), auth(t.ownerToken)), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("waybillCount")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pack_print_batch_items WHERE order_id = ?", Integer.class, order)).isEqualTo(1);
        verifyNoInteractions(bostaGateway);
    }

    // ── a10: the reserved range stays off real tenants ──────────────────────

    @Test
    void a10_reservedTracking_refusedOnRealTenant_allowedOnSimulated() {
        T real = new T("A10r", false);
        T sim  = new T("A10s", true);
        UUID realOrder = real.f.order("#A10r", 1);
        UUID simOrder  = sim.f.order("#A10s", 1);

        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', '7779999999990', 'created', 'forward')", real.f.tenant, realOrder))
            .isInstanceOf(DataAccessException.class)
            .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));

        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', '7779999999991', 'created', 'forward')", sim.f.tenant, simOrder);
        assertThat(sim.forwardLegs(simOrder)).hasSize(1);
    }

    // ── a11: fulfillment-link never reaches Bosta for a simulated order ─────

    @Test
    void a11_fulfillmentLink_skipped_noBostaCall() {
        T t = new T("A11", true);
        UUID order = t.ingest(t.payload(IDS.incrementAndGet(), "#A11", true, Instant.now(), null));
        jdbc.update("INSERT INTO order_fulfillment_tracking (tenant_id, order_id, tracking_number, carrier_raw, carrier_class) " +
                    "VALUES (?, ?, '8484805699', 'Bosta', 'bosta')", t.f.tenant, order);

        BostaFulfillmentLinkService.Result r = fulfillmentLink.attempt(t.f.tenant, order, "8484805699", false);
        assertThat(r.verdict().name()).isEqualTo("SKIP");
        assertThat(r.reason()).contains("active forward leg");
        verifyNoInteractions(bostaGateway);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private long seqValue() {
        return jdbc.queryForObject("SELECT last_value FROM simulated_tracking_seq", Long.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> queueIds(T t) {
        ResponseEntity<List> r = rest.exchange(base() + "/api/v1/fulfill/queue", HttpMethod.GET,
            new HttpEntity<>(auth(t.workerToken)), List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) r.getBody()).stream().map(m -> String.valueOf(m.get("id"))).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> pack(String token, String path, Object body) {
        ResponseEntity<Map> r = rest.exchange(base() + "/api/v1/pack-sessions" + path, HttpMethod.POST,
            new HttpEntity<>(body, auth(token)), Map.class);
        assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static String decodeTop(byte[] pdf) throws Exception {
        BufferedImage img;
        try (PDDocument doc = Loader.loadPDF(pdf)) { img = new PDFRenderer(doc).renderImageWithDPI(0, 300); }
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.CODE_128));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        for (Result res : new GenericMultipleBarcodeReader(new MultiFormatReader())
                .decodeMultiple(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))), hints)) {
            if (res.getText().startsWith("G - ")) return res.getText();
        }
        throw new AssertionError("top barcode not found");
    }

    private static String sqlState(Throwable e) {
        for (Throwable x = e; x != null; x = x.getCause()) {
            if (x instanceof SQLException s && s.getSQLState() != null) return s.getSQLState();
        }
        return null;
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String base() { return "http://localhost:" + port; }
}
