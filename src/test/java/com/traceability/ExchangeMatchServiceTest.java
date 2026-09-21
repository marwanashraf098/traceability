package com.traceability;

import com.traceability.inventory.ExchangeMatchService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FR-EXCHANGE Step 3 Part B — ExchangeMatchService.attemptMatch() (Option A fuzzy
 * matcher for the INBOUND/old-item leg).
 *
 * (m1) exactly one phone-matched delivered piece → auto-link (status='matched',
 *      matched_order_id set, match_method='phone', matched_at set).
 * (m2) two phone-matched candidates on different variants, description narrows to
 *      neither → status='needs_confirmation', no link.
 * (m3) phone matches nothing → status='unmatched'.
 * (m4) status='needs_mapping' (pre Phase-2) → matcher is a no-op — proves it never
 *      races ExchangeService.map()'s claim.
 * (m5) description present and narrows two candidates down to exactly one → auto-link
 *      (proves the description-narrowing path, not just the trivial single-candidate one).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeMatchServiceTest {

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

    @Autowired ExchangeMatchService  matchSvc;
    @Autowired ShipmentLinkService   shipmentLinkSvc;
    @Autowired JdbcTemplate          jdbc;
    @MockBean  JobScheduler          jobScheduler;

    UUID tenantId, storeId, productId;

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        productId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EMS-Tenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'ems.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-EMS', 'Bucket Hat', 'active')", productId, tenantId, storeId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM variants WHERE product_id = ?", productId);
    }

    // ── (m1) single candidate → auto-link ─────────────────────────────────────

    @Test
    void m1_singleCandidate_autoLinks() {
        UUID redVariant = createVariant("Red Bucket Hat", "BKT-RED");
        UUID orderId = createOrder("01001111111", "returning");
        String piece = createPiece(redVariant, orderId, "delivered");
        allocate(orderId, redVariant, piece);

        UUID exchangeId = createExchange("EXM-M1", "mapped", null,
            "01001111111", "Red Bucket Hat");

        matchSvc.attemptMatch("EXM-M1");

        Map<String, Object> row = exchangeRow(exchangeId);
        assertThat(row.get("status")).isEqualTo("matched");
        assertThat(row.get("matched_order_id").toString()).isEqualTo(orderId.toString());
        assertThat(row.get("match_method")).isEqualTo("phone");
        assertThat(row.get("matched_at")).isNotNull();
    }

    // ── (m2) two candidates, description narrows to neither → needs_confirmation ──

    @Test
    void m2_twoCandidates_descriptionNarrowsToNeither_needsConfirmation() {
        UUID redVariant  = createVariant("Red Bucket Hat", "BKT-RED-2");
        UUID blueVariant = createVariant("Blue Bucket Hat", "BKT-BLUE-2");
        UUID order1 = createOrder("01002222222", "returning");
        UUID order2 = createOrder("01002222222", "returning");
        String piece1 = createPiece(redVariant, order1, "delivered");
        String piece2 = createPiece(blueVariant, order2, "delivered");
        allocate(order1, redVariant, piece1);
        allocate(order2, blueVariant, piece2);

        UUID exchangeId = createExchange("EXM-M2", "mapped", null,
            "01002222222", "Something completely unrelated");

        matchSvc.attemptMatch("EXM-M2");

        Map<String, Object> row = exchangeRow(exchangeId);
        assertThat(row.get("status")).isEqualTo("needs_confirmation");
        assertThat(row.get("matched_order_id")).isNull();
    }

    // ── (m3) zero candidates → unmatched ──────────────────────────────────────

    @Test
    void m3_zeroCandidates_unmatched() {
        UUID exchangeId = createExchange("EXM-M3", "mapped", null,
            "01009999999", "Nobody has this phone number");

        matchSvc.attemptMatch("EXM-M3");

        Map<String, Object> row = exchangeRow(exchangeId);
        assertThat(row.get("status")).isEqualTo("unmatched");
        assertThat(row.get("matched_order_id")).isNull();
    }

    // ── (m4) needs_mapping (pre Phase-2) → no-op, never races map()'s claim ────

    @Test
    void m4_prePhase2_needsMapping_matcherIsNoOp() {
        UUID redVariant = createVariant("Red Bucket Hat", "BKT-RED-4");
        UUID orderId = createOrder("01004444444", "returning");
        String piece = createPiece(redVariant, orderId, "delivered");
        allocate(orderId, redVariant, piece);

        UUID exchangeId = createExchange("EXM-M4", "needs_mapping", null,
            "01004444444", "Red Bucket Hat");

        matchSvc.attemptMatch("EXM-M4");

        Map<String, Object> row = exchangeRow(exchangeId);
        assertThat(row.get("status"))
            .as("matcher must never touch a needs_mapping exchange — that status belongs to ExchangeService.map()'s claim")
            .isEqualTo("needs_mapping");
        assertThat(row.get("matched_order_id")).isNull();
    }

    // ── (m5) description narrows two candidates down to exactly one → auto-link ──

    @Test
    void m5_twoCandidates_descriptionNarrowsToOne_autoLinks() {
        UUID redVariant  = createVariant("Red Bucket Hat", "BKT-RED-5");
        UUID blueVariant = createVariant("Blue Bucket Hat", "BKT-BLUE-5");
        UUID order1 = createOrder("01005555555", "returning");
        UUID order2 = createOrder("01005555555", "returning");
        String piece1 = createPiece(redVariant, order1, "delivered");
        String piece2 = createPiece(blueVariant, order2, "delivered");
        allocate(order1, redVariant, piece1);
        allocate(order2, blueVariant, piece2);

        UUID exchangeId = createExchange("EXM-M5", "mapped", null,
            "01005555555", "the blue bucket hat, size M");

        matchSvc.attemptMatch("EXM-M5");

        Map<String, Object> row = exchangeRow(exchangeId);
        assertThat(row.get("status")).isEqualTo("matched");
        assertThat(row.get("matched_order_id").toString()).isEqualTo(order2.toString());
    }

    // ── Part D: manual resolution actions ───────────────────────────────────────

    // ── (d1) search-attach on an unmatched exchange → matched, match_method='manual' ─

    @Test
    void d1_searchAttach_onUnmatched_setsMatchedManual() {
        UUID orderId = createOrder("01006666666", "returning");
        UUID exchangeId = createExchange("EXM-D1", "unmatched", null, "01000000000", null);

        Map<String, Object> result = matchSvc.searchAttach(exchangeId, orderId);

        assertThat(result.get("status")).isEqualTo("matched");
        Map<String, Object> row = exchangeRow(exchangeId);
        assertThat(row.get("status")).isEqualTo("matched");
        assertThat(row.get("matched_order_id").toString()).isEqualTo(orderId.toString());
        assertThat(row.get("match_method")).isEqualTo("manual");
        assertThat(row.get("matched_at")).isNotNull();
    }

    // ── (d2) accept-as-bare-return on needs_confirmation → bare_return, no order link ─

    @Test
    void d2_acceptAsBareReturn_onNeedsConfirmation_setsBareReturnNoOrderLink() {
        UUID exchangeId = createExchange("EXM-D2", "needs_confirmation", null, "01000000000", null);

        matchSvc.acceptAsBareReturn(exchangeId);

        Map<String, Object> row = exchangeRow(exchangeId);
        assertThat(row.get("status")).isEqualTo("bare_return");
        assertThat(row.get("matched_order_id"))
            .as("bare return never gets an order link — matching never gates shelf-return")
            .isNull();
    }

    // ── (d3) dismiss on unmatched → dismissed ─────────────────────────────────────

    @Test
    void d3_dismiss_onUnmatched_setsDismissed() {
        UUID exchangeId = createExchange("EXM-D3", "unmatched", null, "01000000000", null);

        matchSvc.dismiss(exchangeId);

        assertThat(exchangeRow(exchangeId).get("status")).isEqualTo("dismissed");
    }

    // ── (d4) claim guard: action against an already-matched exchange → 409 ────────

    @Test
    void d4_manualActionAgainstAlreadyMatchedExchange_conflicts_doesNotOverwrite() {
        UUID originalOrder = createOrder("01007777777", "returning");
        UUID otherOrder    = createOrder("01008888888", "returning");
        UUID exchangeId = createExchange("EXM-D4", "matched", null, "01000000000", null);
        jdbc.update(
            "UPDATE exchanges SET matched_order_id = ?, match_method = 'phone', matched_at = now() " +
            "WHERE id = ?", originalOrder, exchangeId);

        ResponseStatusException ex = catchThrowableOfType(
            () -> matchSvc.searchAttach(exchangeId, otherOrder), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> row = exchangeRow(exchangeId);
        assertThat(row.get("matched_order_id").toString())
            .as("a conflicting manual action must never overwrite an existing match")
            .isEqualTo(originalOrder.toString());
    }

    // ── (x) Cross-tenant: a matched exchange in tenant B never suppresses tenant A ──

    /**
     * hasActiveReturnLeg()'s exchange branch is tenant-scoped (AND tenant_id = ?) same
     * as its shipments branch. Tenant B's exchange row is deliberately given
     * matched_order_id equal to tenant A's real order id (bypassing the FK's tenant
     * consistency via a direct jdbc write, same technique Day13Test's k_ test uses) to
     * prove the tenant_id filter — not merely "the ids never happen to collide" — is
     * what keeps them apart. Same-tenant positive control alongside proves the predicate
     * still says true for the case it should.
     */
    @Test
    void x_matchedExchangeInTenantB_neverSuppressesOrLeaksIntoTenantA() {
        UUID tenantB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'EMS-TenantB')", tenantB);

        UUID orderA = createOrder("01009000000", "returning");

        // Tenant B's own exchange row, but matched_order_id sharing tenant A's order id.
        jdbc.update(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, matched_order_id, " +
            "    match_method, matched_at, raw) " +
            "VALUES (?, 'EXM-X-B', 'matched', ?, 'phone', now(), '{}'::jsonb)",
            tenantB, orderA);

        assertThat(shipmentLinkSvc.hasActiveReturnLeg(orderA, tenantId))
            .as("tenant B's matched exchange must never suppress tenant A's order, even sharing the same order id value")
            .isFalse();

        // Same-tenant positive control: a REAL tenant-A matched exchange for the same order.
        jdbc.update(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, matched_order_id, " +
            "    match_method, matched_at, raw) " +
            "VALUES (?, 'EXM-X-A', 'matched', ?, 'phone', now(), '{}'::jsonb)",
            tenantId, orderA);
        assertThat(shipmentLinkSvc.hasActiveReturnLeg(orderA, tenantId))
            .as("tenant A's own matched exchange must suppress as normal")
            .isTrue();

        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", tenantB);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantB);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private UUID createVariant(String title, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            id, tenantId, productId, "V-" + id, title, sku);
        return id;
    }

    private UUID createOrder(String phone, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, customer_phone, payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#EMS', ?::order_status, 'Buyer', ?, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-EMS-" + UUID.randomUUID(), status, phone);
    }

    private String createPiece(UUID variantId, UUID orderId, String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces " +
            "(id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, now())",
            id, tenantId, variantId, "PC-" + id, id, status, orderId);
        return id;
    }

    private void allocate(UUID orderId, UUID variantId, String pieceId) {
        UUID itemId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, ?, 1)", itemId, tenantId, orderId, variantId);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'packed')",
            tenantId, itemId, pieceId);
    }

    private UUID createExchange(String trackingNumber, String status, UUID outboundOrderId,
                                 String phone, String inboundDescription) {
        String raw = "{\"receiver\":{\"phone\":\"" + phone + "\"}," +
            "\"returnSpecs\":{\"packageDetails\":{\"description\":\"" + inboundDescription + "\"}}}";
        return jdbc.queryForObject(
            "INSERT INTO exchanges " +
            "(tenant_id, tracking_number, status, outbound_order_id, inbound_description, raw) " +
            "VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id",
            UUID.class, tenantId, trackingNumber, status, outboundOrderId, inboundDescription, raw);
    }

    private Map<String, Object> exchangeRow(UUID exchangeId) {
        return jdbc.queryForMap("SELECT * FROM exchanges WHERE id = ?", exchangeId);
    }
}
