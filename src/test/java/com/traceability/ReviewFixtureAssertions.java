package com.traceability;

import com.traceability.review.ReviewTenantSeeder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review mode S5 — the whole-fixture assertions, shared by ReviewTenantTest.o4 (seeded over the
 * postgres test datasource) and ReviewTenantRlsTest.o4b (seeded over app_user + RLS, as in prod).
 * Read with a postgres JdbcTemplate: these check what was written, not who can see it.
 */
final class ReviewFixtureAssertions {

    private ReviewFixtureAssertions() {}

    static void assertWholeFixture(JdbcTemplate jdbc, UUID t) {
        assertThat(jdbc.queryForObject("SELECT status::text FROM stores WHERE tenant_id = ? AND shop_domain = ?",
            String.class, t, ReviewTenantSeeder.PLACEHOLDER_SHOP)).isEqualTo("disconnected");
        assertThat(count(jdbc, "products", t)).isEqualTo(5);
        assertThat(count(jdbc, "variants", t)).isEqualTo(15);
        assertThat(jdbc.queryForList("SELECT image_url FROM products WHERE tenant_id = ?", String.class, t))
            .allSatisfy(u -> assertThat(u).matches("^https?://[^/]+/assets/review/[a-z-]+\\.webp$"));
        assertThat(jdbc.queryForList("SELECT external_id FROM variants WHERE tenant_id = ?", String.class, t))
            .allSatisfy(e -> assertThat(e).startsWith("review-fixture:variant:"));

        assertThat(count(jdbc, "pieces", t)).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ? AND event_type = 'received' " +
            "AND actor_user_id IS NOT NULL", Integer.class, t)).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT last_value FROM piece_counters WHERE tenant_id = ?", Long.class, t))
            .isGreaterThanOrEqualTo(45L);

        assertThat(count(jdbc, "orders", t)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders o WHERE o.tenant_id = ? AND (SELECT COUNT(*) FROM shipments s " +
            "WHERE s.order_id = o.id AND s.shipment_leg = 'forward' AND s.tracking_number ~ '^777[0-9]{10}$') = 1",
            Integer.class, t)).as("every order: exactly one simulated forward leg").isEqualTo(13);

        Map<String, String> statusByNumber = new HashMap<>();
        jdbc.query("SELECT o.number, o.status::text, o.on_hold, s.internal_state::text FROM orders o " +
            "JOIN shipments s ON s.order_id = o.id AND s.shipment_leg = 'forward' WHERE o.tenant_id = ?",
            rs -> { statusByNumber.put(rs.getString(1), rs.getString(2) + "/" + rs.getString(4) + (rs.getBoolean(3) ? "/hold" : "")); }, t);
        assertThat(statusByNumber).containsEntry("#R1001", "packed/created")
                                  .containsEntry("#R1002", "packed/created")
                                  .containsEntry("#R1003", "new/created")
                                  .containsEntry("#R1004", "packed/with_courier")
                                  .containsEntry("#R1005", "packed/delivered")
                                  .containsEntry("#R1006", "packed/delivered")
                                  .containsEntry("#R1007", "new/created/hold");
        for (int n = 1008; n <= 1013; n++) assertThat(statusByNumber).containsEntry("#R" + n, "new/created");

        assertThat(jdbc.queryForList("SELECT o.number FROM pack_print_batch_items bi JOIN orders o ON o.id = bi.order_id " +
            "WHERE bi.tenant_id = ?", String.class, t)).containsExactlyInAnyOrder("#R1001", "#R1002", "#R1003");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pickups WHERE tenant_id = ? AND session_status = 'closed'",
            Integer.class, t)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE tenant_id = ?", String.class, t))
            .isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pieces WHERE tenant_id = ? AND status = 'delivered'", Integer.class, t))
            .isEqualTo(2);
    }

    static int count(JdbcTemplate jdbc, String table, UUID tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }
}
