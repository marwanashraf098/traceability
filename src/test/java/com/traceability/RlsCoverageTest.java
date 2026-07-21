package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.inventory.UlidGenerator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3c: Coverage enforcement for GET /api/ endpoints.
 *
 * Reflectively discovers every @GetMapping under /api/ via RequestMappingHandlerMapping,
 * then asserts each pattern is either:
 *   (a) COVERED — has a seeded non-empty @Test in this class, or
 *   (b) EXEMPT  — deliberately excluded with a written reason.
 *
 * A new GET endpoint that appears in neither set fails the build until it is
 * consciously placed in one.
 *
 * NOTE: These tests connect as postgres (BYPASSRLS) for simplicity — the same
 * pattern used by other integration test classes.  RLS _correctness_ for the
 * eight fixed endpoints is verified by Day10Test (e/e-pos/e-pos2/e-pos3).
 * This class proves _coverage_ (every endpoint reachable, data visible) and
 * acts as the self-enforcing registry of which endpoints still lack RLS tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RlsCoverageTest {

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

    // ── Patterns tested in this class ─────────────────────────────────────────

    private static final Set<String> COVERED = Set.of(
            "/api/v1/users",
            "/api/v1/audit-log",
            "/api/v1/shipments/unlinked",
            "/api/v1/returns/pending",
            "/api/v1/returns/never-received",
            "/api/v1/returns/sessions",
            "/api/v1/returns/sessions/{sessionId}/pieces",
            "/api/v1/orders/daily-counts",
            "/api/v1/lookup"
    );

    // ── Patterns consciously excluded, with reasons ────────────────────────────

    private static final Map<String, String> EXEMPT = Map.ofEntries(
            entry("/api/v1/health",
                    "no DB query — returns static UP status"),
            entry("/api/v1/orders",
                    "uses tx.execute(); empty valid for new tenant; covered by FulfillTest"),
            entry("/api/v1/orders/{orderId}",
                    "single-entity GET; uses tx.execute(); covered by FulfillTest"),
            entry("/api/v1/fulfill/queue",
                    "uses tx.execute(); empty valid for new tenant"),
            entry("/api/v1/fulfill/{orderId}",
                    "single-entity GET; uses tx.execute()"),
            entry("/api/v1/inventory/summary",
                    "uses tx.execute(); all-zeros is valid initial state"),
            entry("/api/v1/pieces",
                    "uses tx.execute(); empty valid for new tenant"),
            entry("/api/v1/locations",
                    "uses tx.execute(); covered by LocationTest"),
            entry("/api/v1/receiving/sessions",
                    "uses tx.execute(); empty valid for new tenant"),
            entry("/api/v1/receiving/sessions/{sessionId}",
                    "single-entity GET; uses tx.execute()"),
            entry("/api/v1/receiving/sessions/{sessionId}/labels",
                    "PDF response, not a JSON RLS-gated list"),
            entry("/api/v1/receiving/sessions/{sessionId}/variants/{variantId}/labels",
                    "PDF response, not a JSON RLS-gated list"),
            entry("/api/v1/receiving/sessions/{sessionId}/pieces",
                    "scoped to session; uses tx.execute()"),
            entry("/api/v1/receiving/variants/search",
                    "search requires ?q= param; empty result is valid"),
            entry("/api/v1/pickup-sessions",
                    "uses tx.execute(); empty valid for new tenant"),
            entry("/api/v1/pickup-sessions/{id}",
                    "single-entity GET; uses tx.execute()"),
            entry("/api/v1/pickup-sessions/{id}/manifest",
                    "single-entity GET; uses tx.execute()"),
            entry("/api/v1/blocklist",
                    "uses tx.execute(); empty blocklist is the normal initial state"),
            entry("/api/v1/exceptions",
                    "uses tx.execute(); empty valid for new tenant"),
            entry("/api/v1/exceptions/resolutions",
                    "static enum list — no RLS table query"),
            entry("/api/v1/shopify/stores",
                    "uses tx.execute(); empty valid for tenant with no stores"),
            entry("/api/v1/shopify/stores/{storeId}/status",
                    "single-entity GET; uses tx.execute()"),
            entry("/api/v1/shopify-inventory/adjustments",
                    "uses tx.execute(); empty valid for no syncs"),
            entry("/api/v1/shopify-inventory/adjustments/export.csv",
                    "CSV download, not a JSON list"),
            entry("/api/v1/catalog",
                    "uses tx.execute(); empty valid for new tenant; covered by CatalogTest"),
            entry("/api/v1/embedded/inventory/summary",
                    "uses embedded token auth; delegates to covered services"),
            entry("/api/v1/embedded/orders/daily-counts",
                    "uses embedded token auth; delegates to covered services"),
            entry("/api/v1/embedded/stores/status",
                    "connection status, not an RLS-gated list"),
            entry("/api/v1/embedded/exceptions",
                    "uses embedded token auth; delegates to covered services"),
            entry("/api/v1/tenant/settings",
                    "single tenant config row — not a list"),
            entry("/api/v1/connections",
                    "connection status; uses tx.execute()"),
            entry("/api/v1/onboarding/status",
                    "onboarding state — not a list"),
            entry("/api/v1/bosta/sync/status",
                    "connection status — not an RLS list"),
            entry("/api/v1/bosta/pickup/manifest/{pickupId}",
                    "single-entity GET; uses tx.execute()"),
            entry("/api/v1/returns/pieces/{pieceId}/label",
                    "label bytes — single-entity GET, not a list"),
            entry("/api/v1/test/probe",
                    "test-only probe controller (src/test/java); not a production endpoint")
    );

    // ── Spring / HTTP wiring ──────────────────────────────────────────────────

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    // ── Per-class shared state ────────────────────────────────────────────────

    String  ownerToken;
    UUID    tenantId;
    UUID    ownerUserId;
    UUID    storeId;
    UUID    productId;
    UUID    variantId;
    UUID    locationId;

    @BeforeAll
    void setup() {
        SignupRequest req = new SignupRequest(
                "Coverage Co", "cov_owner", "cov@test.com", "Password99!", true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                base() + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken = resp.getBody().accessToken();
        tenantId   = UUID.fromString((String) jwtService.verify(ownerToken).getClaim("tenant"));
        ownerUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE tenant_id = ? AND role = 'owner'",
                UUID.class, tenantId);

        // Seed product hierarchy used by piece-related COVERED tests
        storeId    = UUID.randomUUID();
        productId  = UUID.randomUUID();
        variantId  = UUID.randomUUID();
        locationId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'cov-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-CVG', 'Coverage Widget', 'active')",
                    productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-CVG', 'Default', 'CVG-001')",
                    variantId, tenantId, productId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default) " +
                    "VALUES (?, ?, 'CVG Warehouse', 'warehouse', false)",
                    locationId, tenantId);
    }

    @BeforeEach
    void cleanPerTest() {
        jdbc.update("DELETE FROM audit_log               WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events            WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations             WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces                  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments               WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items             WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders                  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM receipts                WHERE tenant_id = ?", tenantId);
    }

    @AfterAll
    void teardown() {
        jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM products WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stores   WHERE tenant_id = ?", tenantId);
    }

    // ── Coverage audit ────────────────────────────────────────────────────────

    /**
     * Fails the build when a new GET /api/ endpoint is added without being
     * placed in COVERED (test in this class) or EXEMPT (written reason above).
     */
    @Test
    void coverageAudit_allGetEndpointsAreCoveredOrExempt() {
        Set<String> discovered = discoverApiGetPatterns();

        Set<String> allowlisted = new HashSet<>();
        allowlisted.addAll(COVERED);
        allowlisted.addAll(EXEMPT.keySet());

        Set<String> uncovered = discovered.stream()
                .filter(p -> !allowlisted.contains(p))
                .collect(Collectors.toSet());

        assertThat(uncovered)
                .as("New GET endpoints must be added to COVERED (with a @Test here) or EXEMPT " +
                    "(with a reason in the EXEMPT map). Uncovered: %s", uncovered)
                .isEmpty();
    }

    private Set<String> discoverApiGetPatterns() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> {
                    var methods = e.getKey().getMethodsCondition().getMethods();
                    return methods.contains(RequestMethod.GET);
                })
                .flatMap(e -> e.getKey().getPatternValues().stream())
                .filter(p -> p.startsWith("/api/"))
                .collect(Collectors.toSet());
    }

    // ── COVERED tests ─────────────────────────────────────────────────────────

    @Test
    void users_listReturnsOwnUser() {
        ResponseEntity<List> resp = get("/api/v1/users", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void auditLog_returnsSeededEntry() {
        jdbc.update("INSERT INTO audit_log (tenant_id, actor_user_id, action) " +
                    "VALUES (?, ?, 'user_login')", tenantId, ownerUserId);

        ResponseEntity<Map> resp = get("/api/v1/audit-log", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) resp.getBody().get("items");
        assertThat(items).isNotEmpty();
    }

    @Test
    void shipmentsUnlinked_returnsSeededRow() {
        jdbc.update("INSERT INTO unlinked_bosta_deliveries " +
                    "(tenant_id, tracking_number, bosta_state_code, bosta_order_type) " +
                    "VALUES (?, 'TN-CVG-001', 45, 'normal')", tenantId);

        ResponseEntity<List> resp = get("/api/v1/shipments/unlinked", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void returnsPending_returnsSeededPiece() {
        String pieceId = UlidGenerator.generate();
        jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                "        'return_pending_inspection'::piece_status)",
                pieceId, tenantId, variantId, "PC-" + pieceId, pieceId);

        ResponseEntity<List> resp = get("/api/v1/returns/pending", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void returnsNeverReceived_returnsSeededShipment() {
        UUID orderId      = UUID.randomUUID();
        UUID orderItemId  = UUID.randomUUID();
        UUID shipmentId   = UUID.randomUUID();
        String pieceId    = UlidGenerator.generate();

        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold) " +
                    "VALUES (?, ?, ?, 'EXT-CVG-NR', 'new'::order_status, false)",
                    orderId, tenantId, storeId);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                    "VALUES (?, ?, ?, ?, 1)", orderItemId, tenantId, orderId, variantId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                    "        'available'::piece_status)",
                    pieceId, tenantId, variantId, "PC-" + pieceId, pieceId);
        jdbc.update("INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, 'packed'::allocation_status)",
                    tenantId, orderItemId, pieceId);
        jdbc.update("INSERT INTO shipments " +
                    "(id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, returned_at) " +
                    "VALUES (?, ?, ?, 'TN-NEVER-REC', 'returned'::shipment_internal_state, 'forward', " +
                    "        now() - interval '1 hour')",
                    shipmentId, tenantId, orderId);

        // windowDays=0 → returned_at < now() — matches any past returned_at
        ResponseEntity<List> resp = get("/api/v1/returns/never-received?windowDays=0", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void returnsSessions_returnsSeededSession() {
        UUID receiptId = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, kind, status, reference) " +
                    "VALUES (?, ?, 'returns', 'open', 'TN-CVG-SESS')",
                    receiptId, tenantId);

        ResponseEntity<List> resp = get("/api/v1/returns/sessions", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void returnsSessionPieces_returnsSessionMapWithWaybill() {
        UUID receiptId = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, kind, status, reference) " +
                    "VALUES (?, ?, 'returns', 'open', 'TN-CVG-SESS-P')",
                    receiptId, tenantId);

        ResponseEntity<Map> resp = get("/api/v1/returns/sessions/" + receiptId + "/pieces", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("waybillNumber")).isEqualTo("TN-CVG-SESS-P");
    }

    @Test
    void ordersDailyCounts_returnsNonEmptyArray() {
        // generate_series guarantees at least 30 data-points regardless of seeded orders
        ResponseEntity<List> resp = get("/api/v1/orders/daily-counts", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void lookup_returnsSeededPiece() {
        String pieceId = UlidGenerator.generate();
        jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                "        'available'::piece_status)",
                pieceId, tenantId, variantId, "PC-" + pieceId, pieceId);

        ResponseEntity<Map> resp = get("/api/v1/lookup?q=PC-" + pieceId, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("type")).isEqualTo("piece");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> get(String path, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        return (ResponseEntity<T>) rest.exchange(
                base() + path, HttpMethod.GET, new HttpEntity<>(headers), type);
    }
}
