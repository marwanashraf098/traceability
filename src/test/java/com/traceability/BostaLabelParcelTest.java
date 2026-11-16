package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug 2 / 3 (approved 2026-11-16) — Scan returns reads the item lines Bosta printed on the parcel's label
 * (BostaLabelLines), never itemsCount. Realistic BROEK fixtures, bare numeric AWBs.
 *
 *   l1  BRK-47431 / 7139234160 (type 25, label: 1 Crewneck): bostaLabel {total 1}; the Crewneck unit first and onLabel,
 *       Gray / Raw Denim after it, onLabel false
 *   l2  BRK-47247 / 4226201959 (type 25, label 3 jeans; Bosta's specs.itemsCount says 1): 6 pieces could come back, the
 *       3 on the label are marked onLabel and listed first; total 3
 *   l3  a returned-to-sender forward leg: its plugin-format specs label is read (what was sent)
 *   l4  free-text label → no bostaLabel key, no onLabel key, rows in their old order (today's behaviour)
 *   m1  marking past the label (a second unit on a 1-item label, a unit not on it) is NEVER blocked server-side —
 *       the confirmation is the screen's
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaLabelParcelTest {

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
    @Autowired ReturnSessionService sessions;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;

    static final String CRP_47431 = "x1 Boxy Oversized Crewneck – Burgundy - M - SKU: BRK-RGC-2627-BRG-M";
    static final String CRP_47247 = "x1 Washed Black Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BLK-RF-002 // " +
        "x1 Beige Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-BGE-RF-002 // x1 White Relaxed Fit Denim Jeans - 32 - SKU: BRK-JEANS-WHT-RF-002";

    UUID tenant, store, location, owner;
    final Map<String, UUID> variants = new java.util.LinkedHashMap<>();

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); store = UUID.randomUUID(); location = UUID.randomUUID(); owner = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'BROEK label')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'broek-label.myshopify.com', 'disconnected')",
            store, tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'x', 'owner', true)",
            owner, tenant, "owner-" + owner + "@test.local");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, tenant);
        for (String sku : List.of("BRK-RGC-2627-BRG-M", "BROEK-Gray-RFD-1", "BROEK-Raw-Denim-RFD-1", "BRK-JEANS-BLK-RF-002",
                "BRK-JEANS-BGE-RF-002", "BRK-JEANS-WHT-RF-002", "BROEK-DARK-WASHED-RFD-001")) {
            UUID p = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'active') RETURNING id",
                UUID.class, tenant, store, "P-" + sku, "Product " + sku);
            variants.put(sku, jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'M', ?) RETURNING id",
                UUID.class, tenant, p, "V-" + sku, sku));
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        jdbc.update("UPDATE return_sessions SET status = 'abandoned' WHERE tenant_id = ? AND status = 'open'", tenant);
    }

    @Test
    @SuppressWarnings("unchecked")
    void l1_untrackedParcel_labelRowsFirst_othersOffLabel() {
        UUID o = order("BRK-47431-EG", "5373943286");
        for (String sku : List.of("BRK-RGC-2627-BRG-M", "BROEK-Gray-RFD-1", "BROEK-Raw-Denim-RFD-1")) line(o, sku);
        crp(o, "7139234160", CRP_47431, 1);
        Map<String, Object> parcel = parcel("7139234160");
        assertThat((Map<String, Object>) parcel.get("bostaLabel")).containsEntry("total", 1);
        List<Map<String, Object>> units = (List<Map<String, Object>>) parcel.get("untrackedUnits");
        assertThat(units).extracting(u -> u.get("sku") + ":" + u.get("onLabel"))
            .containsExactly("BRK-RGC-2627-BRG-M:true", "BROEK-Gray-RFD-1:false", "BROEK-Raw-Denim-RFD-1:false");
        assertThat(units.get(0)).containsEntry("labelQuantity", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void l2_trackedParcel_upToSix_threeOnTheLabel_firstAndMarked() {
        UUID o = order("BRK-47247-EG", "1151717987");
        for (String sku : List.of("BROEK-DARK-WASHED-RFD-001", "BRK-JEANS-BLK-RF-002", "BRK-JEANS-BGE-RF-002",
                "BRK-JEANS-WHT-RF-002", "BROEK-Gray-RFD-1", "BROEK-Raw-Denim-RFD-1")) {
            deliveredPiece(o, line(o, sku), sku);
        }
        crp(o, "4226201959", CRP_47247, 1);   // Bosta's own itemsCount says 1 — ignored
        Map<String, Object> parcel = parcel("4226201959");
        assertThat((Map<String, Object>) parcel.get("bostaLabel")).containsEntry("total", 3);
        List<Map<String, Object>> expected = (List<Map<String, Object>>) parcel.get("expectedPieces");
        assertThat(expected).hasSize(6);
        assertThat(expected.subList(0, 3)).extracting(e -> e.get("sku")).containsExactlyInAnyOrder(
            "BRK-JEANS-BLK-RF-002", "BRK-JEANS-BGE-RF-002", "BRK-JEANS-WHT-RF-002");
        assertThat(expected.subList(0, 3)).allSatisfy(e -> assertThat(e).containsEntry("onLabel", true));
        assertThat(expected.subList(3, 6)).allSatisfy(e -> assertThat(e).containsEntry("onLabel", false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void l3_returnedToSenderForwardLeg_readsTheSentLabel() {
        UUID o = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, 'BRK-47500-EG', 'new', now()) RETURNING id", UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID());
        line(o, "BRK-RGC-2627-BRG-M");
        line(o, "BROEK-Gray-RFD-1");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', '8350112290', 'returned', 'forward', ?::jsonb)", tenant, o,
            "{\"type\":{\"code\":10,\"value\":\"Return to Origin\"},\"state\":{\"value\":\"Returned to business\"}," +
            "\"specs\":{\"packageDetails\":{\"itemsCount\":2,\"description\":" +
            "\"Boxy Oversized Crewneck – Burgundy - M x 1 (BRK-RGC-2627-BRG-M)\\nGray Relaxed Fit Denim Jeans - 30 x 1 (BROEK-Gray-RFD-1)\"}}}");
        Map<String, Object> parcel = parcel("8350112290");
        assertThat((Map<String, Object>) parcel.get("bostaLabel")).containsEntry("total", 2);
        assertThat((List<Map<String, Object>>) parcel.get("untrackedUnits")).allSatisfy(u -> assertThat(u).containsEntry("onLabel", true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void l4_freeTextLabel_todaysBehaviour_noLabelKeys() {
        UUID o = order("BRK-47501-EG", "5373943299");
        line(o, "BROEK-Gray-RFD-1");
        line(o, "BRK-RGC-2627-BRG-M");
        crp(o, "7139234199", "Clothes", 2);
        Map<String, Object> parcel = parcel("7139234199");
        assertThat(parcel).doesNotContainKey("bostaLabel");
        List<Map<String, Object>> units = (List<Map<String, Object>>) parcel.get("untrackedUnits");
        assertThat(units).hasSize(2).allSatisfy(u -> assertThat(u).doesNotContainKey("onLabel"));
    }

    @Test
    void m1_pastTheLabel_neverBlockedServerSide() {
        UUID o = order("BRK-47502-EG", "5373943298");
        UUID crew = line(o, "BRK-RGC-2627-BRG-M");
        UUID gray = line(o, "BROEK-Gray-RFD-1");
        UUID leg = crp(o, "7139234198", CRP_47431, 1);
        UUID session = TenantContext.runAs(tenant, () -> sessions.createSession(null, owner));
        TenantContext.runAs(tenant, () -> sessions.scan(session, "7139234198", location, owner));
        TenantContext.runAs(tenant, () -> sessions.unitArrived(session, leg, crew, 1, "sellable", owner));
        TenantContext.runAs(tenant, () -> sessions.unitArrived(session, leg, gray, 1, "damaged", owner));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM untracked_unit_intakes WHERE shipment_id = ? AND undone_at IS NULL",
            Integer.class, leg)).isEqualTo(2);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private UUID order(String number, String fwdTn) {
        UUID o = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new', now()) RETURNING id", UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', now() - interval '1 day')", tenant, o, fwdTn);
        return o;
    }

    private UUID line(UUID order, String sku) {
        return jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenant, order, variants.get(sku));
    }

    private void deliveredPiece(UUID order, UUID item, String sku) {
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered', ?, now())",
            piece, tenant, variants.get(sku), "PC-" + piece, piece, order);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", tenant, item, piece);
    }

    private UUID crp(UUID order, String tn, String description, int itemsCount) {
        return jdbc.queryForObject("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'returned', 'return', ?::jsonb) RETURNING id", UUID.class, tenant, order, tn,
            "{\"type\":{\"code\":25,\"value\":\"Customer Return Pickup\"},\"specs\":{\"packageDetails\":{\"itemsCount\":1}}," +
            "\"returnSpecs\":{\"packageDetails\":{\"itemsCount\":" + itemsCount + ",\"description\":\"" + description + "\"}}}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parcel(String awb) {
        UUID session = TenantContext.runAs(tenant, () -> sessions.createSession(null, owner));
        TenantContext.runAs(tenant, () -> sessions.scan(session, awb, location, owner));
        Map<String, Object> detail = TenantContext.runAs(tenant, () -> sessions.getSession(session));
        TenantContext.runAs(tenant, () -> sessions.abandon(session, owner));
        return ((List<Map<String, Object>>) detail.get("parcels")).stream()
            .filter(p -> awb.equals(p.get("awb"))).findFirst().orElseThrow();
    }
}
