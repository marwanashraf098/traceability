package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExchangeService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.ReturnService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.security.EncryptionService;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exchange reference S3b (2026-11-19) — an exchange whose leg was advanced at commit (Bosta already past pickup when it
 * was mapped) still finishes like any other: its old items come back in a Scan returns session that scanned the
 * exchange AWB, are graded, and the exchange is return_received. Meanwhile the replacement order is never pickable and
 * nothing moved but the leg (no piece / ledger event, no Shopify write).
 *
 * <pre>
 *   c1  multi-item, mapped by a person (map-items) while Bosta is at out_for_return → leg delivered at commit; both
 *       old items back on the AWB and graded → return_received
 *   c2  single-item, mapped while Bosta is at picked_up → leg with_courier at commit; the old item graded →
 *       return_received
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExchangeAdvanceAtCommitClosureTest {

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

    static final AtomicLong IDS = new AtomicLong(7_300_000_000L);

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean BostaGateway         bostaGateway;

    @Autowired JdbcTemplate      jdbc;
    @Autowired ObjectMapper      mapper;
    @Autowired EncryptionService encryption;
    @Autowired ExchangeService   exchanges;
    @Autowired FulfillService    fulfill;
    @Autowired ReturnService     returns;

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-test");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void c1_multiItem_advancedToDeliveredAtCommit_closesOnEveryInboundUnit() {
        Shop s = new Shop("c1");
        UUID original = s.order("#7801", s.beige, s.navy);
        String delivBeige = s.deliveredPiece(original, s.beige);
        String delivNavy = s.deliveredPiece(original, s.navy);
        String awb = s.exchange(original, 2, 2, 46, "Returned to business", "out_for_return");
        int eventsBefore = pieceEvents(s);

        Map<String, Object> r = s.as(() -> exchanges.mapItems(s.exchangeId(awb),
            List.of(row(s.black, 2, "two black")), List.of(row(s.beige, 1, null), row(s.navy, 1, null)), s.owner));
        UUID exc = UUID.fromString((String) r.get("orderId"));

        assertThat(legState(awb)).as("advanced at commit").isEqualTo("delivered");
        assertThat(jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, exc)).isEqualTo("new");
        assertThat(s.as(() -> fulfill.getQueue()).stream().map(q -> q.get("id"))).doesNotContain(exc);
        assertThat(pieceEvents(s)).as("no piece moved").isEqualTo(eventsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", Integer.class, s.tenant)).isZero();
        verifyNoInteractions(shopifyGateway);
        assertThat(status(awb)).isEqualTo("matched");

        UUID session = s.returnSession(awb);
        received(s, delivBeige, original, session);
        s.as(() -> { returns.restock(delivBeige, s.location, s.owner); return null; });
        assertThat(status(awb)).as("one of two back").isEqualTo("matched");
        received(s, delivNavy, original, session);
        s.as(() -> { returns.markDamaged(delivNavy, "torn seam", s.owner); return null; });
        assertThat(status(awb)).isEqualTo("return_received");
    }

    @Test
    void c2_singleItem_advancedToWithCourierAtCommit_closesOnItsPiece() {
        Shop s = new Shop("c2");
        UUID original = s.order("#7802", s.beige);
        String piece = s.deliveredPiece(original, s.beige);
        String awb = s.exchange(original, 1, 1, 21, "Picked up from business", "picked_up");

        Map<String, Object> r = s.as(() -> exchanges.map(s.exchangeId(awb), s.navy, s.beige));
        UUID exc = UUID.fromString((String) r.get("orderId"));
        assertThat(legState(awb)).isEqualTo("with_courier");
        assertThat(s.as(() -> fulfill.getQueue()).stream().map(q -> q.get("id"))).doesNotContain(exc);
        assertThat(status(awb)).isEqualTo("matched");

        jdbc.update("UPDATE pieces SET status = 'return_pending_inspection' WHERE id = ?", piece);
        s.as(() -> { returns.restock(piece, s.location, s.owner); return null; });
        assertThat(status(awb)).isEqualTo("return_received");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    String legState(String awb) {
        return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE tracking_number = ? AND shipment_leg = 'forward'",
            String.class, awb);
    }

    String status(String awb) {
        return jdbc.queryForObject("SELECT status FROM exchanges WHERE tracking_number = ?", String.class, awb);
    }

    int pieceEvents(Shop s) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, s.tenant);
    }

    static ExchangeService.ItemRow row(UUID variant, int qty, String hint) {
        return new ExchangeService.ItemRow(variant, qty, hint);
    }

    /** The piece arrives in {@code session}: return_pending_inspection + its return_received event (as Scan returns writes it). */
    void received(Shop s, String piece, UUID order, UUID session) {
        jdbc.update("UPDATE pieces SET status = 'return_pending_inspection' WHERE id = ?", piece);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, metadata) " +
            "VALUES (?, ?, 'return_received', ?, 'delivered', 'return_pending_inspection', ?::jsonb)",
            s.tenant, piece, order, "{\"via\":\"return_scan\",\"session_id\":\"" + session + "\"}");
    }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        final UUID location = UUID.randomUUID();
        final UUID beige, navy, black;

        Shop(String name) {
            String shop = "eac-" + name + "-" + tenant.toString().substring(0, 8) + ".myshopify.com";
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "EAC-" + name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
                owner, tenant, "o-" + owner + "@eac.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes) " +
                "VALUES (?, ?, ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
                store, tenant, shop);
            // No Shopify location link: a restock records its claim as skipped (no call) — the test watches for none.
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true)",
                location, tenant);
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                "VALUES (?, 'bosta', ?, 'h', 'active')", tenant, encryption.encrypt("key-" + tenant));
            UUID product = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, ?, 'Wide Leg', 'active')", product, tenant, store, "gid://shopify/Product/" + IDS.incrementAndGet());
            beige = variant(product, "Beige / 1");
            navy = variant(product, "Navy / 1");
            black = variant(product, "Black / 2");
        }

        private UUID variant(UUID product, String title) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
                v, tenant, product, "gid://shopify/ProductVariant/" + IDS.incrementAndGet(), title, "WL-" + v.toString().substring(0, 6));
            return v;
        }

        UUID order(String number, UUID... variants) {
            UUID o = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                "VALUES (?, ?, ?, ?, 'delivered'::order_status, now() - interval '5 days') RETURNING id", UUID.class,
                tenant, store, "gid://shopify/Order/" + IDS.incrementAndGet(), number);
            for (UUID v : variants) {
                jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", tenant, o, v);
            }
            return o;
        }

        String deliveredPiece(UUID order, UUID variant) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, current_order_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered', ?, ?)",
                id, tenant, variant, "PC-" + id, id, location, order);
            UUID line = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ? AND variant_id = ?", UUID.class, order, variant);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", tenant, line, id);
            return id;
        }

        /** A dashboard exchange (needs_mapping, linked to {@code original}) Bosta already has at {@code code} / {@code milestone}. */
        String exchange(UUID original, int out, int in, int code, String value, String milestone) {
            String tn = String.valueOf(IDS.incrementAndGet());
            ObjectNode raw = mapper.createObjectNode();
            raw.put("trackingNumber", tn);
            raw.putNull("businessReference");
            raw.putObject("type").put("code", 30).put("value", "Exchange");
            raw.putObject("state").put("code", code).put("value", value);
            raw.putObject("receiver").put("fullName", "Mona Adel").put("phone", "+201005557788");
            raw.putObject("specs").putObject("packageDetails").put("itemsCount", out).put("description", "out");
            raw.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", in).put("description", "in");
            ArrayNode tl = raw.putArray("timeline");
            tl.addObject().put("value", "new").put("done", true).put("date", "2026-11-12T10:00:00.000Z");
            tl.addObject().put("value", milestone).put("done", true).put("date", "2026-11-13T10:00:00.000Z");
            jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_description, inbound_description, " +
                "raw, matched_order_id, match_method, matched_at) VALUES (?, ?, 'needs_mapping', 'out', 'in', ?::jsonb, ?, 'manual', now())",
                tenant, tn, raw.toString(), original);
            return tn;
        }

        UUID returnSession(String awb) {
            UUID id = jdbc.queryForObject("INSERT INTO return_sessions (tenant_id, status, opened_by, closed_by, closed_at) " +
                "VALUES (?, 'closed', ?, ?, now()) RETURNING id", UUID.class, tenant, owner, owner);
            jdbc.update("INSERT INTO return_session_shipments (tenant_id, session_id, awb) VALUES (?, ?, ?)", tenant, id, awb);
            return id;
        }

        UUID exchangeId(String tn) {
            return jdbc.queryForObject("SELECT id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", UUID.class, tenant, tn);
        }

        <T> T as(java.util.concurrent.Callable<T> body) { return TenantContext.runAs(tenant, body); }
    }
}
