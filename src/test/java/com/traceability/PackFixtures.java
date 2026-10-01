package com.traceability;

import com.traceability.inventory.UlidGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pick &amp; Pack S3 test fixtures (postgres connection, fixtures only): one tenant with a store,
 * a location, users, products, orders with forward / return legs, pieces.
 */
final class PackFixtures {

    private static final AtomicLong TRACKING = new AtomicLong(3_000_000_000L);

    final JdbcTemplate jdbc;
    final UUID tenant, store, location;

    PackFixtures(JdbcTemplate jdbc, String name) {
        this.jdbc = jdbc;
        this.tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, name);
        this.store = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')", store, tenant, "pk-" + store + ".myshopify.com");
        this.location = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'PackLoc')", location, tenant);
    }

    static String nextTracking() { return String.valueOf(TRACKING.incrementAndGet()); }

    UUID user(String name, String role) {
        return jdbc.queryForObject(
            "INSERT INTO users (tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, ?, 'x', ?::user_role) RETURNING id",
            UUID.class, tenant, name, name.toLowerCase() + "-" + UUID.randomUUID() + "@test.com", role);
    }

    UUID variant(String title, String sku, String imageUrl) {
        UUID product = jdbc.queryForObject(
            "INSERT INTO products (tenant_id, store_id, external_id, title, status, image_url) " +
            "VALUES (?, ?, ?, ?, 'active', ?) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Product/" + UUID.randomUUID(), title, imageUrl);
        return jdbc.queryForObject(
            "INSERT INTO variants (tenant_id, product_id, external_id, sku, title) VALUES (?, ?, ?, ?, 'M') RETURNING id",
            UUID.class, tenant, product, "gid://shopify/ProductVariant/" + UUID.randomUUID(), sku);
    }

    /** status 'new', placed now (inside the window) unless placedDaysAgo says otherwise. */
    UUID order(String number, int placedDaysAgo) {
        return orderWith(number, "new", placedDaysAgo, "EXT-" + UUID.randomUUID());
    }

    UUID orderWith(String number, String status, int placedDaysAgo, String externalId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, cod_amount, " +
            "                    payment_method, placed_at, created_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'Youssef Adel', 1250.00, 'cod', " +
            "        now() - (? * interval '1 day'), now() - (? * interval '1 day')) RETURNING id",
            UUID.class, tenant, store, externalId, number, status, placedDaysAgo, placedDaysAgo);
    }

    void item(UUID orderId, UUID variantId, int qty) {
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
            tenant, orderId, variantId, qty);
    }

    /** Forward leg in 'created'; returns its tracking number. */
    String forward(UUID orderId) { return leg(orderId, "forward", "created", null); }

    String leg(UUID orderId, String leg, String state, String rawJson) {
        String tn = nextTracking();
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, " +
                    "                       shipment_leg, raw) " +
                    "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, ?::jsonb)",
                    tenant, orderId, tn, state, leg, rawJson);
        return tn;
    }

    /** An available piece of the variant; returns its short code (what the label encodes). */
    String piece(UUID variantId, UUID actor) {
        String id = UlidGenerator.generate();
        String shortCode = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, " +
            "                    last_event_at, last_user_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, now(), ?)",
            id, tenant, variantId, "PC-" + id, shortCode, location, actor);
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, ?, NULL, 'available'::piece_status)",
            tenant, id, actor, location);
        return shortCode;
    }

    String pieceId(String shortCode) {
        return jdbc.queryForObject("SELECT id FROM pieces WHERE tenant_id = ? AND short_code = ?",
            String.class, tenant, shortCode);
    }

    void claim(UUID orderId, UUID user, int minutesAgo) {
        jdbc.update("UPDATE orders SET locked_by = ?, locked_at = now() - (? * interval '1 minute') WHERE id = ?",
            user, minutesAgo, orderId);
    }
}
