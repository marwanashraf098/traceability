package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.*;
import com.traceability.portal.PortalService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Non-Bosta couriers G3 — Bosta isolation, golden. One realistic Bosta tenant runs every surface G3 touches:
 * a whole pickup session (scans of every outcome, a leg that ends between scan and close, close, manifest),
 * Scan returns (a CRP return leg + its delivered piece, an untracked returned-to-sender parcel with unit rows
 * and an Arrived unit, a with_courier piece with RTO history, one with none, a delivered piece out of window,
 * a foreign scan, grading, close and the intake stamps it writes), Lookup, the Orders list and order detail
 * (derived status, timeline), the open alerts, returns-portal eligibility and two analytics endpoints.
 *
 * The golden files were recorded on origin/main a9a9f4c — BEFORE any G3 code — with
 * {@code -Dgolden.write=true}; this test then proves the same output after G3:
 *   g1  flag OFF → byte-identical to g3-bosta-isolation-off.json;
 *   g2  flag ON, still no generic shipment → byte-identical to g3-bosta-isolation-on.json, except the keys
 *       listed in {@link #FLAG_ON_CHANGES} (each one a deliberate G3 change for switched-on tenants only).
 * Volatile values (ids, piece codes, timestamps, dates) are replaced by stable tokens in order of appearance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericG3IsolationGoldenTest {

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
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    static final Path GOLDEN_OFF = Path.of("src/test/resources/golden/g3-bosta-isolation-off.json");
    static final Path GOLDEN_ON  = Path.of("src/test/resources/golden/g3-bosta-isolation-on.json");

    /** Keys of the flag-ON output that G3 may change (switched-on tenants only). Empty when recorded. */
    static final Set<String> FLAG_ON_CHANGES = Set.of();

    @LocalServerPort int port;
    @Autowired TestRestTemplate      rest;
    @Autowired JwtService            jwt;
    @Autowired JdbcTemplate          jdbc;
    @Autowired ObjectMapper          mapper;
    @Autowired PickupSessionService  pickups;
    @Autowired ReturnSessionService  returns;
    @Autowired LookupService         lookup;
    @Autowired ExceptionService      exceptions;
    @Autowired PortalService         portal;
    @MockBean  BostaGateway          bostaGateway;
    @MockBean  ShopifyGateway        shopifyGateway;
    @MockBean  JobScheduler          jobScheduler;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void g1_flagOff_bostaTenant_identicalToPreG3Golden() throws Exception {
        String out = scenario("G3gold-off", 0L, false);
        check(out, GOLDEN_OFF, "off");
        assertThat(out).isEqualTo(Files.readString(GOLDEN_OFF));
    }

    @Test
    void g2_flagOn_bostaTenant_noGenericLeg_identicalExceptListedKeys() throws Exception {
        String out = scenario("G3gold-on", 1_000_000L, true);
        check(out, GOLDEN_ON, "on");
        @SuppressWarnings("unchecked") Map<String, Object> on = mapper.readValue(out, Map.class);
        @SuppressWarnings("unchecked") Map<String, Object> golden = mapper.readValue(Files.readString(GOLDEN_ON), Map.class);
        for (String k : FLAG_ON_CHANGES) {
            assertThat(on.get(k)).as(k + " is a listed G3 change — it must actually differ").isNotEqualTo(golden.get(k));
            on.remove(k);
            golden.remove(k);
        }
        assertThat(on).isEqualTo(golden);
    }

    private void check(String out, Path golden, String tag) throws Exception {
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/g3-bosta-isolation-" + tag + ".actual.json"), out);
        if (Boolean.getBoolean("golden.write")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, out);
        }
    }

    // ── The scenario ─────────────────────────────────────────────────────────────────────────────

    private String scenario(String name, long offset, boolean genericOn) throws Exception {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = name.toLowerCase();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", tenant, name, slug);
        if (genericOn) jdbc.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, tenant);
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', 'x', ?, 'active')", tenant, "wh-" + tenant);
        UUID owner = user(tenant, "Mona", "owner"), worker = user(tenant, "Fatma", "worker");
        UUID variant = variant(tenant, store, name);
        long b = 8_484_805_000L + offset;
        Ctx c = new Ctx(tenant, store, variant, worker);

        // Pickup orders
        Ord packed   = order(c, "#3001", "packed", 2, b + 1, "created", null);  String pPacked = piece(c, packed, "packed", "packed");
        Ord ending   = order(c, "#3002", "packed", 3, b + 2, "created", null);  piece(c, ending, "packed", "packed");
        Ord unpacked = order(c, "#3003", "new", 4, b + 3, "created", null);     piece(c, unpacked, "reserved", "active");
        // Returns orders
        Ord crp      = order(c, "#3004", "delivered", 9, b + 40, "delivered", "now() - interval '6 days'");
        String pCrp  = piece(c, crp, "delivered", "packed");
        leg(c, crp, "return", "returned", b + 4, null);
        Ord rto      = order(c, "#3005", "cancelled", 10, b + 5, "returned", null);
        jdbc.update("UPDATE order_items SET quantity = 2 WHERE id = ?", rto.item());
        jdbc.update("UPDATE shipments SET raw = '{\"type\": {\"code\": 20, \"value\": \"Return to Origin\"}, " +
            "\"state\": {\"code\": 46, \"value\": \"Returned to business\"}}'::jsonb WHERE id = ?", rto.shipment());
        history(rto.shipment(), "returned", 46);
        Ord returning = order(c, "#3006", "with_courier", 11, b + 6, "returning", null);
        history(returning.shipment(), "with_courier", 24);
        history(returning.shipment(), "returning", 41);
        String pReturning = piece(c, returning, "with_courier", "packed");
        Ord moving   = order(c, "#3007", "with_courier", 12, b + 7, "with_courier", null);
        history(moving.shipment(), "with_courier", 24);
        String pMoving = piece(c, moving, "with_courier", "packed");
        Ord old      = order(c, "#3008", "delivered", 60, b + 8, "delivered", "now() - interval '50 days'");
        String pOld  = piece(c, old, "delivered", "packed");
        Ord recent   = order(c, "#3009", "delivered", 5, b + 9, "delivered", "now() - interval '3 days'");
        history(recent.shipment(), "with_courier", 24);
        history(recent.shipment(), "delivered", 45);
        piece(c, recent, "delivered", "packed");

        Map<String, Object> out = new LinkedHashMap<>();
        TenantContext.set(tenant);

        // 1. Pickup session
        UUID pickup = pickups.openSession(tenant, worker, LocalDate.of(2026, 10, 20), "10:00-13:00", "g3");
        Map<String, Object> pk = new LinkedHashMap<>();
        pk.put("01_packedHubPrefix", attempt(() -> pickups.scan(tenant, pickup, worker, "D-07-" + (b + 1))));
        pk.put("02_duplicate",       attempt(() -> pickups.scan(tenant, pickup, worker, String.valueOf(b + 1))));
        pk.put("03_ending",          attempt(() -> pickups.scan(tenant, pickup, worker, String.valueOf(b + 2))));
        pk.put("04_notPacked",       attempt(() -> pickups.scan(tenant, pickup, worker, String.valueOf(b + 3))));
        pk.put("05_returnLeg",       attempt(() -> pickups.scan(tenant, pickup, worker, String.valueOf(b + 4))));
        pk.put("06_unknownDigits",   attempt(() -> pickups.scan(tenant, pickup, worker, String.valueOf(b + 99))));
        pk.put("07_lettersDash",     attempt(() -> pickups.scan(tenant, pickup, worker, "SMSA-ABC123")));
        pk.put("08_aramexDigits",    attempt(() -> pickups.scan(tenant, pickup, worker, "34127840151")));
        pk.put("09_wijha",           attempt(() -> pickups.scan(tenant, pickup, worker, "WJ12345")));
        pk.put("10_pieceCode",       attempt(() -> pickups.scan(tenant, pickup, worker, pPacked)));
        TenantContext.set(tenant);
        jdbc.update("UPDATE shipments SET internal_state = 'cancelled' WHERE id = ?", ending.shipment());
        pk.put("11_detailBeforeClose", pickups.getSession(tenant, pickup));
        pk.put("12_close",             attempt(() -> pickups.closeSession(tenant, pickup, worker)));
        pk.put("13_manifest",          pickups.getManifestData(tenant, pickup));
        pk.put("14_list",              pickups.listSessions(tenant));
        pk.put("15_after",             shipmentState(packed, ending, unpacked));
        out.put("pickup", pk);
        TenantContext.set(tenant);

        // 2. Scan returns
        Map<String, Object> rt = new LinkedHashMap<>();
        UUID rs = returns.createSession(null, worker);
        rt.put("01_crpAwb",       attempt(() -> returns.scan(rs, String.valueOf(b + 4), location, worker)));
        rt.put("02_crpPiece",     attempt(() -> returns.scan(rs, pCrp, location, worker)));
        rt.put("03_rtoAwb",       attempt(() -> returns.scan(rs, String.valueOf(b + 5), location, worker)));
        rt.put("04_unitArrived",  attempt(() -> { returns.unitArrived(rs, rto.shipment(), rto.item(), 1, "sellable", worker); return "ok"; }));
        rt.put("05_returningPiece", attempt(() -> returns.scan(rs, pReturning, location, worker)));
        rt.put("06_movingPiece",  attempt(() -> returns.scan(rs, pMoving, location, worker)));
        rt.put("07_oldPiece",     attempt(() -> returns.scan(rs, pOld, location, worker)));
        rt.put("08_foreign",      attempt(() -> returns.scan(rs, "SMSA-ABC123", location, worker)));
        rt.put("09_aramexDigits", attempt(() -> returns.scan(rs, "34127840151", location, worker)));
        rt.put("10_movingAwb",    attempt(() -> returns.scan(rs, String.valueOf(b + 7), location, worker)));
        rt.put("11_detail",       returns.getSession(rs));
        rt.put("12_gradeCrp",     attempt(() -> returns.disposition(rs, id(pCrp), "restock", null, location, worker, "worker")));
        rt.put("13_gradeReturning", attempt(() -> returns.disposition(rs, id(pReturning), "restock", null, location, worker, "worker")));
        rt.put("14_gradeMoving",  attempt(() -> returns.disposition(rs, id(pMoving), "damaged", "torn", location, worker, "worker")));
        rt.put("14b_mismatchOld", attempt(() -> returns.disposition(rs, id(pOld), "mismatch", "not ours", location, owner, "owner")));
        rt.put("15_close",        attempt(() -> returns.close(rs, worker)));
        rt.put("16_after",        shipmentState(crp, rto, returning, moving, old));
        rt.put("17_awaitingScan", attempt(() -> link().awaitingScan()));
        out.put("returns", rt);
        TenantContext.set(tenant);

        // 3. Lookup
        out.put("lookupTracking", attempt(() -> lookup.lookupTracking("D-07-" + (b + 1))));
        out.put("lookupOrderCrp", attempt(() -> lookup.lookupOrder("#3004")));
        out.put("lookupLetters",  attempt(() -> lookup.lookupTracking("SMSA-ABC123")));

        // 4. Orders list + detail (HTTP, owner)
        String token = jwt.issueAccessToken(owner, tenant, "owner");
        out.put("ordersList", get(token, "/api/v1/orders?size=50"));
        for (Ord o : List.of(packed, crp, rto, moving, recent)) out.put("orderDetail" + o.number(), get(token, "/api/v1/orders/" + o.id()));

        // 5. Alerts
        TenantContext.set(tenant);
        // inventory_increment_sync_failed is left out: it comes from the restock's async Shopify push (a mock
        // here), so whether it is open yet is timing — not behaviour this test is about.
        out.put("exceptions", exceptions.detectAllOpen().stream()
            .filter(e -> !"inventory_increment_sync_failed".equals(e.get("type")))
            .map(e -> e.get("type") + "|" + e.get("severity") + "|" + e.get("subject_type")).sorted().toList());

        // 6. Portal eligibility
        TenantContext.clear();
        out.put("portalRecent", portalLookup(slug, "3009", "01011113009"));
        out.put("portalOld",    portalLookup(slug, "3008", "01011113008"));
        out.put("portalCrp",    portalLookup(slug, "3004", "01011113004"));

        // 7. Analytics
        out.put("analyticsDelivery", get(token, "/api/v1/analytics/delivery/summary?period=30d"));
        out.put("analyticsOrders",   get(token, "/api/v1/analytics/orders?size=200&period=30d"));

        String json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out);
        return stabilise(json, name, slug);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    record Ctx(UUID tenant, UUID store, UUID variant, UUID actor) {}
    record Ord(UUID id, UUID item, UUID shipment, String number) {}

    @Autowired ShipmentLinkService linkService;
    private ShipmentLinkService link() { return linkService; }

    private UUID user(UUID tenant, String name, String role) {
        return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id",
            UUID.class, tenant, name, name.toLowerCase() + "-" + UUID.randomUUID() + "@test.com", role);
    }

    private UUID variant(UUID tenant, UUID store, String name) {
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, tenant, store, "gid://shopify/Product/" + name);
        return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'SH-M', 'White / M') RETURNING id", UUID.class, tenant, product, "gid://shopify/ProductVariant/" + name);
    }

    /** An order (one line of 1) with a Bosta forward leg in {@code state}; delivered_at from the SQL expression. */
    private Ord order(Ctx c, String number, String status, int daysAgo, long tn, String state, String deliveredAtSql) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, created_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'Youssef Adel', ?, 1250.00, 'cod', " +
            "    now() - (? * interval '1 day'), now() - (? * interval '1 day')) RETURNING id",
            UUID.class, c.tenant(), c.store(), "gid://shopify/Order/" + c.tenant() + number, number, status,
            "0101111" + number.substring(1), daysAgo, daysAgo);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, c.tenant(), order, c.variant());
        UUID shipment = leg(c, new Ord(order, item, null, number), "forward", state, tn, deliveredAtSql);
        return new Ord(order, item, shipment, number);
    }

    private UUID leg(Ctx c, Ord o, String leg, String state, long tn, String deliveredAtSql) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, cod_amount, " +
            "    raw, delivered_at, created_at) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, 1250.00, '{\"type\": {\"code\": 10, \"value\": \"Send\"}}'::jsonb, " +
            (deliveredAtSql == null ? "NULL" : deliveredAtSql) + ", now() - interval '20 days') RETURNING id",
            UUID.class, c.tenant(), o.id(), String.valueOf(tn), state, leg);
    }

    private void history(UUID shipment, String state, int providerState) {
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state, occurred_at) " +
            "SELECT tenant_id, id, ?, ?, now() - interval '4 days' FROM shipments WHERE id = ?", state, providerState, shipment);
    }

    /** A tracked piece on the order's line in {@code status} with an allocation in {@code allocStatus}; its short code. */
    private String piece(Ctx c, Ord o, String status, String allocStatus) {
        String id = UlidGenerator.generate();
        String code = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at, last_user_id) " +
            "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, now(), ?)", id, c.tenant(), c.variant(), "PC-" + id, code, status, o.id(), c.actor());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", c.tenant(), id, c.actor());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
            c.tenant(), o.item(), id, allocStatus);
        return code;
    }

    private String id(String shortCode) {
        return jdbc.queryForObject("SELECT id FROM pieces WHERE short_code = ?", String.class, shortCode);
    }

    private List<Map<String, Object>> shipmentState(Ord... orders) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Ord o : orders) {
            rows.add(Map.of(o.number(), jdbc.queryForList(
                "SELECT s.shipment_leg, s.internal_state::text AS state, s.custody_locked_by_scan, " +
                "       s.delivered_at IS NOT NULL AS delivered, s.returned_at IS NOT NULL AS returned, " +
                "       s.return_intake_completed_at IS NOT NULL AS intake_done, s.return_intake_outcome, " +
                "       (SELECT string_agg(h.internal_state || ':' || coalesce(h.provider_state::text, '-'), ',' ORDER BY h.occurred_at, h.id) " +
                "          FROM shipment_status_history h WHERE h.shipment_id = s.id) AS history, " +
                "       (SELECT string_agg(p.status::text, ',' ORDER BY p.status::text) FROM pieces p WHERE p.current_order_id = s.order_id) AS pieces " +
                "FROM shipments s WHERE s.order_id = ? ORDER BY s.shipment_leg, s.tracking_number", o.id())));
        }
        return rows;
    }

    private Object get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Object> r = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), Object.class);
        return Map.of("status", r.getStatusCode().value(), "body", String.valueOf(r.getBody() == null ? "" : json(r.getBody())));
    }

    private Object portalLookup(String slug, String number, String phone) {
        return portal.lookup(slug, number, phone).map(r -> {
            Map<String, Object> body = r.body() == null ? null : new TreeMap<>(r.body());
            if (body != null) body.remove("token");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", String.valueOf(r.outcome()));
            m.put("body", body);
            return (Object) m;
        }).orElse("empty");
    }

    private String json(Object o) {
        try {
            return mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The call's result, or the refusal it ended in (class + status + reason) — both are behaviour. */
    private Object attempt(Supplier<Object> call) {
        try {
            return call.get();
        } catch (ResponseStatusException e) {
            return Map.of("refused", e.getStatusCode().value(), "reason", String.valueOf(e.getReason()));
        } catch (RuntimeException e) {
            return Map.of("refused", e.getClass().getSimpleName(), "reason", String.valueOf(e.getMessage()));
        }
    }

    /** Ids, piece codes, timestamps, dates and the run's offset tracking numbers → stable tokens. */
    private static String stabilise(String json, String name, String slug) {
        Map<String, String> seen = new LinkedHashMap<>();
        String[] patterns = {
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",      // uuid
            "PC-[0-9A-HJKMNP-TV-Z]{26}", "\\b[0-9A-HJKMNP-TV-Z]{26}\\b",          // piece barcode / id
            "\\bP[0-9]{6}\\b",                                                     // piece short code
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}(:\\d{2})?)?", // iso / pg time
            "\\b1[6-9]\\d{8}\\.\\d+\\b", "\\b1[6-9]\\d{11}\\b",                   // epoch s.frac / millis
            "\\b\\d{4}-\\d{2}-\\d{2}\\b"                                           // dates
        };
        String s = json.replace(name, "<tenant>").replace(slug, "<slug>");
        for (String p : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(s);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                String token = seen.computeIfAbsent(m.group(), k -> "<v" + seen.size() + ">");
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(token));
            }
            m.appendTail(b);
            s = b.toString();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("84[0-9]{8}").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            long v = Long.parseLong(m.group());
            m.appendReplacement(b, "TN+" + ((v - 8_484_805_000L) % 1_000_000L));
        }
        m.appendTail(b);
        return b.toString();
    }
}
