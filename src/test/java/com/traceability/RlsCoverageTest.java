package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
            "/api/v1/returns/sessions",
            "/api/v1/returns/sessions/{sessionId}",
            "/api/v1/returns/analytics",
            "/api/v1/orders/daily-counts",
            "/api/v1/lookup",
            "/api/v1/fulfill/gather",
            "/api/v1/catalog",
            "/api/v1/locations/shopify-junk-report",
            "/api/v1/shopify/inventory/reconcile",
            "/api/v1/stock-takes/sessions/{sessionId}/reconciliation",
            "/api/v1/stock-takes/sessions",
            "/api/v1/stock-takes/sessions/{sessionId}",
            "/api/v1/stock-takes/summary",
            "/api/v1/transfers",
            "/api/v1/transfers/{transferId}",
            "/api/v1/me",
            "/api/v1/exceptions/count",
            "/api/v1/inventory/status-totals",
            "/api/v1/inventory/valuation",
            "/api/v1/orders/funnel",
            "/api/v1/orders/summary",
            "/api/v1/inventory/throughput",
            "/api/v1/activity/recent",
            "/api/v1/exchanges",
            "/api/v1/overview/trends",
            "/api/v1/overview/top-skus",
            "/api/v1/inventory/stock",
            "/api/v1/inventory/variants/{variantId}/breakdown",
            "/api/v1/inventory/breakdown",
            "/api/v1/inventory/pieces",
            "/api/v1/inventory/movements"
    );

    // ── Patterns consciously excluded, with reasons ────────────────────────────

    private static final Map<String, String> EXEMPT = Map.ofEntries(
            entry("/api/v1/health",
                    "no DB query — returns static UP status"),
            entry("/api/v1/orders",
                    "uses tx.execute(); empty valid for new tenant; covered by FulfillTest"),
            entry("/api/v1/orders/{orderId}",
                    "single-entity GET; uses tx.execute(); covered by FulfillTest"),
            entry("/api/v1/orders/{orderId}/timeline",
                    "single-entity GET; uses tx.execute(); RLS-covered by " +
                    "OrderStatusListDetailParityTest's real app_user same-tenant positive " +
                    "control + cross-tenant negative control (stronger than this class's " +
                    "own BYPASSRLS pattern for a genuinely RLS-sensitive read)"),
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
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

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
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'cov-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-CVG', 'Coverage Widget', 'active')",
                    productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-CVG', 'Default', 'CVG-001')",
                    variantId, tenantId, productId);
        // Reuse the fulfillment location signup already seeded (AuthRepository.
        // createTenantWithOwner sets is_fulfillment=true on it) rather than inserting a
        // second one — V61 enforces exactly one is_fulfillment=true location per tenant.
        locationId = jdbc.queryForObject(
                "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true LIMIT 1",
                UUID.class, tenantId);
    }

    @BeforeEach
    void cleanPerTest() {
        jdbc.update("DELETE FROM audit_log               WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        // return_session_items/return_sessions before pieces — FK references pieces(id),
        // a leftover row here would abort the "DELETE FROM pieces" below.
        jdbc.update("DELETE FROM return_session_items    WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_sessions         WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events            WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations             WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces                  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments               WHERE tenant_id = ?", tenantId);
        // exchanges.outbound_order_id FKs to orders — delete exchanges first.
        jdbc.update("DELETE FROM exchanges               WHERE tenant_id = ?", tenantId);
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
    void me_returnsOwnIdentity() {
        ResponseEntity<Map> resp = get("/api/v1/me", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("name")).isEqualTo("cov_owner");
        assertThat(resp.getBody().get("email")).isEqualTo("cov@test.com");
        assertThat(resp.getBody().get("role")).isEqualTo("owner");
    }

    @Test
    void exceptionsCount_returnsSeededCount() {
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, on_hold, hold_reason) " +
            "VALUES (?, ?, 'EXT-CVG-EXC-COUNT', '#CVG-EXC-COUNT', 'new'::order_status, " +
            "    'cod', now(), true, 'Blocked customer')",
            tenantId, storeId);

        ResponseEntity<Map> resp = get("/api/v1/exceptions/count", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("count")).intValue()).isGreaterThanOrEqualTo(1);
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
    void exchanges_returnsSeededNeedsMappingRow() {
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, raw) " +
                    "VALUES (?, 'TN-CVG-EXC-001', 'needs_mapping', '{}'::jsonb)", tenantId);

        ResponseEntity<List> resp = get("/api/v1/exchanges?status=needs_mapping", List.class);
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

        // {items, total} — not a bare list (FR-Overview §1.2: the Overview dashboard's
        // awaiting-inspection tile reads .total, never page .items.length). FR-24:
        // items is always [] now (listPending() retired with the old three-tab UI —
        // only countPending()/.total survives, since Overview depends on it); the old
        // items-non-empty assertion no longer applies.
        ResponseEntity<Map> resp = get("/api/v1/returns/pending", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("total")).longValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void inventoryStatusTotals_returnsSeededCount() {
        String pieceId = UlidGenerator.generate();
        jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                "        'available'::piece_status)",
                pieceId, tenantId, variantId, "PC-" + pieceId, pieceId);

        ResponseEntity<Map> resp = get("/api/v1/inventory/status-totals", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) resp.getBody().get("statusCounts");
        assertThat(((Number) counts.get("available")).longValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void inventoryValuation_reflectsSeededCostedVariant() {
        jdbc.update("UPDATE variants SET unit_cost = 10.00 WHERE id = ?", variantId);
        String pieceId = UlidGenerator.generate();
        jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                "        'available'::piece_status)",
                pieceId, tenantId, variantId, "PC-" + pieceId, pieceId);

        ResponseEntity<Map> resp = get("/api/v1/inventory/valuation", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("variantsCosted")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(new java.math.BigDecimal(resp.getBody().get("inventoryValue").toString()))
                .isEqualByComparingTo("10.00");

        jdbc.update("UPDATE variants SET unit_cost = NULL WHERE id = ?", variantId);
    }

    @Test
    void ordersFunnel_reflectsSeededOrderToday() {
        jdbc.update(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
                "    payment_method, placed_at, on_hold) " +
                "VALUES (?, ?, 'EXT-CVG-FUNNEL', '#CVG-FUNNEL', 'new'::order_status, " +
                "    'cod', now(), false)",
                tenantId, storeId);

        ResponseEntity<Map> resp = get("/api/v1/orders/funnel", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("newCount")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ordersSummary_reflectsSeededOrders() {
        jdbc.update(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, on_hold) " +
            "VALUES (?, ?, 'EXT-CVG-SUMMARY', '#CVG-SUMMARY', 'new'::order_status, " +
            "    'cod', now(), false)",
            tenantId, storeId);

        ResponseEntity<Map> resp = get("/api/v1/orders/summary", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("total")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) resp.getBody().get("processing")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void inventoryThroughput_returnsNonEmptyArray() {
        // generate_series guarantees 30 data-points regardless of seeded events
        ResponseEntity<List> resp = get("/api/v1/inventory/throughput", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void activityRecent_returnsSeededFinalizedReceipt() {
        UUID receiptId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO receipts (id, tenant_id, kind, status, reference, received_by, " +
                "    location_id, finalized_at) " +
                "VALUES (?, ?, 'inbound', 'finalized', 'REC-CVG-ACT', ?, ?, now())",
                receiptId, tenantId, ownerUserId, locationId);
        jdbc.update(
                "INSERT INTO receipt_lines (id, tenant_id, receipt_id, variant_id, quantity) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, 5)",
                tenantId, receiptId, variantId);

        ResponseEntity<List> resp = get("/api/v1/activity/recent", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();

        jdbc.update("DELETE FROM receipt_lines WHERE receipt_id = ?", receiptId);
        jdbc.update("DELETE FROM receipts WHERE id = ?", receiptId);
    }

    @Test
    void returnsAnalytics_reflectsSeededNeverReceivedShipment() {
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
        // never_received_window_days default is 3 — set to 0 so this seeded shipment
        // (returned 1 hour ago) counts as "expected but not scanned" immediately.
        jdbc.update("UPDATE tenants SET never_received_window_days = 0 WHERE id = ?", tenantId);

        // FR-24: /returns/never-received (raw HTTP endpoint) was retired with the old
        // three-tab UI — neverReceived() is now wrapped, unmodified, inside analytics'
        // expectedNotScannedCount.
        ResponseEntity<Map> resp = get("/api/v1/returns/analytics", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("expectedNotScannedCount")).intValue())
            .isGreaterThanOrEqualTo(1);

        jdbc.update("UPDATE tenants SET never_received_window_days = 3 WHERE id = ?", tenantId);
    }

    @Test
    void returnsSessions_returnsSeededSession() {
        UUID sessionId = UUID.randomUUID();
        jdbc.update("INSERT INTO return_sessions (id, tenant_id, status, opened_by) " +
                    "VALUES (?, ?, 'open', ?)",
                    sessionId, tenantId, ownerUserId);

        // {items, total} — not a bare list (same convention as /returns/pending).
        ResponseEntity<Map> resp = get("/api/v1/returns/sessions", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) resp.getBody().get("items");
        assertThat(items).isNotEmpty();
        assertThat(((Number) resp.getBody().get("total")).longValue()).isGreaterThanOrEqualTo(1);

        jdbc.update("DELETE FROM return_sessions WHERE id = ?", sessionId);
    }

    @Test
    void returnsSessionDetail_returnsSeededSessionWithItems() {
        UUID sessionId = UUID.randomUUID();
        String pieceId = UlidGenerator.generate();
        jdbc.update("INSERT INTO return_sessions (id, tenant_id, status, opened_by) " +
                    "VALUES (?, ?, 'open', ?)",
                    sessionId, tenantId, ownerUserId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                    "        'return_pending_inspection'::piece_status)",
                    pieceId, tenantId, variantId, "PC-" + pieceId, pieceId);
        jdbc.update("INSERT INTO return_session_items " +
                    "(id, tenant_id, session_id, piece_id, scanned_by, scan_source) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, ?, 'barcode')",
                    tenantId, sessionId, pieceId, ownerUserId);

        ResponseEntity<Map> resp = get("/api/v1/returns/sessions/" + sessionId, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("open");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("piece_id")).isEqualTo(pieceId);

        jdbc.update("DELETE FROM return_session_items WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM return_sessions WHERE id = ?", sessionId);
        jdbc.update("DELETE FROM pieces WHERE id = ?", pieceId);
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

    @Test
    void fulfillGather_returnsSeededDemand() {
        UUID orderId     = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, on_hold, placed_at) " +
                    "VALUES (?, ?, ?, 'EXT-CVG-GATHER', '#CVG-GATHER', 'ready_to_pick'::order_status, false, now())",
                    orderId, tenantId, storeId);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                    "VALUES (?, ?, ?, ?, 3)", orderItemId, tenantId, orderId, variantId);
        // PICKABLE_ORDERS_FILTER (2026-07-28): non-self-pickup orders now require a
        // forward shipment in 'created' state to be gatherable/queueable at all.
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', 'created'::shipment_internal_state, 'forward')",
                    tenantId, orderId);

        ResponseEntity<Map> resp = get("/api/v1/fulfill/gather", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getBody().get("rows");
        assertThat(rows).isNotEmpty();
    }

    @Test
    void catalog_committedAndAvailable_reflectSeededOrder() {
        // Positive control: PHASE 0 narrowed committed to orders.status='new'; the
        // committed-over-counting fix additionally requires the SAME pickable
        // shipment-gate the Fulfill queue uses (FulfillService.PICKABLE_SHIPMENT_GATE) —
        // self-pickup, or the latest forward shipment at internal_state='created'. This
        // order clears the gate via a 'created' forward shipment. A piece at the
        // (is_fulfillment=true) location contributes to on_hand.
        UUID orderId     = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        String pieceId   = UlidGenerator.generate();

        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold) " +
                    "VALUES (?, ?, ?, 'EXT-CVG-CATALOG', 'new'::order_status, false)",
                    orderId, tenantId, storeId);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', 'TN-CVG-CATALOG', 'created'::shipment_internal_state, 'forward')",
                    tenantId, orderId);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                    "VALUES (?, ?, ?, ?, 2)", orderItemId, tenantId, orderId, variantId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                    "        'available'::piece_status, ?)",
                    pieceId, tenantId, variantId, "PC-" + pieceId, pieceId, locationId);

        ResponseEntity<Map> resp = get("/api/v1/catalog", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) resp.getBody().get("products");
        Map<String, Object> variant = products.stream()
            .flatMap(p -> ((List<Map<String, Object>>) p.get("variants")).stream())
            .filter(v -> variantId.toString().equals(v.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded variant not found in catalog response"));

        assertThat(((Number) variant.get("committed")).longValue())
            .as("committed must reflect the seeded in-window order")
            .isEqualTo(2L);
        assertThat(((Number) variant.get("available")).longValue())
            .as("available = on_hand(1) - committed(2)")
            .isEqualTo(-1L);

        // PHASE 0: a piece already scanned/allocated (allocation status='active') to a
        // second 'new' order's line must net that line's contribution to zero — proves
        // VariantStockService's allocation-netting under the real HTTP/RLS path, not just
        // an in-process unit test. Total committed stays 2 (order1's un-allocated line
        // only) — order2's fully-covered line adds nothing.
        UUID order2Id     = UUID.randomUUID();
        UUID order2ItemId = UUID.randomUUID();
        String piece2Id   = UlidGenerator.generate();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold) " +
                    "VALUES (?, ?, ?, 'EXT-CVG-CATALOG-SCANNED', 'new'::order_status, false)",
                    order2Id, tenantId, storeId);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                    "VALUES (?, ?, ?, ?, 1)", order2ItemId, tenantId, order2Id, variantId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                    "        'reserved'::piece_status, ?)",
                    piece2Id, tenantId, variantId, "PC-" + piece2Id, piece2Id, locationId);
        jdbc.update("INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, 'active'::allocation_status)",
                    tenantId, order2ItemId, piece2Id);
        try {
            ResponseEntity<Map> scannedResp = get("/api/v1/catalog", Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scannedProducts =
                (List<Map<String, Object>>) scannedResp.getBody().get("products");
            Map<String, Object> scannedVariant = scannedProducts.stream()
                .flatMap(p -> ((List<Map<String, Object>>) p.get("variants")).stream())
                .filter(v -> variantId.toString().equals(v.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("seeded variant not found in catalog response"));
            assertThat(((Number) scannedVariant.get("committed")).longValue())
                .as("order2's active allocation must net its line to 0; total committed unchanged at 2")
                .isEqualTo(2L);
        } finally {
            jdbc.update("DELETE FROM allocations WHERE order_item_id = ?", order2ItemId);
            jdbc.update("DELETE FROM pieces WHERE id = ?", piece2Id);
            jdbc.update("DELETE FROM order_items WHERE id = ?", order2ItemId);
            jdbc.update("DELETE FROM orders WHERE id = ?", order2Id);
        }

        // imageUrl null case: the shared P-CVG fixture (productId) never sets image_url —
        // must surface as JSON null, not an empty string or a missing key.
        Map<String, Object> productRow = products.stream()
            .filter(p -> productId.toString().equals(p.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded product not found in catalog response"));
        assertThat(productRow.containsKey("imageUrl"))
            .as("imageUrl key must be present in the product row")
            .isTrue();
        assertThat(productRow.get("imageUrl"))
            .as("product with no Shopify image must surface imageUrl=null")
            .isNull();

        // imageUrl non-null case: a separate product with image_url set must round-trip verbatim.
        UUID imgProductId = UUID.randomUUID();
        String expectedImageUrl = "https://cdn.shopify.com/s/files/1/0000/0001/products/cvg-image.jpg";
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, image_url) " +
                    "VALUES (?, ?, ?, 'P-CVG-IMG', 'Coverage Widget With Image', 'active', ?)",
                    imgProductId, tenantId, storeId, expectedImageUrl);
        try {
            ResponseEntity<Map> imgResp = get("/api/v1/catalog", Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> imgProducts = (List<Map<String, Object>>) imgResp.getBody().get("products");
            Map<String, Object> imgProductRow = imgProducts.stream()
                .filter(p -> imgProductId.toString().equals(p.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("image-seeded product not found in catalog response"));
            assertThat(imgProductRow.get("imageUrl"))
                .as("product with a Shopify image must round-trip the exact CDN URL")
                .isEqualTo(expectedImageUrl);
        } finally {
            jdbc.update("DELETE FROM products WHERE id = ?", imgProductId);
        }
    }

    @Test
    void catalog_committed_excludesNewOrderWhoseForwardShipmentAlreadyDelivered() {
        // The committed-over-counting fix, under real RLS/HTTP: an order stuck at
        // orders.status='new' whose forward shipment already progressed past 'created'
        // (here: 'delivered') is not open backlog — Traced's own pack flow never ran for
        // it, but the shipment shows it's already out the door. Proven on live pilot data
        // (The Snouts, 2026-08): 40 of 45 units counted for one variant were exactly this
        // shape. Must contribute 0, not its order_item quantity.
        UUID deliveredOrderId     = UUID.randomUUID();
        UUID deliveredOrderItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold) " +
                    "VALUES (?, ?, ?, 'EXT-CVG-DELIVERED-STUCK', 'new'::order_status, false)",
                    deliveredOrderId, tenantId, storeId);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', 'TN-CVG-DELIVERED-STUCK', 'delivered'::shipment_internal_state, 'forward')",
                    tenantId, deliveredOrderId);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                    "VALUES (?, ?, ?, ?, 40)", deliveredOrderItemId, tenantId, deliveredOrderId, variantId);

        try {
            ResponseEntity<Map> resp = get("/api/v1/catalog", Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> products = (List<Map<String, Object>>) resp.getBody().get("products");
            Map<String, Object> variant = products.stream()
                .flatMap(p -> ((List<Map<String, Object>>) p.get("variants")).stream())
                .filter(v -> variantId.toString().equals(v.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("seeded variant not found in catalog response"));
            assertThat(((Number) variant.get("committed")).longValue())
                .as("a 'new' order whose forward shipment already shows 'delivered' must not contribute — " +
                    "it isn't sitting pre-shipment, regardless of orders.status")
                .isEqualTo(0L);
        } finally {
            jdbc.update("DELETE FROM order_items WHERE id = ?", deliveredOrderItemId);
            jdbc.update("DELETE FROM shipments WHERE order_id = ?", deliveredOrderId);
            jdbc.update("DELETE FROM orders WHERE id = ?", deliveredOrderId);
        }
    }

    @Test
    void shopifyInventoryReconcile_returnsComputedReportForSeededVariant() {
        // Reuse the shared is_fulfillment=true fixture location (V61: one per tenant) —
        // link it to Shopify for the duration of this test, then unlink it again.
        jdbc.update(
            "UPDATE locations SET shopify_location_id = 'gid://shopify/Location/cvg-traced', " +
            "    shopify_sync_status = 'linked' WHERE id = ?",
            locationId);

        when(tokenProvider.getValidToken(any())).thenReturn("test-token");
        when(shopifyGateway.resolveInventoryItemId(any(), any(), any()))
            .thenReturn("gid://shopify/InventoryItem/cvg");
        when(shopifyGateway.fetchAvailableQuantities(any(), any(), any(), any()))
            .thenReturn(List.of());

        try {
            ResponseEntity<Map> resp = get("/api/v1/shopify/inventory/reconcile", Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getBody().get("rows");
            assertThat(rows).isNotEmpty();
            assertThat(rows).anyMatch(r -> variantId.toString().equals(r.get("variantId")));
        } finally {
            jdbc.update(
                "UPDATE locations SET shopify_location_id = NULL, shopify_sync_status = 'unsynced' " +
                "WHERE id = ?",
                locationId);
        }
    }

    @Test
    void locationsShopifyJunkReport_returnsSeededNonFulfillmentLinkedLocation() {
        UUID junkId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, " +
            "    shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'CVG Junk Showroom', 'warehouse', false, false, " +
            "    'gid://shopify/Location/junk-cvg', 'linked')",
            junkId, tenantId);

        ResponseEntity<List> resp = get("/api/v1/locations/shopify-junk-report", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();

        jdbc.update("DELETE FROM locations WHERE id = ?", junkId);
    }

    @Test
    void stockTakeReconciliation_returnsComputedReportForSeededSession() {
        UUID sessionId = UUID.randomUUID();
        String pieceId = UlidGenerator.generate();

        jdbc.update(
            "INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by) " +
            "VALUES (?, ?, 'open', 'all', ?, ?)",
            sessionId, tenantId, locationId, ownerUserId);
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        'available'::piece_status, ?)",
            pieceId, tenantId, variantId, "PC-" + pieceId, pieceId, locationId);
        jdbc.update(
            "INSERT INTO stock_take_expected (tenant_id, session_id, piece_id, variant_id, status_at_open) " +
            "VALUES (?, ?, ?, ?, 'available')",
            tenantId, sessionId, pieceId, variantId);

        ResponseEntity<Map> resp = get(
            "/api/v1/stock-takes/sessions/" + sessionId + "/reconciliation", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rollup = (List<Map<String, Object>>) resp.getBody().get("variantRollup");
        assertThat(rollup).anyMatch(r -> variantId.toString().equals(r.get("variantId")));

        @SuppressWarnings("unchecked")
        Map<String, Object> buckets = (Map<String, Object>) resp.getBody().get("buckets");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> uncounted = (List<Map<String, Object>>) buckets.get("on_shelf_uncounted");
        assertThat(uncounted).anyMatch(p -> pieceId.equals(p.get("pieceId")));

        jdbc.update("DELETE FROM stock_take_expected WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM stock_take_sessions WHERE id = ?", sessionId);
        jdbc.update("DELETE FROM pieces WHERE id = ?", pieceId);
    }

    @Test
    void stockTakeSummary_reflectsSeededSession() {
        UUID sessionId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by) " +
            "VALUES (?, ?, 'open', 'all', ?, ?)",
            sessionId, tenantId, locationId, ownerUserId);

        ResponseEntity<Map> resp = get("/api/v1/stock-takes/summary", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("openSessions")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) resp.getBody().get("countsThisMonth")).longValue()).isGreaterThanOrEqualTo(1);

        jdbc.update("DELETE FROM stock_take_sessions WHERE id = ?", sessionId);
    }

    @Test
    void stockTakeSessionsList_returnsSeededSession() {
        UUID sessionId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by) " +
            "VALUES (?, ?, 'open', 'all', ?, ?)",
            sessionId, tenantId, locationId, ownerUserId);

        ResponseEntity<List> resp = get("/api/v1/stock-takes/sessions", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = resp.getBody();
        assertThat(body).anyMatch(r -> sessionId.toString().equals(r.get("sessionId")));

        jdbc.update("DELETE FROM stock_take_sessions WHERE id = ?", sessionId);
    }

    @Test
    void stockTakeSessionDetail_returnsSeededSessionWithSyncStatus() {
        UUID sessionId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by) " +
            "VALUES (?, ?, 'finalized', 'all', ?, ?)",
            sessionId, tenantId, locationId, ownerUserId);
        jdbc.update(
            "INSERT INTO stock_take_shopify_syncs (id, tenant_id, session_id, status, payload) " +
            "VALUES (gen_random_uuid(), ?, ?, 'pushed', " +
            "        ('{\"locationId\":\"' || ? || '\",\"deltas\":{\"' || ? || '\":2}}')::jsonb)",
            tenantId, sessionId, locationId, variantId);

        ResponseEntity<Map> resp = get(
            "/api/v1/stock-takes/sessions/" + sessionId, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("sessionId")).isEqualTo(sessionId.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> shopifySync = (Map<String, Object>) resp.getBody().get("shopifySync");
        assertThat(shopifySync).isNotNull();
        assertThat(shopifySync.get("status")).isEqualTo("pushed");
        assertThat(shopifySync.get("referenceDocumentUri")).isEqualTo("traced://stock-take/" + sessionId);

        jdbc.update("DELETE FROM stock_take_shopify_syncs WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM stock_take_sessions WHERE id = ?", sessionId);
    }

    @Test
    void overviewTrends_reflectsSeededOrderToday() {
        jdbc.update(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
                "    payment_method, placed_at, on_hold) " +
                "VALUES (?, ?, 'EXT-CVG-OVTRENDS', '#CVG-OVTRENDS', 'new'::order_status, " +
                "    'cod', now(), false)",
                tenantId, storeId);

        ResponseEntity<List> resp = get("/api/v1/overview/trends", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = resp.getBody();
        Map<String, Object> orders = body.stream()
            .filter(m -> "orders".equals(m.get("metric")))
            .findFirst().orElseThrow(() -> new AssertionError("orders metric not found"));
        assertThat(((Number) orders.get("today")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void overviewTopSkus_reflectsSeededOrderItem() {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, " +
                "    payment_method, placed_at, on_hold) " +
                "VALUES (?, ?, ?, 'EXT-CVG-OVSKUS', '#CVG-OVSKUS', 'new'::order_status, " +
                "    'cod', now(), false)",
                orderId, tenantId, storeId);
        jdbc.update(
                "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, 3)",
                tenantId, orderId, variantId);

        ResponseEntity<List> resp = get("/api/v1/overview/top-skus", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();

        jdbc.update("DELETE FROM order_items WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
    }

    @Test
    void transfersList_returnsSeededOpenTransfer() {
        UUID destId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'CVG Transfer Dest', 'showroom', false, false)",
            destId, tenantId);
        jdbc.update(
            "INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, status, created_by) " +
            "VALUES (?, ?, 'showroom', ?, 'open', ?)",
            transferId, tenantId, destId, ownerUserId);

        ResponseEntity<List> resp = get("/api/v1/transfers", List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();

        jdbc.update("DELETE FROM transfers WHERE id = ?", transferId);
        jdbc.update("DELETE FROM locations WHERE id = ?", destId);
    }

    @Test
    void transferDetail_returnsSeededTransferWithLines() {
        UUID destId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'CVG Transfer Dest 2', 'showroom', false, false)",
            destId, tenantId);
        jdbc.update(
            "INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, status, created_by) " +
            "VALUES (?, ?, 'showroom', ?, 'open', ?)",
            transferId, tenantId, destId, ownerUserId);

        ResponseEntity<Map> resp = get("/api/v1/transfers/" + transferId, Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("id")).isEqualTo(transferId.toString());
        assertThat(resp.getBody()).containsKey("lines");

        jdbc.update("DELETE FROM transfers WHERE id = ?", transferId);
        jdbc.update("DELETE FROM locations WHERE id = ?", destId);
    }

    // ── Phase A: Inventory backend endpoints ────────────────────────────────────

    @Test
    void inventoryStock_reflectsSeededPiece_andNullsCommittedWhenLocationScoped() {
        String pieceId = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                    "        'available'::piece_status, ?)",
                    pieceId, tenantId, variantId, "PC-" + pieceId, pieceId, locationId);

        // Tenant-wide: committed present (0, no open orders), on_hand/available = 1.
        ResponseEntity<Map> resp = get("/api/v1/inventory/stock", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getBody().get("items");
        Map<String, Object> variant = items.stream()
            .flatMap(p -> ((List<Map<String, Object>>) p.get("variants")).stream())
            .filter(v -> variantId.toString().equals(v.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded variant not found in /inventory/stock"));
        assertThat(((Number) variant.get("onHand")).longValue()).isEqualTo(1L);
        assertThat(((Number) variant.get("committed")).longValue()).isEqualTo(0L);
        assertThat(((Number) variant.get("available")).longValue()).isEqualTo(1L);
        assertThat(variant.get("shopifySync")).isEqualTo("none");

        // Location-scoped: committed must be absent (null), not a fake 0.
        ResponseEntity<Map> locResp = get("/api/v1/inventory/stock?locationId=" + locationId, Map.class);
        assertThat(locResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locItems = (List<Map<String, Object>>) locResp.getBody().get("items");
        Map<String, Object> locVariant = locItems.stream()
            .flatMap(p -> ((List<Map<String, Object>>) p.get("variants")).stream())
            .filter(v -> variantId.toString().equals(v.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded variant not found in location-scoped /inventory/stock"));
        assertThat(locVariant.get("committed"))
            .as("committed must be null when locationId is set — it has no location")
            .isNull();
        assertThat(((Number) locVariant.get("onHand")).longValue()).isEqualTo(1L);
        assertThat(((Number) locVariant.get("available")).longValue()).isEqualTo(1L);
    }

    @Test
    void inventoryVariantBreakdown_reflectsSeededPieceAcrossLocations() {
        String pieceId = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                    "        'available'::piece_status, ?)",
                    pieceId, tenantId, variantId, "PC-" + pieceId, pieceId, locationId);

        ResponseEntity<Map> resp = get("/api/v1/inventory/variants/" + variantId + "/breakdown", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("onHand")).longValue()).isEqualTo(1L);
        assertThat(((Number) resp.getBody().get("committed")).longValue()).isEqualTo(0L);
        assertThat(((Number) resp.getBody().get("available")).longValue()).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locations = (List<Map<String, Object>>) resp.getBody().get("locations");
        Map<String, Object> loc = locations.stream()
            .filter(l -> locationId.toString().equals(l.get("locationId")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("fixture location not in breakdown"));
        assertThat(((Number) loc.get("onHand")).longValue()).isEqualTo(1L);
        assertThat(((Number) loc.get("available")).longValue()).isEqualTo(1L);

        assertThat(resp.getBody()).containsKey("recentMovements");
    }

    @Test
    void inventoryBreakdown_reflectsSeededPhaseCounts() {
        String availId  = UlidGenerator.generate();
        String courierId = UlidGenerator.generate();
        String damagedId = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status)",
                    availId, tenantId, variantId, "PC-" + availId, availId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'with_courier'::piece_status)",
                    courierId, tenantId, variantId, "PC-" + courierId, courierId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'damaged'::piece_status)",
                    damagedId, tenantId, variantId, "PC-" + damagedId, damagedId);

        ResponseEntity<Map> resp = get("/api/v1/inventory/breakdown", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("inWarehouse")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(((Number) resp.getBody().get("onTheWayOut")).longValue()).isGreaterThanOrEqualTo(1L);
        assertThat(((Number) resp.getBody().get("problem")).longValue()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void inventoryPieces_returnsSeededPieceWithOrderAndTracking() {
        UUID orderId = UUID.randomUUID();
        String pieceId = UlidGenerator.generate();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, on_hold) " +
                    "VALUES (?, ?, ?, 'EXT-CVG-PIECES', '#CVG-PIECES', 'new'::order_status, false)",
                    orderId, tenantId, storeId);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', 'TN-CVG-PIECES', 'created'::shipment_internal_state, 'forward')",
                    tenantId, orderId);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, " +
                    "    current_location_id, current_order_id) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                    "        'reserved'::piece_status, ?, ?)",
                    pieceId, tenantId, variantId, "PC-" + pieceId, pieceId, locationId, orderId);

        ResponseEntity<Map> resp = get("/api/v1/inventory/pieces?status=reserved", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getBody().get("items");
        Map<String, Object> row = items.stream()
            .filter(r -> pieceId.equals(r.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("seeded piece not found in /inventory/pieces"));
        assertThat(row.get("orderNumber")).isEqualTo("#CVG-PIECES");
        assertThat(row.get("trackingNumber")).isEqualTo("TN-CVG-PIECES");
        assertThat(row.get("locationName")).isNotNull();

        jdbc.update("DELETE FROM shipments WHERE order_id = ?", orderId);
    }

    @Test
    void inventoryMovements_unionsAdjustmentAndStockTakeRows() {
        UUID sessionId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        jdbc.update("INSERT INTO shopify_inventory_adjustments " +
                    "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, status) " +
                    "VALUES (?, ?, ?, ?, 5, 'receiving_session', 'CVG-RCPT-1', 'applied')",
                    tenantId, batchId, variantId, locationId);

        jdbc.update("INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by, finalized_by, finalized_at) " +
                    "VALUES (?, ?, 'finalized', 'all', ?, ?, ?, now())",
                    sessionId, tenantId, locationId, ownerUserId, ownerUserId);
        jdbc.update("INSERT INTO stock_take_shopify_syncs (id, tenant_id, session_id, status, payload) " +
                    "VALUES (gen_random_uuid(), ?, ?, 'pushed', " +
                    "        ('{\"locationId\":\"' || ? || '\",\"deltas\":{\"' || ? || '\":3}}')::jsonb)",
                    tenantId, sessionId, locationId, variantId);

        try {
            ResponseEntity<Map> resp = get("/api/v1/inventory/movements", Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getBody().get("items");

            Map<String, Object> adjRow = items.stream()
                .filter(r -> "adjustment".equals(r.get("source")) && variantId.toString().equals(r.get("variantId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("seeded adjustment row not found in /inventory/movements"));
            assertThat(((Number) adjRow.get("delta")).intValue()).isEqualTo(5);
            assertThat(adjRow.get("syncStatus")).isEqualTo("synced");

            Map<String, Object> stRow = items.stream()
                .filter(r -> "stock_take".equals(r.get("source")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("seeded stock_take row not found in /inventory/movements"));
            assertThat(((Number) stRow.get("delta")).intValue())
                .as("stock-take delta must be the NEGATED sum of write-off magnitudes (a decrement)")
                .isEqualTo(-3);
            assertThat(stRow.get("syncStatus")).isEqualTo("synced");
            assertThat(stRow.get("variantId")).isNull();
        } finally {
            jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE batch_id = ?", batchId);
            jdbc.update("DELETE FROM stock_take_shopify_syncs WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM stock_take_sessions WHERE id = ?", sessionId);
        }
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
