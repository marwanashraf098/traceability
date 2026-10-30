package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stock-take review (2026-10-27) — the app runs as app_user (RLS on, like production); every call goes
 * through real HTTP + JWT. The bug: POST /resolve was not @Transactional, so the tenant GUC never reached
 * the database and every resolution answered a bare 404 on an OPEN session (Nuvira 6fffc477).
 *
 *   rv1 resolve mark_damaged on a condition_mismatch row → 200, the piece is damaged (404 before the fix)
 *   rv2 resolve 'found' / 'lost' → 400 STOCK_TAKE_ACTION_REMOVED with a message, nothing written
 *   rv3 the review-page scan (the same POST /scan as the counting screen) counts an uncounted expected piece;
 *       a piece outside the count is recorded as an unexpected find
 *   rv4 the finalize plan lists every piece it writes off (code, product, variant, origin, Shopify effect);
 *       after finalize the review marks writtenOffHere on exactly those rows
 *   rv5 tenant isolation: another tenant's owner gets 404 for the session (no rows, no write)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakeReviewRlsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService       jwt;
    @MockBean  ShopifyGateway   shopify;
    @MockBean  BostaGateway     bosta;
    @MockBean  JobScheduler     jobScheduler;
    @MockBean  ShopifyTokenProvider tokens;

    JdbcTemplate owner;   // postgres — fixtures and assertions only

    record Tenant(UUID id, UUID location, UUID owner, UUID variant) {}

    @BeforeAll
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    // ── rv1 ──────────────────────────────────────────────────────────────────

    @Test
    void rv1_markDamaged_onOpenSession_worksUnderRls() {
        Tenant t = tenant("rv1");
        String piece = piece(t, "available");
        UUID session = open(t);
        // Scanned damaged while Traced says available → condition_mismatch, the Apply damaged row.
        assertThat(scan(t, session, barcode(piece), "damaged").getBody()).containsEntry("classification", "condition_mismatch");

        ResponseEntity<List> r = rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/resolve"), HttpMethod.POST,
            new HttpEntity<>(List.of(Map.of("pieceId", piece, "action", "mark_damaged")), headers(t)), List.class);

        assertThat(r.getStatusCode()).as("rv1: resolve on an open session, as app_user").isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
        assertThat(status(piece)).isEqualTo("damaged");
    }

    // ── rv2 ──────────────────────────────────────────────────────────────────

    @Test
    void rv2_foundAndLost_areRefusedWith400_nothingWritten() {
        Tenant t = tenant("rv2");
        String piece = piece(t, "available");
        UUID session = open(t);
        attest(t, session);

        for (String action : List.of("found", "lost")) {
            ResponseEntity<Map> r = rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/resolve"), HttpMethod.POST,
                new HttpEntity<>(List.of(Map.of("pieceId", piece, "action", action)), headers(t)), Map.class);
            assertThat(r.getStatusCode()).as("rv2 " + action).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).as("rv2 " + action).containsEntry("code", "STOCK_TAKE_ACTION_REMOVED");
            assertThat((String) r.getBody().get("message_en")).isNotBlank();
            assertThat((String) r.getBody().get("message_ar")).isNotBlank();
        }
        assertThat(status(piece)).isEqualTo("available");
        assertThat(owner.queryForObject("SELECT count(*) FROM stock_take_scans WHERE session_id = ?", Integer.class, session))
            .isZero();
    }

    // ── rv3 ──────────────────────────────────────────────────────────────────

    @Test
    void rv3_reviewScan_countsAnUncountedPiece_outsiderIsAnUnexpectedFind() {
        Tenant t = tenant("rv3");
        String counted = piece(t, "available");
        String missed  = piece(t, "available");
        UUID session = open(t);
        scan(t, session, barcode(counted), "good");
        assertThat(bucketIds(t, session, "on_shelf_uncounted")).containsExactly(missed);

        // The review page's overlay sends the same request as the counting screen.
        ResponseEntity<Map> found = scan(t, session, barcode(missed), "good");
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).containsEntry("classification", "match").containsEntry("alreadyScanned", false);
        assertThat(bucketIds(t, session, "on_shelf_uncounted")).isEmpty();
        assertThat(bucketIds(t, session, "on_shelf_counted")).containsExactlyInAnyOrder(counted, missed);

        // Scanning it again is "already counted", not a second row.
        assertThat(scan(t, session, barcode(missed), "good").getBody()).containsEntry("alreadyScanned", true);

        // A piece that arrived after the count opened is not in it → recorded as an unexpected find.
        String outsider = piece(t, "available");
        assertThat(scan(t, session, barcode(outsider), "good").getBody()).containsEntry("classification", "out_of_scope");
        assertThat(bucketIds(t, session, "unexpected_finds")).containsExactly(outsider);
    }

    // ── rv4 ──────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void rv4_finalizePlan_listsEveryWriteOff_thenReviewMarksThem() {
        Tenant t = tenant("rv4");
        String a = piece(t, "available"), b = piece(t, "available"), lostGood = piece(t, "available");
        String lostDamaged = piece(t, "damaged");
        UUID session = open(t);
        scan(t, session, barcode(a), "good");
        scan(t, session, barcode(b), "good");
        attest(t, session);

        Map<String, Object> plan = (Map<String, Object>) reconciliation(t, session).get("finalizePlan");
        assertThat(plan).containsEntry("writeOffs", 2).containsEntry("shopifyDecrement", 1);
        List<Map<String, Object>> pieces = (List<Map<String, Object>>) plan.get("writeOffPieces");
        assertThat(pieces).extracting(p -> p.get("pieceId")).containsExactlyInAnyOrder(lostGood, lostDamaged);
        Map<String, Object> good = pieces.stream().filter(p -> lostGood.equals(p.get("pieceId"))).findFirst().orElseThrow();
        Map<String, Object> dmg  = pieces.stream().filter(p -> lostDamaged.equals(p.get("pieceId"))).findFirst().orElseThrow();
        assertThat(good).containsEntry("origin", "available").containsEntry("shopifyEffect", -1)
            .containsEntry("productTitle", "Review Product").containsEntry("variantTitle", "Blue / M")
            .containsEntry("shortCode", shortCode(lostGood));
        assertThat(dmg).containsEntry("origin", "damaged").containsEntry("shopifyEffect", 0);

        ResponseEntity<Map> fin = rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/finalize"), HttpMethod.POST,
            new HttpEntity<>(Map.of("confirmWriteOffs", 2), headers(t)), Map.class);
        assertThat(fin.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> after = reconciliation(t, session);
        assertThat(after).doesNotContainKey("finalizePlan");
        List<Map<String, Object>> rows = allRows(after);
        assertThat(rows).filteredOn(r -> Boolean.TRUE.equals(r.get("writtenOffHere")))
            .extracting(r -> r.get("pieceId")).containsExactlyInAnyOrder(lostGood, lostDamaged);
        assertThat(rows).noneMatch(r -> Boolean.TRUE.equals(r.get("foundHere")));
    }

    // ── rv5 ──────────────────────────────────────────────────────────────────

    @Test
    void rv5_anotherTenant_cannotSeeOrResolveTheSession() {
        Tenant t = tenant("rv5a"), other = tenant("rv5b");
        String piece = piece(t, "available");
        UUID session = open(t);
        scan(t, session, barcode(piece), "damaged");

        // Positive control: the owner's own tenant sees the session.
        assertThat(rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/reconciliation"), HttpMethod.GET,
            new HttpEntity<>(headers(t)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/reconciliation"), HttpMethod.GET,
            new HttpEntity<>(headers(other)), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/resolve"), HttpMethod.POST,
            new HttpEntity<>(List.of(Map.of("pieceId", piece, "action", "mark_damaged")), headers(other)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(piece)).isEqualTo("available");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> allRows(Map<String, Object> rec) {
        Map<String, List<Map<String, Object>>> buckets = (Map<String, List<Map<String, Object>>>) rec.get("buckets");
        return buckets.values().stream().flatMap(List::stream).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Object> bucketIds(Tenant t, UUID session, String bucket) {
        Map<String, List<Map<String, Object>>> buckets =
            (Map<String, List<Map<String, Object>>>) reconciliation(t, session).get("buckets");
        return buckets.get(bucket).stream().map(r -> r.get("pieceId")).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reconciliation(Tenant t, UUID session) {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/reconciliation"),
            HttpMethod.GET, new HttpEntity<>(headers(t)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> scan(Tenant t, UUID session, String barcode, String condition) {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/scan"), HttpMethod.POST,
            new HttpEntity<>(Map.of("barcode", barcode, "condition", condition), headers(t)), Map.class);
        assertThat(r.getStatusCode()).as("scan " + barcode).isEqualTo(HttpStatus.OK);
        return r;
    }

    private void attest(Tenant t, UUID session) {
        assertThat(rest.exchange(url("/api/v1/stock-takes/sessions/" + session + "/attest-complete"), HttpMethod.POST,
            new HttpEntity<>(headers(t)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID open(Tenant t) {
        ResponseEntity<Map> r = rest.exchange(url("/api/v1/stock-takes/sessions"), HttpMethod.POST,
            new HttpEntity<>(Map.of("scopeType", "variant_subset", "variantIds", List.of(t.variant().toString()),
                "locationId", t.location().toString()), headers(t)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("sessionId"));
    }

    private String status(String piece) {
        return owner.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    private String barcode(String piece) { return "PC-" + piece; }

    private String shortCode(String piece) {
        return owner.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, piece);
    }

    private String piece(Tenant t, String status) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
        owner.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                     "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?)",
            id, t.id(), t.variant(), barcode(id), "R" + id.substring(0, 8), status, t.location());
        return id;
    }

    private Tenant tenant(String tag) {
        UUID id = UUID.randomUUID(), loc = UUID.randomUUID(), store = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID(), user = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Review " + tag);
        owner.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                     "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", loc, id);
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                     "VALUES (?, ?, ?, ?, 'h', 'owner'::user_role, true)", user, id, "Owner " + tag, tag + "-" + user + "@test.local");
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, "review-" + store + ".myshopify.com");
        owner.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Review Product')",
            product, id, store, "gid://shopify/Product/" + product);
        owner.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'Blue / M', 'RV-BM')",
            variant, id, product, "gid://shopify/ProductVariant/" + variant);
        return new Tenant(id, loc, user, variant);
    }

    private HttpHeaders headers(Tenant t) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt.issueAccessToken(t.owner(), t.id(), "owner"));
        return h;
    }

    private String url(String path) { return "http://localhost:" + port + path; }
}
