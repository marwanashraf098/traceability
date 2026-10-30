package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
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

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Lookup "Reprint label" — POST /api/v1/pieces/{id}/label/reprint.
 *
 * The allow-list (available / on_hold / damaged / return_pending_inspection / reserved) prints the
 * piece's EXISTING short code and writes exactly one piece_events 'label_reprinted' row with
 * from_status = to_status = the current status; the pieces row is untouched. Every other status
 * is 422 REPRINT_NOT_ALLOWED with no row. Workers are 403. Another tenant's piece is 404 — over
 * HTTP (explicit tenant filter) and over an app_user connection (RLS). No Shopify call, ever.
 *
 * Fixtures use the real shapes: id = ULID, barcode = "PC-" + id (what every receiving batch
 * writes, and what pre-V55 stickers carried), short_code = "P000123" style.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PieceLabelReprintTest {

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

    /** Spelled out here (not read from the constant) so a change to the constant fails a test. */
    static final Set<PieceStatus> EXPECTED_ALLOWED = EnumSet.of(
        PieceStatus.AVAILABLE, PieceStatus.ON_HOLD, PieceStatus.DAMAGED,
        PieceStatus.RETURN_PENDING_INSPECTION, PieceStatus.RESERVED);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler jobScheduler;
    @SpyBean LabelService labelService;

    UUID tenantA, ownerA, managerA, workerA, storeA, locationA, variantA;
    UUID tenantB, ownerB, storeB, locationB, variantB;
    String ownerToken, managerToken, workerToken;
    final AtomicInteger nextCode = new AtomicInteger(123);

    @BeforeAll
    void setup() {
        tenantA = UUID.randomUUID(); ownerA = UUID.randomUUID(); managerA = UUID.randomUUID();
        workerA = UUID.randomUUID(); storeA = UUID.randomUUID(); locationA = UUID.randomUUID();
        variantA = UUID.randomUUID();
        tenantB = UUID.randomUUID(); ownerB = UUID.randomUUID(); storeB = UUID.randomUUID();
        locationB = UUID.randomUUID(); variantB = UUID.randomUUID();

        tenant(tenantA, "Reprint Co A", storeA, locationA, variantA, "reprint-a",
            "بلوزة قطنية واسعة", "أحمر / M", "BLZ-RED-M");
        tenant(tenantB, "Reprint Co B", storeB, locationB, variantB, "reprint-b",
            "Linen Shirt", "White / L", "LIN-W-L");
        user(ownerA, tenantA, "owner");
        user(managerA, tenantA, "manager");
        user(workerA, tenantA, "worker");
        user(ownerB, tenantB, "owner");

        ownerToken   = jwt.issueAccessToken(ownerA,   tenantA, "owner");
        managerToken = jwt.issueAccessToken(managerA, tenantA, "manager");
        workerToken  = jwt.issueAccessToken(workerA,  tenantA, "worker");
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── the one allow-list ────────────────────────────────────────────────────

    @Test
    void allowList_isExactlyTheApprovedFive() {
        assertThat(PieceLabelReprintService.REPRINTABLE).isEqualTo(EXPECTED_ALLOWED);
    }

    // ── allowed statuses ──────────────────────────────────────────────────────

    @Test
    void allowedStatus_owner_200_printsExistingShortCode_oneEvent_pieceRowUntouched() throws Exception {
        for (PieceStatus status : EXPECTED_ALLOWED) {
            String piece = piece(tenantA, variantA, locationA, status);
            String before = rowSnapshot(piece);

            ResponseEntity<byte[]> resp = post(piece, ownerToken);

            assertThat(resp.getStatusCode()).as(status.db).isEqualTo(HttpStatus.OK);
            assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
            LabelEndpointsTest.assertLabels(resp.getBody(), List.of(shortCode(piece)), 50f, 25f);

            List<Map<String, Object>> events = reprintEvents(piece);
            assertThat(events).as(status.db + ": exactly one label_reprinted row").hasSize(1);
            assertThat(events.get(0).get("from_status")).isEqualTo(status.db);
            assertThat(events.get(0).get("to_status")).isEqualTo(status.db);
            assertThat(events.get(0).get("actor_user_id")).isEqualTo(ownerA);
            assertThat(events.get(0).get("location_id")).isEqualTo(locationA);
            assertThat(rowSnapshot(piece)).as(status.db + ": pieces row unchanged").isEqualTo(before);
        }
        verifyNoInteractions(shopifyGateway);
    }

    @Test
    void manager_200() throws Exception {
        String piece = piece(tenantA, variantA, locationA, PieceStatus.AVAILABLE);
        ResponseEntity<byte[]> resp = post(piece, managerToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reprintEvents(piece)).hasSize(1);
        assertThat(reprintEvents(piece).get(0).get("actor_user_id")).isEqualTo(managerA);
    }

    @Test
    void legacyPiece_printsItsShortCode_notTheOldPcBarcode() throws Exception {
        String piece = piece(tenantA, variantA, locationA, PieceStatus.AVAILABLE);
        String barcode = jdbc.queryForObject("SELECT barcode FROM pieces WHERE id = ?", String.class, piece);
        assertThat(barcode).startsWith("PC-");

        ResponseEntity<byte[]> resp = post(piece, ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sc = shortCode(piece);
        assertThat(sc).matches("P\\d{6}");
        LabelEndpointsTest.assertLabels(resp.getBody(), List.of(sc), 50f, 25f);
    }

    @Test
    void reservedPiece_eventCarriesOrder_andNewestForwardShipment() {
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status) " +
            "VALUES (?, ?, ?, ?, '#1042', 'picking'::order_status)", order, tenantA, storeA, "gid://shopify/Order/" + order);
        UUID oldLeg = UUID.randomUUID(), liveLeg = UUID.randomUUID();
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at) " +
            "VALUES (?, ?, ?, 'bosta', '4829163057', 'terminated', 'forward', now() - interval '2 days')", oldLeg, tenantA, order);
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, created_at) " +
            "VALUES (?, ?, ?, 'bosta', '4829163058', 'created', 'forward', now())", liveLeg, tenantA, order);
        String piece = piece(tenantA, variantA, locationA, PieceStatus.RESERVED);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", order, piece);

        ResponseEntity<byte[]> resp = post(piece, ownerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> e = reprintEvents(piece).get(0);
        assertThat(e.get("order_id")).isEqualTo(order);
        assertThat(e.get("shipment_id")).isEqualTo(liveLeg);
        assertThat(e.get("from_status")).isEqualTo("reserved");
    }

    @Test
    void renderFailure_500_eventRolledBack() throws Exception {
        String piece = piece(tenantA, variantA, locationA, PieceStatus.AVAILABLE);
        doThrow(new java.io.IOException("render failed"))
            .when(labelService).generatePieceLabel(eq(piece), any(), any());

        ResponseEntity<String> resp = postForString(piece, ownerToken);

        assertThat(resp.getStatusCode().is5xxServerError()).isTrue();
        assertThat(reprintEvents(piece)).as("the event rolls back with the failed render").isEmpty();
    }

    // ── blocked statuses ──────────────────────────────────────────────────────

    @Test
    void everyOtherStatus_422_reprintNotAllowed_noEvent() throws Exception {
        for (PieceStatus status : PieceStatus.values()) {
            if (EXPECTED_ALLOWED.contains(status)) continue;
            String piece = piece(tenantA, variantA, locationA, status);
            String before = rowSnapshot(piece);

            ResponseEntity<String> resp = postForString(piece, ownerToken);

            assertThat(resp.getStatusCode().value()).as(status.db).isEqualTo(422);
            JsonNode body = json.readTree(resp.getBody());
            assertThat(body.path("code").asText()).isEqualTo("REPRINT_NOT_ALLOWED");
            assertThat(body.path("message_en").asText()).isNotBlank();
            assertThat(body.path("message_ar").asText()).isNotBlank();
            assertThat(reprintEvents(piece)).as(status.db + ": no event").isEmpty();
            assertThat(rowSnapshot(piece)).isEqualTo(before);
        }
        verifyNoInteractions(shopifyGateway);
    }

    // ── roles ─────────────────────────────────────────────────────────────────

    @Test
    void worker_403_noEvent() {
        String piece = piece(tenantA, variantA, locationA, PieceStatus.AVAILABLE);

        ResponseEntity<String> resp = postForString(piece, workerToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reprintEvents(piece)).isEmpty();
    }

    // ── tenant isolation ──────────────────────────────────────────────────────

    @Test
    void http_otherTenantsPiece_404_noEvent_sameTenantPositiveControl_200() {
        String theirs = piece(tenantB, variantB, locationB, PieceStatus.AVAILABLE);
        String ours   = piece(tenantA, variantA, locationA, PieceStatus.AVAILABLE);

        assertThat(postForString(theirs, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reprintEvents(theirs)).isEmpty();

        assertThat(post(ours, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reprintEvents(ours)).hasSize(1);
    }

    @Test
    void appUser_rls_otherTenantsPiece_404_noEvent_sameTenantPositiveControl() {
        String theirs = piece(tenantB, variantB, locationB, PieceStatus.AVAILABLE);
        String ours   = piece(tenantA, variantA, locationA, PieceStatus.AVAILABLE);

        TenantAwareDataSource ds = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        PieceLabelReprintService svc = new PieceLabelReprintService(
            appJdbc, new InventoryLedger(appJdbc), new LabelService(appJdbc));

        TenantContext.set(tenantA);
        assertThatThrownBy(() -> appTx.execute(s -> {
                try { return svc.reprint(theirs, ownerA); } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        byte[] pdf = appTx.execute(s -> {
            try { return svc.reprint(ours, ownerA); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        });
        TenantContext.clear();

        assertThat(pdf).isNotEmpty();
        assertThat(reprintEvents(theirs)).as("no event on the other tenant's piece").isEmpty();
        assertThat(reprintEvents(ours)).as("positive control: same-tenant reprint recorded").hasSize(1);
    }

    // ── lookup carries canReprint from the same constant ─────────────────────

    @Test
    void lookup_canReprint_followsTheAllowList() throws Exception {
        for (PieceStatus status : PieceStatus.values()) {
            String piece = piece(tenantA, variantA, locationA, status);
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(ownerToken);
            ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/lookup?q=" + shortCode(piece),
                HttpMethod.GET, new HttpEntity<>(h), String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json.readTree(resp.getBody()).path("canReprint").asBoolean())
                .as(status.db).isEqualTo(EXPECTED_ALLOWED.contains(status));
        }
    }

    // ── fixtures / helpers ────────────────────────────────────────────────────

    private void tenant(UUID tenant, String name, UUID store, UUID location, UUID variant, String shop,
                        String productTitle, String variantTitle, String sku) {
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', ?, 'disconnected')", store, tenant, shop + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", location, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, ?, ?, 'active')", product, tenant, store, "gid://shopify/Product/" + product, productTitle);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, ?, ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + variant, variantTitle, sku);
    }

    private void user(UUID id, UUID tenant, String role) {
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, ?, 'x', ?::user_role, true)", id, tenant, role, role + "+" + id + "@reprint.test", role);
    }

    /** A piece in the real shape: ULID id, barcode "PC-" + id, short code "P000123". */
    private String piece(UUID tenant, UUID variant, UUID location, PieceStatus status) {
        String id = UlidGenerator.generate();
        String sc = String.format("P%06d", nextCode.getAndIncrement());
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, now() - interval '1 day')",
            id, tenant, variant, "PC-" + id, sc, status.db, location);
        return id;
    }

    private String shortCode(String piece) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, piece);
    }

    private String rowSnapshot(String piece) {
        return jdbc.queryForObject("SELECT to_jsonb(p)::text FROM pieces p WHERE id = ?", String.class, piece);
    }

    private List<Map<String, Object>> reprintEvents(String piece) {
        return jdbc.queryForList(
            "SELECT from_status::text AS from_status, to_status::text AS to_status, actor_user_id, " +
            "       order_id, shipment_id, location_id " +
            "FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'", piece);
    }

    private ResponseEntity<byte[]> post(String piece, String token) {
        return rest.exchange(url(piece), HttpMethod.POST, new HttpEntity<>(bearer(token)), byte[].class);
    }

    private ResponseEntity<String> postForString(String piece, String token) {
        return rest.exchange(url(piece), HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private String url(String piece) { return base() + "/api/v1/pieces/" + piece + "/label/reprint"; }

    private String base() { return "http://localhost:" + port; }
}
