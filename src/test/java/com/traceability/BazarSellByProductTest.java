package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B7a / B7b — selling without barcodes: tap a product + size (the server picks the oldest open piece,
 * SKIP LOCKED, through the scan sale's own path), tapped cart lines, the event's #numbers (per product + colour,
 * stable, next free number mid-event), a product number never sells as a code, no Shopify write, who may use it,
 * RLS on the numbers, and the A4 price sheet (EN / AR, Arabic shaped).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSellByProductTest {

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
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired PasswordEncoder  passwordEncoder;
    @Autowired DataSource       dataSource;

    UUID tenant, otherTenant, main, storeId;
    UUID shirtWhiteM, shirtWhiteL, shirtBlueM, teeS, teeM, scarfGrey, scarfBlack, apron;
    String owner, worker, ownerOther;
    UUID workerId;
    int seq = 0, pieceSeq = 0;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        main = UUID.randomUUID();
        storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'B7 On', true)", tenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'B7 Other', true)", otherTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'b7.myshopify.com', 'disconnected')", storeId, tenant);
        UUID shirt = product("Linen shirt", "Shirts");
        shirtWhiteM = variant(shirt, "White / M", "960.00");
        shirtWhiteL = variant(shirt, "White / L", "960.00");
        shirtBlueM  = variant(shirt, "Blue / M", "1000.00");
        UUID tee = product("Cotton tee", "Tops");
        teeS = variant(tee, "S", "360.00");
        teeM = variant(tee, "M", "360.00");
        UUID scarf = product("Knit scarf", null);
        scarfGrey  = variant(scarf, "Grey", "320.00");
        scarfBlack = variant(scarf, "Black", "320.00");
        apron = variant(product("Apron", null), "Default Title", "200.00");
        owner = login(user(UUID.randomUUID(), tenant, "owner"));
        workerId = UUID.randomUUID();
        worker = login(user(workerId, tenant, "worker"));
        ownerOther = login(user(UUID.randomUUID(), otherTenant, "owner"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private UUID product(String title, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'active', ?::jsonb)", id, tenant, storeId, "P-" + id, title,
            type == null ? "{}" : "{\"product_type\":\"" + type + "\"}");
        return id;
    }

    private UUID variant(UUID product, String title, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) VALUES (?, ?, ?, ?, ?, ?::numeric)",
            id, tenant, product, "V-" + id, title, price);
        return id;
    }

    private String user(UUID id, UUID t, String role) {
        String email = role + "-" + UUID.randomUUID() + "@b7.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, t, "B7 " + role, email, passwordEncoder.encode("pass123"), role);
        return email;
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> r = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().accessToken();
    }

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), Map.class);
    }

    private String piece(UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available', ?)", id, tenant, variant, "PC-" + id,
            String.format("P%07d", 7700000 + ++pieceSeq), main);
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    /** A live event; {@code variants} scanned out in this order (one piece each): returns the event id. */
    @SuppressWarnings("rawtypes")
    private String liveEvent(List<String> roles, UUID... variants) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "B7 Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "B7 Hall " + seq, "sellerRoles", roles,
            "paymentMethods", List.of("cash", "card")));
        ResponseEntity<Map> created = call(owner, HttpMethod.POST, "/api/v1/bazar-events", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ev = (String) created.getBody().get("id");
        for (UUID v : variants) {
            String code = piece(v);
            assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/scan-out", Map.of("barcode", code))
                .getBody().get("success")).isEqualTo(true);
        }
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/mark-sent", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/start", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        return ev;
    }

    private String liveEvent(UUID... variants) {
        return liveEvent(List.of("owner", "worker"), variants);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> tap(String token, String ev, UUID variant, String crid) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sell",
            Map.of("variant_id", variant.toString(), "client_request_id", crid, "method", "cash"));
    }

    /** Short codes of the event's pieces of a variant, in scan-out order. */
    private List<String> scanned(String ev, UUID variant) {
        return jdbc.queryForList(
            "SELECT p.short_code FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id " +
            "JOIN bazar_events e ON e.transfer_id = tp.transfer_id WHERE e.id = ?::uuid AND p.variant_id = ? " +
            "ORDER BY tp.created_at, tp.id", String.class, ev, variant);
    }

    private String soldCode(Object saleId) {
        return jdbc.queryForObject("SELECT p.short_code FROM bazar_sales s JOIN pieces p ON p.id = s.piece_id WHERE s.id = ?::uuid",
            String.class, String.valueOf(saleId));
    }

    // ── tap to sell ─────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void tap_sellsTheOldestOpenPiece_throughTheScanSalesOwnWrites() {
        String ev = liveEvent(shirtWhiteM, shirtWhiteM, shirtWhiteM);
        List<String> order = scanned(ev, shirtWhiteM);
        ResponseEntity<Map> r = tap(worker, ev, shirtWhiteM, "t-" + UUID.randomUUID());
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        Object saleId = r.getBody().get("id");
        assertThat(soldCode(saleId)).as("the oldest scanned-out piece").isEqualTo(order.get(0));
        // The same writes as a scan sale: ledger event, transfer piece, line counter, one payment row at the price.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.short_code = ? AND pe.event_type = 'bazar_sold' AND pe.to_status = 'sold'", Integer.class, order.get(0))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT tp.outcome FROM bazar_sales s JOIN transfer_pieces tp ON tp.id = s.transfer_piece_id " +
            "WHERE s.id = ?::uuid", String.class, String.valueOf(saleId))).isEqualTo("sold");
        assertThat(jdbc.queryForObject("SELECT tl.qty_sold FROM transfer_lines tl JOIN bazar_events e ON e.transfer_id = tl.transfer_id " +
            "WHERE e.id = ?::uuid AND tl.variant_id = ?", Integer.class, ev, shirtWhiteM)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM bazar_payments WHERE sale_id = ?::uuid AND kind = 'payment'",
            java.math.BigDecimal.class, String.valueOf(saleId))).isEqualByComparingTo("960");
        assertThat(soldCode(tap(worker, ev, shirtWhiteM, "t-" + UUID.randomUUID()).getBody().get("id"))).isEqualTo(order.get(1));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void sameRequestIdTwice_isOneSale() {
        String ev = liveEvent(teeS, teeS);
        String crid = "t-" + UUID.randomUUID();
        Object first = tap(worker, ev, teeS, crid).getBody().get("id");
        ResponseEntity<Map> again = tap(worker, ev, teeS, crid);
        assertThat(again.getBody().get("id")).isEqualTo(first);
        assertThat(again.getBody().get("replayed")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bazar_sales WHERE event_id = ?::uuid", Integer.class, ev)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void noneLeft_isSoldOut_withTheVariantAndLeftCount() {
        String ev = liveEvent(teeM);
        assertThat(tap(worker, ev, teeM, "t-" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = tap(worker, ev, teeM, "t-" + UUID.randomUUID());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_SOLD_OUT");
        Map d = (Map) r.getBody().get("details");
        assertThat(d.get("variant_id")).isEqualTo(teeM.toString());
        assertThat(d.get("left")).isEqualTo(0);
        assertThat((String) r.getBody().get("message_en")).contains("Cotton tee").contains("sold out");
        assertThat((String) r.getBody().get("message_ar")).contains("نفد");
    }

    @Test
    void twoTapsOnTheLastPiece_atOnce_oneSaleOneSoldOut() throws Exception {
        String ev = liveEvent(scarfBlack);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> { go.await(); return tap(worker, ev, scarfBlack, "c-" + UUID.randomUUID()).getStatusCode().value(); }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) codes.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bazar_sales WHERE event_id = ?::uuid", Integer.class, ev)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aPieceAnotherSaleIsWriting_isSkipped_neverWaitedOn() throws Exception {
        String ev = liveEvent(shirtBlueM, shirtBlueM);
        List<String> order = scanned(ev, shirtBlueM);
        String oldestTp = jdbc.queryForObject("SELECT tp.id::text FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id " +
            "WHERE p.short_code = ?", String.class, order.get(0));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM transfer_pieces WHERE id = ?::uuid FOR UPDATE")) {
                ps.setString(1, oldestTp);
                ps.executeQuery();
            }
            // The oldest is held by "another sale": the tap takes the next one at once.
            Future<ResponseEntity<Map>> r = pool.submit(() -> tap(worker, ev, shirtBlueM, "s-" + UUID.randomUUID()));
            ResponseEntity<Map> sold = r.get(10, TimeUnit.SECONDS);
            assertThat(soldCode(sold.getBody().get("id"))).isEqualTo(order.get(1));
            // Only the held one is left: sold out at once, not a wait.
            ResponseEntity<Map> none = pool.submit(() -> tap(worker, ev, shirtBlueM, "s-" + UUID.randomUUID())).get(10, TimeUnit.SECONDS);
            assertThat(none.getBody().get("code")).isEqualTo("BAZAR_SOLD_OUT");
            assertThat(((Map) none.getBody().get("details")).get("left")).as("the held piece still counts").isEqualTo(1);
            c.rollback();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    void tappedSale_undoAndPaymentChange_workLikeAScanSale() {
        String ev = liveEvent(scarfGrey);
        Object sale = tap(worker, ev, scarfGrey, "u-" + UUID.randomUUID()).getBody().get("id");
        assertThat(call(owner, HttpMethod.PUT, "/api/v1/bazar-events/" + ev + "/sales/" + sale + "/payment",
            Map.of("method", "card")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> undone = call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sales/" + sale + "/undo", Map.of());
        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().get("status")).isEqualTo("undone");
        // Back on the bazar's stock: tappable again.
        assertThat(tap(worker, ev, scarfGrey, "u-" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cart_tappedLines_getDistinctPieces_neverTheScannedOne_andASoldOutLineNamesItsIndex() {
        String ev = liveEvent(teeS, teeS, teeS);
        List<String> order = scanned(ev, teeS);
        // Scan the OLDEST piece and tap two more: the taps must take the other two, never the scanned one.
        List<Map<String, Object>> lines = List.of(Map.of("variant_id", teeS.toString()), Map.of("code", order.get(0)),
            Map.of("variant_id", teeS.toString()));
        ResponseEntity<Map> preview = call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/cart/preview", Map.of("lines", lines));
        assertThat(preview.getBody().get("ok")).isEqualTo(true);
        assertThat(preview.getBody().get("subtotal")).isEqualTo(1080.0);
        ResponseEntity<Map> done = call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/cart/complete",
            Map.of("lines", lines, "method", "cash", "client_request_id", "k-" + UUID.randomUUID()));
        assertThat(done.getStatusCode()).as(String.valueOf(done.getBody())).isEqualTo(HttpStatus.OK);
        List<String> sold = jdbc.queryForList("SELECT p.short_code FROM bazar_sales s JOIN pieces p ON p.id = s.piece_id " +
            "WHERE s.receipt_id = ?::uuid ORDER BY s.line_no", String.class, String.valueOf(done.getBody().get("id")));
        assertThat(sold).hasSize(3).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(order);
        assertThat(sold.get(1)).as("the scanned line is the scanned piece").isEqualTo(order.get(0));

        // One more tap than is left: preview flags it, Complete refuses that line and writes nothing.
        String ev2 = liveEvent(teeM);
        List<Map<String, Object>> two = List.of(Map.of("variant_id", teeM.toString()), Map.of("variant_id", teeM.toString()));
        List<Map> pl = (List<Map>) call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev2 + "/cart/preview",
            Map.of("lines", two)).getBody().get("lines");
        assertThat(pl.get(0).get("error")).isNull();
        assertThat(((Map) pl.get(1).get("error")).get("code")).isEqualTo("BAZAR_SOLD_OUT");
        ResponseEntity<Map> refused = call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev2 + "/cart/complete",
            Map.of("lines", two, "method", "cash", "client_request_id", "k-" + UUID.randomUUID()));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("BAZAR_SOLD_OUT");
        assertThat(((Map) refused.getBody().get("details")).get("line_index")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bazar_sales WHERE event_id = ?::uuid", Integer.class, ev2)).isZero();
    }

    @Test
    void tapSalesAndTappedCarts_writeNoShopifyClaim() {
        int adjBefore = jdbc.queryForObject("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", Integer.class, tenant);
        int syncBefore = jdbc.queryForObject("SELECT count(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", Integer.class, tenant);
        String ev = liveEvent(shirtWhiteL, shirtWhiteL, shirtWhiteL);
        int syncAfterSend = jdbc.queryForObject("SELECT count(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", Integer.class, tenant);
        tap(worker, ev, shirtWhiteL, "n-" + UUID.randomUUID());
        call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/cart/complete", Map.of("lines",
            List.of(Map.of("variant_id", shirtWhiteL.toString()), Map.of("variant_id", shirtWhiteL.toString())),
            "method", "cash", "client_request_id", "n-" + UUID.randomUUID()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bazar_sales WHERE event_id = ?::uuid", Integer.class, ev)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", Integer.class, tenant))
            .isEqualTo(adjBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", Integer.class, tenant))
            .as("only the send's own claim, nothing from the sales").isEqualTo(syncAfterSend).isLessThanOrEqualTo(syncBefore + 1);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aProductNumber_neverSellsAsACode() {
        String ev = liveEvent(scarfGrey);
        for (String code : List.of("12", "#12", "١٢", "#1")) {
            ResponseEntity<Map> r = call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sell",
                Map.of("code", code, "client_request_id", "pn-" + UUID.randomUUID(), "method", "cash"));
            assertThat(r.getStatusCode()).as(code).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code")).isEqualTo("BAZAR_PRODUCT_NUMBER");
        }
        assertThat(call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/return",
            Map.of("code", "#1", "condition", "good", "client_request_id", "pn-" + UUID.randomUUID())).getBody().get("code"))
            .isEqualTo("BAZAR_PRODUCT_NUMBER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bazar_sales WHERE event_id = ?::uuid", Integer.class, ev)).isZero();
    }

    // ── numbers ─────────────────────────────────────────────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map> products(String token, String ev) {
        ResponseEntity<Map> r = call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev + "/catalog", null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return (List<Map>) r.getBody().get("products");
    }

    private static String label(Map p) {
        return p.get("number") + " " + p.get("title") + (p.get("colour") == null ? "" : " / " + p.get("colour"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void numbers_perProductAndColour_byTitleThenColour_stable_nextFreeMidEvent() {
        String ev = liveEvent(shirtWhiteM, shirtWhiteL, shirtBlueM, teeS, teeM, scarfGrey, scarfBlack);
        List<Map> ps = products(worker, ev);
        assertThat(ps.stream().map(BazarSellByProductTest::label).toList()).containsExactly(
            "1 Cotton tee", "2 Knit scarf / Black", "3 Knit scarf / Grey", "4 Linen shirt / Blue", "5 Linen shirt / White");
        Map white = ps.get(4);
        assertThat(((List<Map>) white.get("sizes")).stream().map(s -> s.get("size")).toList()).containsExactly("M", "L");
        assertThat(((List<Map>) ps.get(0).get("sizes")).stream().map(s -> s.get("size")).toList()).containsExactly("S", "M");
        assertThat(ps.get(1).get("sizes").toString()).contains("size=null");
        assertThat(white.get("category")).isEqualTo("Shirts");
        assertThat(white.get("left")).isEqualTo(2);

        // A product priced mid-event gets the NEXT number ("Apron" sorts first by title, but 1 is taken).
        HttpHeaders h = bearer(owner);
        h.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange(base() + "/api/v1/bazar-events/" + ev + "/prices/" + apron, HttpMethod.PUT,
            new HttpEntity<>(Map.of("price", 150), h), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> again = products(owner, ev);
        assertThat(again.stream().map(BazarSellByProductTest::label).toList()).containsExactly(
            "1 Cotton tee", "2 Knit scarf / Black", "3 Knit scarf / Grey", "4 Linen shirt / Blue", "5 Linen shirt / White", "6 Apron");
        // Selling never renumbers.
        tap(worker, ev, teeS, "nn-" + UUID.randomUUID());
        tap(worker, ev, teeM, "nn-" + UUID.randomUUID());
        assertThat(products(worker, ev).get(0).get("number")).isEqualTo(1);
        assertThat(products(worker, ev).get(0).get("left")).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bazar_event_product_numbers WHERE event_id = ?::uuid", Integer.class, ev)).isEqualTo(6);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void whoMayUseIt_sellersAndManagers_notOtherRoles_notRemoved_notOtherTenants() {
        String ev = liveEvent(List.of("owner", "manager"), teeS);
        for (String path : List.of("/catalog", "/price-sheet.pdf")) {
            assertThat(rest.exchange(base() + "/api/v1/bazar-events/" + ev + path, HttpMethod.GET,
                new HttpEntity<>(bearer(worker)), String.class).getStatusCode()).as("worker not in seller_roles " + path)
                .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(rest.exchange(base() + "/api/v1/bazar-events/" + ev + path, HttpMethod.GET,
                new HttpEntity<>(bearer(ownerOther)), String.class).getStatusCode()).as("other tenant " + path)
                .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(tap(worker, ev, teeS, "w-" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        String ev2 = liveEvent(teeS);
        assertThat(products(worker, ev2)).isNotEmpty();
        jdbc.update("INSERT INTO bazar_event_sellers (tenant_id, event_id, user_id, added_by, removed_at, removed_by) " +
            "SELECT ?, ?::uuid, ?, ?, now(), ? ", tenant, ev2, workerId, workerId, workerId);
        assertThat(call(worker, HttpMethod.GET, "/api/v1/bazar-events/" + ev2 + "/catalog", null).getStatusCode())
            .as("removed seller").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(tap(worker, ev2, teeS, "w-" + UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void rls_appUser_seesOnlyItsOwnNumbers() {
        String ev = liveEvent(scarfGrey);
        products(owner, ev);
        TenantAwareDataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        String sql = "SELECT COUNT(*) FROM bazar_event_product_numbers WHERE event_id = ?::uuid";
        try {
            TenantContext.set(otherTenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev))).isZero();
            TenantContext.clear();
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev))).as("no tenant").isZero();
            TenantContext.set(tenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev))).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('app_user', 'bazar_event_product_numbers', 'UPDATE') " +
            "OR has_table_privilege('app_user', 'bazar_event_product_numbers', 'DELETE')", Boolean.class)).isFalse();
    }

    // ── price sheet ─────────────────────────────────────────────────────────

    private byte[] sheet(String token, String ev, String lang) {
        ResponseEntity<byte[]> r = rest.exchange(base() + "/api/v1/bazar-events/" + ev + "/price-sheet.pdf?lang=" + lang,
            HttpMethod.GET, new HttpEntity<>(bearer(token)), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        return r.getBody();
    }

    private String sheetText(String token, String ev, String lang) throws Exception {
        try (PDDocument doc = Loader.loadPDF(sheet(token, ev, lang))) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** Every glyph's own Unicode as drawn (TextPosition), before PDFTextStripper folds presentation forms back. */
    private String rawGlyphs(byte[] pdf) throws Exception {
        StringBuilder out = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    for (org.apache.pdfbox.text.TextPosition p : positions) out.append(p.getUnicode());
                }
            };
            s.getText(doc);
        }
        return out.toString();
    }

    @Test
    void priceSheet_en_listsThisEventsProductsByNumber_withSizesLeftAndPrices() throws Exception {
        String ev = liveEvent(shirtWhiteM, shirtWhiteL, teeS);
        tap(worker, ev, teeS, "ps-" + UUID.randomUUID());   // Cotton tee: none left → "Sold out"
        String text = sheetText(worker, ev, "en");
        assertThat(text).contains("Bazar price sheet").contains("B7 Bazar " + seq)
            .contains("#1").contains("Cotton tee").contains("Sold out")
            .contains("#2").contains("Linen shirt").contains("White").contains("M · L").contains("960 EGP");
        assertThat(text).doesNotContain("Knit scarf").doesNotContain("Apron");
    }

    @Test
    void priceSheet_ar_isShaped_withArabicHeadings() throws Exception {
        String ev = liveEvent(scarfGrey);
        byte[] pdf = sheet(worker, ev, "ar");
        String glyphs = rawGlyphs(pdf);
        // Shaped Arabic: the glyphs drawn are Arabic presentation forms (U+FB50–U+FDFF, U+FE70–U+FEFF).
        assertThat(glyphs.codePoints().anyMatch(cp -> (cp >= 0xFE70 && cp <= 0xFEFF) || (cp >= 0xFB50 && cp <= 0xFDFF)))
            .as("shaped Arabic glyphs").isTrue();
        String text = sheetText(worker, ev, "ar");
        assertThat(text).contains("ج.م");
        assertThat(text).contains("Knit scarf").contains("320").contains("#1");
    }
}
