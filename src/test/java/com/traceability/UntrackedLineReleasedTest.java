package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.*;
import com.traceability.portal.PortalService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4 (approved 2026-11-16, BRK-47431-EG) — an order line is untracked when it has no TRACKING allocation: a live one,
 * or a released one whose piece really left with the order (ShipmentLinkService.trackingAllocationSql). A line picked
 * and then unpicked (released, the piece never left) is untracked again.
 *
 * BRK-47431 shape: delivered by Bosta (5373943286), never packed in Traced; Crewneck and Raw Denim have no allocation,
 * Gray had one that was released 11-08 (the piece is back on the shelf). Return leg 7139234160 (type 25).
 *
 *   p1  portal BEFORE / AFTER: before D4 Gray was offered neither as a piece (none delivered) nor as an untracked
 *       unit — the customer could not return it; now it is an untracked line (orderItemId, tracked:false, 1 unit)
 *   p2  golden: a line whose piece REALLY left with the order (with_courier / delivered on the forward leg), came back
 *       and was restocked (allocation released) stays tracked — never offered as an untracked unit
 *   u1  Scan returns: the return leg's parcel lists one unit row per untracked line — Crewneck, Raw Denim AND Gray
 *       (before D4: 2 rows, "0 of 2")
 *   x1  cross-tenant: tenant B's portal can't find tenant A's order; B's own line of the same shape follows the same rule
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UntrackedLineReleasedTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired PortalService portal;
    @Autowired ReturnSessionService sessions;
    @Autowired InventoryLedger ledger;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;

    record Tenant(UUID id, UUID store, UUID location, UUID owner, String slug, UUID crewneck, UUID gray, UUID rawDenim) {}
    record Order(UUID id, String number, UUID forwardLeg, UUID crewneckLine, UUID grayLine, UUID rawDenimLine) {}

    Tenant a, b;

    @BeforeAll
    void setup() {
        a = tenant("BROEK d4", "broek-d4");
        b = tenant("Other d4", "other-d4");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    @SuppressWarnings("unchecked")
    void p1_portal_releasedNeverShippedLine_isOfferedAsUntracked() {
        Order o = brk47431(a, "BRK-47431-EG", "01011112222", "5373943286");
        List<Map<String, Object>> lines = lookupLines(a, "BRK-47431-EG", "01011112222");
        assertThat(lines).filteredOn(l -> o.grayLine().toString().equals(l.get("orderItemId"))).singleElement()
            .satisfies(l -> assertThat(l).containsEntry("tracked", false).containsEntry("returnableQuantity", 1)
                .containsEntry("variantId", a.gray().toString()));
        assertThat(lines).extracting(l -> l.get("orderItemId"))
            .contains(o.crewneckLine().toString(), o.rawDenimLine().toString(), o.grayLine().toString());
    }

    @Test
    void p2_golden_pieceReallyLeftWithTheOrder_lineStaysTracked() {
        Order o = brk47431(a, "BRK-47432-EG", "01011113333", "5373943287");
        UUID line = o.rawDenimLine();
        String piece = pieceOf(a, a.rawDenim());
        TenantContext.set(a.id());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'active')", a.id(), line, piece);
        move(piece, PieceStatus.AVAILABLE, PieceStatus.RESERVED, "scan", o, null);
        move(piece, PieceStatus.RESERVED, PieceStatus.PACKED, "pack", o, null);
        move(piece, PieceStatus.PACKED, PieceStatus.AWAITING_PICKUP, "tracking_linked", o, o.forwardLeg());
        move(piece, PieceStatus.AWAITING_PICKUP, PieceStatus.WITH_COURIER, "handed_to_courier", o, o.forwardLeg());
        move(piece, PieceStatus.WITH_COURIER, PieceStatus.DELIVERED, "courier_update", o, o.forwardLeg());
        move(piece, PieceStatus.DELIVERED, PieceStatus.RETURN_PENDING_INSPECTION, "return_received", o, o.forwardLeg());
        move(piece, PieceStatus.RETURN_PENDING_INSPECTION, PieceStatus.AVAILABLE, "restocked", o, null);
        jdbc.update("UPDATE allocations SET status = 'released' WHERE piece_id = ?", piece);
        TenantContext.clear();

        assertThat(lookupLines(a, "BRK-47432-EG", "01011113333")).extracting(l -> l.get("orderItemId"))
            .doesNotContain(line.toString())
            .contains(o.grayLine().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void u1_scanReturns_returnLegListsEveryUntrackedLine_grayIncluded() {
        Order o = brk47431(a, "BRK-47433-EG", "01011114444", "5373943288");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', '7139234160', 'returned', 'return', ?::jsonb)", a.id(), o.id(),
            "{\"type\":{\"code\":25,\"value\":\"Customer Return Pickup\"},\"returnSpecs\":{\"packageDetails\":{\"itemsCount\":1," +
            "\"description\":\"x1 Boxy Oversized Crewneck – Burgundy - M - SKU: BRK-RGC-2627-BRG-M\"}}}");
        UUID session = TenantContext.runAs(a.id(), () -> sessions.createSession(null, a.owner()));
        TenantContext.runAs(a.id(), () -> sessions.scan(session, "7139234160", a.location(), a.owner()));
        Map<String, Object> detail = TenantContext.runAs(a.id(), () -> sessions.getSession(session));
        Map<String, Object> parcel = ((List<Map<String, Object>>) detail.get("parcels")).get(0);
        List<Map<String, Object>> units = (List<Map<String, Object>>) parcel.get("untrackedUnits");
        assertThat(units).extracting(u -> u.get("orderItemId")).containsExactlyInAnyOrder(
            o.crewneckLine().toString(), o.rawDenimLine().toString(), o.grayLine().toString());
    }

    @Test
    void x1_crossTenant_portalScopedToItsTenant_sameRuleForEach() {
        brk47431(a, "BRK-47434-EG", "01011115555", "5373943289");
        Order ob = brk47431(b, "BRK-47434-EG", "01011116666", "5373943290");
        assertThat(portal.lookup(b.slug(), "BRK-47434-EG", "01011115555").map(PortalService.LookupResult::outcome))
            .as("A's phone on B's portal finds nothing of A")
            .isNotEqualTo(java.util.Optional.of(PortalService.Outcome.SUCCESS));
        assertThat(lookupLines(b, "BRK-47434-EG", "01011116666")).extracting(l -> l.get("orderItemId"))
            .contains(ob.grayLine().toString());
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    /** BRK-47431: delivered by Bosta, never packed; Gray picked on 11-08 and released (the piece is available again). */
    private Order brk47431(Tenant t, String number, String phone, String fwdTn) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name, " +
            "  customer_phone, pii_source) VALUES (?, ?, ?, ?, 'new'::order_status, 'cod'::order_payment_method, now(), 'Mona', ?, " +
            "  'bosta') RETURNING id", UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        UUID leg = jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, " +
            "shipment_leg, delivered_at) VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', now() - interval '1 day') RETURNING id",
            UUID.class, t.id(), order, fwdTn);
        UUID crew = line(t, order, t.crewneck()), gray = line(t, order, t.gray()), raw = line(t, order, t.rawDenim());
        Order o = new Order(order, number, leg, crew, gray, raw);
        String piece = pieceOf(t, t.gray());
        TenantContext.set(t.id());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'active')", t.id(), gray, piece);
        move(piece, PieceStatus.AVAILABLE, PieceStatus.RESERVED, "scan", o, null);
        move(piece, PieceStatus.RESERVED, PieceStatus.AVAILABLE, "unscan", o, null);
        jdbc.update("UPDATE allocations SET status = 'released' WHERE piece_id = ?", piece);
        TenantContext.clear();
        return o;
    }

    private UUID line(Tenant t, UUID order, UUID variant) {
        return jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, 1, '{\"current_quantity\": 1}'::jsonb) RETURNING id", UUID.class, t.id(), order, variant);
    }

    private void move(String piece, PieceStatus from, PieceStatus to, String event, Order o, UUID shipment) {
        ledger.transition(piece, from, to, event, null, new TransitionContext(o.id(), shipment, null,
            to == PieceStatus.AVAILABLE ? null : o.id(), null));
    }

    private String pieceOf(Tenant t, UUID variant) {
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?, now())",
            piece, t.id(), variant, "PC-" + piece, piece, t.location());
        return piece;
    }

    private Tenant tenant(String name, String slug) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", id, name, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'x', 'owner', true)",
            owner, id, "owner-" + owner + "@test.local");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        return new Tenant(id, store, location, owner, slug,
            variant(id, store, "Boxy Oversized Crewneck – Burgundy", "M", "BRK-RGC-2627-BRG-M"),
            variant(id, store, "Gray Relaxed Fit Denim Jeans", "30", "BROEK-Gray-RFD-1"),
            variant(id, store, "Raw Denim Relaxed Fit Jeans", "30", "BROEK-Raw-Denim-RFD-1"));
    }

    private UUID variant(UUID tenant, UUID store, String product, String title, String sku) {
        UUID p = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'active') " +
            "RETURNING id", UUID.class, tenant, store, "P-" + UUID.randomUUID(), product);
        return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenant, p, "V-" + UUID.randomUUID(), title, sku);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lookupLines(Tenant t, String number, String phone) {
        PortalService.LookupResult res = portal.lookup(t.slug(), number, phone).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return (List<Map<String, Object>>) res.body().get("lines");
    }
}
