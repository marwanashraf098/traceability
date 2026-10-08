package com.traceability.analytics;

import com.traceability.privacy.CustomerRedaction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics slice 8 — the V149 SQL mapping functions give exactly what the Java tables in
 * AnalyticsMappings give, over the full s5 mapping test set plus edge cases; the generated columns
 * hold what the queries used to parse from raw; malformed payloads never break an INSERT; GDPR
 * redaction clears the customer key.
 */
@Testcontainers
class AnalyticsSqlParityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    // ── channel ─────────────────────────────────────────────────────────────

    static final String STATUS_URL = "https://broek-eg.com/123/orders/abc/authenticate?key=x";

    /** {source, referring site, landing site} — AnalyticsMappingsTest.channel_table plus edge cases. */
    static final String[][] CHANNEL_CASES = {
        {"shopify_draft_order", "https://instagram.com/", "/?utm_source=facebook"},
        {"web", "https://instagram.com/", "/?utm_source=facebook"},
        {"web", "https://l.instagram.com/", null},
        {"web", "android-app://com.instagram.android/", null},
        {"web", "https://m.facebook.com/", null},
        {"web", "https://l.facebook.com/l.php?u=x", null},
        {"web", "https://www.tiktok.com/", null},
        {"web", "https://www.google.com/", null},
        {"web", "https://www.google.com.eg/search?q=x", null},
        {"web", null, "/products/x?utm_source=facebook&utm_medium=paid"},
        {"web", null, "/?utm_source=fa"},
        {"web", null, "/?utm_source=fac"},
        {"web", null, "/?utm_source=faceb"},
        {"web", null, "/?utm_source=FB"},
        {"web", null, "/?utm_source=ig"},
        {"web", null, "/?utm_source=tiktok"},
        {"web", null, "/?utm_source=google"},
        {"web", null, "/?utm_source=jeans%20-%20summer%20collection%20%7C%206%2F23"},
        {"web", null, "/?utm_source=f"},
        {"web", "https://l.wl.co/abc", null},
        {"web", "https://broek-eg.com/collections/all", null},
        {"web", "https://www.broek-eg.com/", null},
        {"web", null, null},
        {"web", "", "/"},
        // edge cases
        {" Shopify_Draft_Order ", null, null},
        {"web", "HTTPS://WWW.INSTAGRAM.COM/p/x", null},
        {"web", "https://user@www.facebook.com:443/x", null},
        {"web", "​https://messenger.com/t", null},
        {"web", "https://fb.me/abc", null},
        {"web", "https://ads.fb.com/x", null},
        {"web", "https://vm.tiktok.com/x", null},
        {"web", "android-app://com.google.android.googlequicksearchbox/", null},
        {"web", "https://google/", null},
        {"web", "https://notgoogle.com/", null},
        {"web", "https://other-store.myshopify.com/cart", null},
        {"web", "https://checkout.shopify.com/x", null},
        {"web", null, "/?UTM_SOURCE=Instagram"},
        {"web", null, "/?a=1&utm_source=%66acebook#top"},
        {"web", null, "/?utm_source=face%ZZ"},
        {"web", null, "/?utm_source=%E2%9C%93"},
        {"web", null, "/?utm_source=+ig+"},
        {"web", null, "/?utm_source=tt"},
        {"web", null, "/?utm_source=googleads&utm_source=facebook"},
        {"web", "https:///", null},
        {"web", "   ", "/?utm_source="},
        {"pos", null, null},
    };

    @Test
    void channel_sqlEqualsJava_overTheWholeTable() {
        Set<String> own = AnalyticsMappings.ownHosts(STATUS_URL, null);
        String ownHost = own.iterator().next();
        for (String[] c : CHANNEL_CASES) {
            for (boolean hasSource : new boolean[] {true, false}) {
                String java = AnalyticsMappings.channel(c[0], hasSource, c[1], c[2], own);
                String sql = jdbc.queryForObject("SELECT analytics_channel(?, ?, ?, ?, ARRAY[?])", String.class,
                    c[0], hasSource, c[1], c[2], ownHost);
                assertThat(sql).as("channel %s has=%s", Arrays.toString(c), hasSource).isEqualTo(java);
            }
        }
    }

    @Test
    void channel_generatedColumnEqualsJava_fromTheOrderRaw() {
        UUID[] t = tenant();
        for (String[] c : CHANNEL_CASES) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("source_name", c[0]);
            raw.put("referring_site", c[1]);
            raw.put("landing_site", c[2]);
            raw.put("order_status_url", STATUS_URL);
            UUID o = order(t, json(raw));
            String expected = AnalyticsMappings.channel(c[0], true, c[1], c[2], AnalyticsMappings.ownHosts(STATUS_URL, null));
            assertThat(jdbc.queryForObject("SELECT channel FROM orders WHERE id = ?", String.class, o))
                .as("orders.channel %s", Arrays.toString(c)).isEqualTo(expected);
        }
        UUID graphql = order(t, "{\"name\":\"#1\",\"paymentGatewayNames\":[\"manual\"]}");
        assertThat(jdbc.queryForObject("SELECT channel FROM orders WHERE id = ?", String.class, graphql)).isEqualTo("Unknown");
    }

    // ── payment ─────────────────────────────────────────────────────────────

    static final List<List<String>> PAYMENT_CASES = List.of(
        List.of("Cash on Delivery (COD)"),
        List.of("Paymob - Native Checkout for Debit/Credit Cards", "Paymob"),
        List.of("Pay with Card, Wallet and Installment via Kashier"),
        List.of("manual"),
        List.of("Cash on Delivery (COD)", "manual"),
        List.of("Paymob - Native Checkout for Debit/Credit Cards", "Cash on Delivery (COD)"),
        List.of(),
        List.of("gift_card"),
        List.of("bank transfer"),
        List.of(" COD "), List.of("MANUAL"), List.of("valU"), List.of("Fawry"), List.of("InstaPay"),
        List.of("gift_card", "manual"), List.of("", "manual"), List.of("cod", "Cash on Delivery (COD)"));

    @Test
    void payment_sqlEqualsJava() throws Exception {
        for (List<String> g : PAYMENT_CASES) {
            String sql = jdbc.queryForObject("SELECT analytics_payment(?::jsonb)", String.class,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(g));
            assertThat(sql).as("payment %s", g).isEqualTo(AnalyticsMappings.payment(g));
        }
        assertThat(jdbc.queryForObject("SELECT analytics_payment(NULL)", String.class)).isEqualTo(AnalyticsMappings.payment(null));
        assertThat(jdbc.queryForObject("SELECT analytics_payment('\"manual\"'::jsonb)", String.class)).isEqualTo("Manual");
        assertThat(jdbc.queryForObject("SELECT analytics_payment('[1, null, \"manual\"]'::jsonb)", String.class)).isEqualTo("Manual");
    }

    // ── failure reason ──────────────────────────────────────────────────────

    static final String[] REASONS = {
        "Cancellation - the customer refuses to receive the shipment.",
        "Retry delivery - the customer is not in the address.",
        "Customer phone is switched off",
        "Waiting for data modification - address not clear",
        "Postponed - the customer requested postponement for another day.",
        "Cancellation - product issue",
        "Something new from Bosta",
        null, "  ", "​", "REJECTED by consignee", "Customer doesn't want it", "Shop closed", "Out of zone",
        "Rescheduled", "Damaged parcel", "Wrong item sent", "Wrong size", "Bad quality", "Wants to open package",
        "No answer", "not available", "Delivery at a later date"};

    @Test
    void failureReason_sqlEqualsJava() {
        for (String r : REASONS) {
            String sql = jdbc.queryForObject("SELECT analytics_failure_category(?)", String.class, r);
            assertThat(sql).as("reason %s", r).isEqualTo(AnalyticsMappings.failureReason(r));
        }
    }

    // ── generated columns ───────────────────────────────────────────────────

    @Test
    void generatedColumns_holdWhatTheQueriesParsedFromRaw() {
        UUID[] t = tenant();
        UUID o = order(t, """
            {"source_name":"web","referring_site":"https://instagram.com/","payment_gateway_names":["Cash on Delivery (COD)"],
             "shipping_address":{"province_code":"GZ","phone":"+20 100 123 4567"},"customer":{"id":555,"phone":"01001234567"},
             "discount_applications":[{"type":"discount_code","code":" save10 "},{"type":"automatic","title":"B2G1"}],
             "fulfillments":[{"created_at":"2026-09-10T20:00:00+03:00","status":"success","line_items":[{"id":11}]}],
             "refunds":[{"created_at":"2026-09-12T10:00:00+03:00","refund_line_items":[
                 {"line_item_id":11,"quantity":1,"restock_type":"return"},
                 {"line_item_id":12,"quantity":2,"restock_type":"no_restock"},
                 {"line_item_id":11,"quantity":1,"restock_type":"cancel"}]},
               {"created_at":"2026-09-09T10:00:00+03:00","refund_line_items":[{"line_item_id":11,"quantity":1,"restock_type":"return"}]}]}
            """);
        Map<String, Object> r = jdbc.queryForMap("SELECT channel, payment_group, customer_key, ship_province, shopify_fulfilled, " +
            "discount_types::text, discount_labels::text, discount_codes::text, refund_lines::text, raw_cancelled, is_cancelled " +
            "FROM orders WHERE id = ?", o);
        assertThat(r.get("channel")).isEqualTo("Instagram");
        assertThat(r.get("payment_group")).isEqualTo("COD");
        assertThat(r.get("customer_key")).isEqualTo("c:555");
        assertThat(r.get("ship_province")).isEqualTo("GZ");
        assertThat(r.get("shopify_fulfilled")).isEqualTo(true);
        assertThat(r.get("discount_types")).isEqualTo("{discount_code,automatic}");
        assertThat(r.get("discount_labels")).isEqualTo("{SAVE10,B2G1}");
        assertThat(r.get("discount_codes")).isEqualTo("{SAVE10}");
        // line 11: the 12 Sep return came after the fulfillment (counts, returned); the 9 Sep one before it (out);
        // line 12: no_restock not in a fulfillment → not added back; the order has fulfillments → verified.
        assertThat(r.get("refund_lines")).isEqualTo("{\"11\": [1, 1, 0], \"12\": [0, 0, 0]}");
        assertThat(r.get("raw_cancelled")).isEqualTo(false);

        UUID nf = order(t, "{\"refunds\":[{\"created_at\":\"2026-09-12T10:00:00Z\",\"refund_line_items\":[" +
            "{\"line_item_id\":7,\"quantity\":1,\"restock_type\":\"no_restock\"},{\"line_item_id\":7,\"quantity\":2,\"restock_type\":\"return\"}]}]," +
            "\"cancelled_at\":\"2026-09-13T10:00:00Z\",\"customer\":{\"phone\":\"0020 100-123-4567\"}}");
        Map<String, Object> n = jdbc.queryForMap("SELECT refund_lines::text, customer_key, raw_cancelled, is_cancelled, channel " +
            "FROM orders WHERE id = ?", nf);
        assertThat(n.get("refund_lines")).as("no fulfillments key: return only, no_restock unverified").isEqualTo("{\"7\": [2, 2, 1]}");
        assertThat(n.get("customer_key")).isEqualTo("p:01001234567");
        assertThat(n.get("raw_cancelled")).isEqualTo(true);
        assertThat(n.get("is_cancelled")).isEqualTo(true);
        assertThat(n.get("channel")).isEqualTo("Unknown");

        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw) VALUES (?, ?, ?, ?, 3, " +
            "'gid://shopify/LineItem/11', '{\"price\":\"100.00\",\"quantity\":3,\"current_quantity\":2,\"discount_allocations\":" +
            "[{\"amount\":\"15.00\",\"discount_application_index\":0},{\"amount\":\"6\",\"discount_application_index\":1}]}'::jsonb)",
            item, t[0], o, t[2]);
        Map<String, Object> i = jdbc.queryForMap("SELECT line_key, unit_price, price_is_raw, original_qty, current_qty, line_discount, " +
            "net_unit_price, alloc_amounts::text, alloc_indexes::text FROM order_items WHERE id = ?", item);
        assertThat(i.get("line_key")).isEqualTo("11");
        assertThat((BigDecimal) i.get("unit_price")).isEqualByComparingTo("100.00");
        assertThat(i.get("price_is_raw")).isEqualTo(true);
        assertThat(i.get("original_qty")).isEqualTo(3);
        assertThat(i.get("current_qty")).isEqualTo(2);
        assertThat((BigDecimal) i.get("line_discount")).isEqualByComparingTo("21");
        assertThat((BigDecimal) i.get("net_unit_price")).isEqualByComparingTo("93");
        assertThat(i.get("alloc_amounts")).isEqualTo("{15.00,6}");
        assertThat(i.get("alloc_indexes")).isEqualTo("{0,1}");

        UUID s = UUID.randomUUID();
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw, " +
            "last_failure_reason) VALUES (?, ?, ?, 'PAR-1', 'returned', 'forward', '{\"type\":{\"code\":20,\"value\":\"RTO\"}," +
            "\"state\":{\"code\":46,\"value\":\"Returned\"},\"cod\":\"450.5\",\"collectedFromBusiness\":\"2026-09-10T15:00:00.000Z\"," +
            "\"dropOffAddress\":{\"city\":{\"_id\":\"c1\",\"name\":\"Cairo\"}}}'::jsonb, " +
            "'Cancellation - the customer refuses to receive the shipment.')", s, t[0], o);
        Map<String, Object> sh = jdbc.queryForMap("SELECT type_code, state_value, city_id, city_name, raw_cod, " +
            "collected_from_business_at::text AS cfb, last_failure_category FROM shipments WHERE id = ?", s);
        assertThat(sh.get("type_code")).isEqualTo("20");
        assertThat(sh.get("state_value")).isEqualTo("Returned");
        assertThat(sh.get("city_id")).isEqualTo("c1");
        assertThat(sh.get("city_name")).isEqualTo("Cairo");
        assertThat((BigDecimal) sh.get("raw_cod")).isEqualByComparingTo("450.5");
        assertThat(sh.get("cfb")).isNotNull();
        assertThat(sh.get("last_failure_category")).isEqualTo("Customer refused");
        jdbc.update("UPDATE shipments SET last_failure_reason = NULL, exception_reason = 'Postponed - another day' WHERE id = ?", s);
        assertThat(jdbc.queryForObject("SELECT last_failure_category FROM shipments WHERE id = ?", String.class, s))
            .as("recomputed on UPDATE").isEqualTo("Postponed / rescheduled");
    }

    @Test
    void malformedPayloads_neverBreakAnInsert() {
        UUID[] t = tenant();
        UUID o = order(t, """
            {"refunds":[{"created_at":"not a date","refund_line_items":[{"line_item_id":1,"quantity":"x","restock_type":"return"}]}],
             "fulfillments":[{"created_at":"2026-99-99","status":"success","line_items":"nope"}],
             "discount_applications":{"type":"automatic"},"payment_gateway_names":42,"landing_site":"/?utm_source=%E2%28",
             "referring_site":{"x":1},"customer":{"id":{"nested":true}},"cancelled_at":null}
            """);
        assertThat(jdbc.queryForObject("SELECT payment_group FROM orders WHERE id = ?", String.class, o)).isEqualTo("Other");
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw) VALUES (?, ?, ?, ?, 1, " +
            "'not-a-gid', '{\"price\":\"abc\",\"quantity\":\"2.5\",\"current_quantity\":null,\"discount_allocations\":" +
            "[{\"amount\":\"x\",\"discount_application_index\":\"y\"}]}'::jsonb)", item, t[0], o, t[2]);
        Map<String, Object> i = jdbc.queryForMap("SELECT line_key, unit_price, original_qty, line_discount FROM order_items WHERE id = ?", item);
        assertThat(i.get("line_key")).isNull();
        assertThat(i.get("unit_price")).isNull();
        assertThat(i.get("original_qty")).isNull();
        assertThat((BigDecimal) i.get("line_discount")).isEqualByComparingTo("0");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'PAR-BAD', 'delivered', 'forward', '{\"cod\":\"n/a\",\"collectedFromBusiness\":\"2026-13-45Tzz\"," +
            "\"type\":\"SEND\",\"dropOffAddress\":\"x\"}'::jsonb)", t[0], o);
        Map<String, Object> sh = jdbc.queryForMap("SELECT raw_cod, collected_from_business_at, type_code, city_id FROM shipments " +
            "WHERE tracking_number = 'PAR-BAD'");
        assertThat(sh.get("raw_cod")).isNull();
        assertThat(sh.get("collected_from_business_at")).isNull();
        assertThat(sh.get("city_id")).isNull();
        assertThat(order(t, null)).isNotNull();          // no raw at all
    }

    @Test
    void gdprRedaction_clearsTheCustomerKey_andTheShippingProvince() {
        UUID[] t = tenant();
        UUID o = order(t, "{\"source_name\":\"web\",\"customer\":{\"id\":777,\"phone\":\"01011112222\"}," +
            "\"shipping_address\":{\"province_code\":\"C\",\"phone\":\"01011112222\"},\"phone\":\"01011112222\"}");
        assertThat(jdbc.queryForObject("SELECT customer_key FROM orders WHERE id = ?", String.class, o)).isEqualTo("c:777");
        new CustomerRedaction(jdbc).redactShop(t[0]);
        Map<String, Object> r = jdbc.queryForMap("SELECT customer_key, ship_province, channel, pii_redacted_at FROM orders WHERE id = ?", o);
        assertThat(r.get("pii_redacted_at")).isNotNull();
        assertThat(r.get("customer_key")).as("redacted raw → no customer id or phone left").isNull();
        assertThat(r.get("ship_province")).isNull();
        assertThat(r.get("channel")).as("not personal data — stays").isEqualTo("Direct");
        // A phone-only order: the key came from the phone, and goes with it.
        UUID p = order(t, "{\"shipping_address\":{\"phone\":\"+201011112222\"}}");
        assertThat(jdbc.queryForObject("SELECT customer_key FROM orders WHERE id = ?", String.class, p)).isEqualTo("p:01011112222");
        new CustomerRedaction(jdbc).redactShop(t[0]);
        assertThat(jdbc.queryForObject("SELECT customer_key FROM orders WHERE id = ?", String.class, p)).isNull();
    }

    /**
     * Generated-column functions must be fast and parallel-safe for real: an EXCEPTION block opens
     * a subtransaction per call (a 60k-order rewrite ran > 10 minutes) and fails inside a parallel
     * plan ("cannot start subtransactions during a parallel operation").
     */
    @Test
    void generatedColumnFunctions_haveNoExceptionBlocks_andRunInAParallelPlan() {
        List<String> withException = jdbc.queryForList(
            "SELECT proname FROM pg_proc WHERE proname LIKE 'analytics\\_%' AND prosrc ILIKE '%exception%'", String.class);
        assertThat(withException).as("analytics_* functions with an EXCEPTION block").isEmpty();
        List<String> notSafe = jdbc.queryForList(
            "SELECT proname FROM pg_proc WHERE proname LIKE 'analytics\\_%' AND (proparallel <> 's' OR provolatile <> 'i')", String.class);
        assertThat(notSafe).as("analytics_* functions not IMMUTABLE PARALLEL SAFE").isEmpty();
        UUID[] t = tenant();
        order(t, "{\"source_name\":\"web\",\"referring_site\":\"https://x.com/\",\"landing_site\":\"/?utm_source=f%61cebook\"," +
            "\"refunds\":[{\"created_at\":\"bad\",\"refund_line_items\":[{\"line_item_id\":1,\"quantity\":1,\"restock_type\":\"return\"}]}]}");
        Long n = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).execute(st -> {
                jdbc.execute("SET LOCAL debug_parallel_query = on");
                return jdbc.queryForObject("SELECT count(*) FROM orders WHERE analytics_order_channel(raw) IS NOT NULL " +
                    "AND analytics_refund_lines(raw) IS DISTINCT FROM '{}'::jsonb AND analytics_customer_key(raw) IS DISTINCT FROM 'x' " +
                    "AND analytics_payment(raw -> 'payment_gateway_names') IS NOT NULL", Long.class);
            });
        assertThat(n).isPositive();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** [tenant, store, variant]. */
    static UUID[] tenant() {
        UUID tid = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Parity')", tid);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            store, tid, "parity-" + tid + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'P')", product, tid, store, "P-" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, 'V')", variant, tid, product, "V-" + variant);
        return new UUID[] {tid, store, variant};
    }

    static UUID order(UUID[] t, String raw) {
        UUID o = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'new'::order_status, now(), ?::jsonb)", o, t[0], t[1], "EXT-" + o, "#P" + o.toString().substring(0, 6), raw);
        return o;
    }

    static String json(Map<String, Object> m) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
