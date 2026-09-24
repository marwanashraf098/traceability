package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns portal Step 4e-A — branding in the portal settings (V103), the extended public
 * config, the variants list for the non-returnable setting, and the extra request-detail
 * fields for the merchant drawer. Over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalBrandingTest {

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

    static final String LOGO = "https://cdn.shopify.com/s/files/1/0001/logo.png";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired PasswordEncoder  passwordEncoder;

    UUID a, b, storeA, storeB;
    String ownerA, managerA, ownerB;

    @BeforeAll
    void setup() {
        a = tenant("Snouts Store", "snouts-4e");
        b = tenant("Jumi Store", "jumi-4e");
        storeA = store(a, "snouts-4e");
        storeB = store(b, "jumi-4e");
        ownerA   = login(user(a, "owner"));
        managerA = login(user(a, "manager"));
        ownerB   = login(user(b, "owner"));
    }

    @AfterEach
    void reset() {
        jdbc.update("UPDATE tenants SET portal_slug = 'snouts-4e', portal_enabled = true, portal_auto_approve = false, " +
                    "customer_return_window_days = 30, portal_logo_url = NULL, portal_brand_color = NULL, " +
                    "portal_policy_text = NULL WHERE id = ?", a);
        for (UUID t : List.of(a, b)) {
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM variants WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM products WHERE tenant_id = ?", t);
        }
    }

    // ── Settings: branding ───────────────────────────────────────────────────

    @Test
    void validBranding_saves_andIsReturnedByGet() {
        ResponseEntity<Map> r = put("/api/v1/tenant/portal-settings",
            settings("snouts-4e", LOGO, "#1A2b3C", "Unworn items within 14 days."), managerA);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("logoUrl", LOGO).containsEntry("brandColor", "#1A2b3C")
            .containsEntry("policyText", "Unworn items within 14 days.");
        assertThat(get("/api/v1/tenant/portal-settings", ownerA).getBody())
            .containsEntry("pickupBooking", false)
            .containsEntry("logoUrl", LOGO).containsEntry("brandColor", "#1A2b3C")
            .containsEntry("policyText", "Unworn items within 14 days.");

        // Blank clears (full replace).
        ResponseEntity<Map> cleared = put("/api/v1/tenant/portal-settings", settings("snouts-4e", "", " ", ""), ownerA);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody()).containsEntry("logoUrl", null).containsEntry("brandColor", null)
            .containsEntry("policyText", null);
    }

    @Test
    void invalidBranding_400_withField_andChangesNothing() {
        put("/api/v1/tenant/portal-settings", settings("snouts-4e", LOGO, "#112233", "Keep"), ownerA);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM tenants WHERE id = ?", a);

        record Case(Map<String, Object> body, String field, String code) {}
        List<Case> cases = List.of(
            new Case(settings("changed-slug", "https://example.com/logo.png", "#445566", "New"), "logoUrl", "LOGO_URL"),
            new Case(settings("changed-slug", "http://cdn.shopify.com/logo.png", "#445566", "New"), "logoUrl", "LOGO_URL"),
            new Case(settings("changed-slug", LOGO, "red", "New"), "brandColor", "BRAND_COLOR"),
            new Case(settings("changed-slug", LOGO, "#12345", "New"), "brandColor", "BRAND_COLOR"),
            new Case(settings("changed-slug", LOGO, "#445566", "x".repeat(2001)), "policyText", "POLICY_LENGTH"));
        for (Case c : cases) {
            ResponseEntity<Map> r = put("/api/v1/tenant/portal-settings", c.body(), ownerA);
            assertThat(r.getStatusCode()).as(c.code()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).containsEntry("field", c.field()).containsEntry("error", c.code());
        }
        assertThat(jdbc.queryForMap("SELECT * FROM tenants WHERE id = ?", a)).isEqualTo(before);

        // The limit itself is fine.
        assertThat(put("/api/v1/tenant/portal-settings", settings("snouts-4e", LOGO, "#445566", "x".repeat(2000)), ownerA)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void existingValidation_nowCarriesField_andTakenSlugIs409OnSlug() {
        ResponseEntity<Map> bad = put("/api/v1/tenant/portal-settings", settings("Bad Slug!", null, null, null), ownerA);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).containsEntry("field", "slug").containsEntry("error", "SLUG_FORMAT");

        ResponseEntity<Map> taken = put("/api/v1/tenant/portal-settings", settings("jumi-4e", null, null, null), ownerA);
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody()).containsEntry("field", "slug").containsEntry("error", "SLUG_TAKEN");
    }

    // ── Public config ────────────────────────────────────────────────────────

    @Test
    void publicConfig_returnsBranding_autoApprove_pickupBookingFalse_andNothingElse() {
        put("/api/v1/tenant/portal-settings", settings("snouts-4e", LOGO, "#1A2B3C", "Policy A"), ownerA);
        jdbc.update("UPDATE tenants SET portal_auto_approve = true WHERE id = ?", a);
        jdbc.update("UPDATE tenants SET portal_logo_url = ?, portal_brand_color = '#000000', portal_policy_text = 'Policy B' " +
                    "WHERE id = ?", "https://cdn.shopify.com/b.png", b);

        ResponseEntity<Map> r = rest.getForEntity(base() + "/api/v1/portal/snouts-4e/config", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().keySet()).containsExactlyInAnyOrder(
            "storeName", "returnWindowDays", "reasonCodes", "logoUrl", "brandColor", "policyText", "autoApprove", "pickupBooking");
        assertThat(r.getBody()).containsEntry("storeName", "Snouts Store").containsEntry("logoUrl", LOGO)
            .containsEntry("brandColor", "#1A2B3C").containsEntry("policyText", "Policy A")
            .containsEntry("autoApprove", true).containsEntry("pickupBooking", false);

        ResponseEntity<Map> other = rest.getForEntity(base() + "/api/v1/portal/jumi-4e/config", Map.class);
        assertThat(other.getBody()).containsEntry("policyText", "Policy B").containsEntry("autoApprove", false);
        jdbc.update("UPDATE tenants SET portal_logo_url = NULL, portal_brand_color = NULL, portal_policy_text = NULL WHERE id = ?", b);
    }

    // ── Variants list ────────────────────────────────────────────────────────

    @Test
    void variants_search_order_paging_nonReturnable() {
        UUID shirtM = variant(a, storeA, "Linen Shirt", "White / M", "2004-White-M");
        UUID shirtL = variant(a, storeA, "Linen Shirt", "White / L", "2004-White-L");
        UUID scarf  = variant(a, storeA, "Silk Scarf", "Sand", "3002-Sand");
        UUID pants  = variant(a, storeA, "Flipped Pants", "Black / XL", "1001_Black%XL");
        jdbc.update("UPDATE variants SET non_returnable = true WHERE id = ?", scarf);

        List<Map<String, Object>> all = items(get("/api/v1/variants", ownerA));
        assertThat(all).extracting(m -> m.get("id"))
            .containsExactly(pants.toString(), shirtL.toString(), shirtM.toString(), scarf.toString());
        assertThat(all.get(3)).containsEntry("productTitle", "Silk Scarf").containsEntry("variantTitle", "Sand")
            .containsEntry("sku", "3002-Sand").containsEntry("nonReturnable", true);
        assertThat(all.get(0)).containsEntry("nonReturnable", false);
        assertThat(get("/api/v1/variants", ownerA).getBody()).containsEntry("total", 4);

        assertThat(items(get("/api/v1/variants?search=linen", managerA))).hasSize(2);    // product title, case-insensitive
        assertThat(items(get("/api/v1/variants?search=sand", ownerA))).extracting(m -> m.get("id"))
            .containsExactly(scarf.toString());                                             // variant title / sku
        assertThat(items(get("/api/v1/variants?search=WHITE-L", ownerA))).extracting(m -> m.get("id"))
            .containsExactly(shirtL.toString());                                            // sku
        assertThat(items(get("/api/v1/variants?search=_", ownerA))).extracting(m -> m.get("id"))
            .containsExactly(pants.toString());                                             // _ is literal
        assertThat(items(get("/api/v1/variants?search=%25", ownerA))).extracting(m -> m.get("id"))
            .containsExactly(pants.toString());                                             // % is literal

        ResponseEntity<Map> page1 = get("/api/v1/variants?page=1&size=3", ownerA);
        assertThat(items(page1)).extracting(m -> m.get("id")).containsExactly(scarf.toString());
        assertThat(page1.getBody()).containsEntry("total", 4);
    }

    @Test
    void variants_crossTenantIsolation_withSameTenantPositiveControl() {
        UUID mine   = variant(a, storeA, "Linen Shirt", "White / M", "A-1");
        UUID theirs = variant(b, storeB, "Linen Shirt", "Black / S", "B-1");

        assertThat(items(get("/api/v1/variants?search=linen", ownerA))).extracting(m -> m.get("id"))
            .containsExactly(mine.toString());
        assertThat(items(get("/api/v1/variants?search=linen", ownerB))).extracting(m -> m.get("id"))
            .containsExactly(theirs.toString());
    }

    @Test
    void variants_workerForbidden() {
        String worker = login(user(a, "worker"));
        assertThat(get("/api/v1/variants", worker).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Request detail: drawer fields ────────────────────────────────────────

    @Test
    void requestDetail_includesPhone_deliveredAt_pickupArea() {
        UUID v = variant(a, storeA, "Linen Shirt", "White / M", "A-2");
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, address, pii_source) " +
            "VALUES (?, ?, 'gid://shopify/Order/4e1', '#1047', 'delivered'::order_status, 'cod'::order_payment_method, now(), " +
            "    'Mariam Saleh', '01012345678', '{\"city\":\"Cairo\",\"zone\":\"Nasr City\"}'::jsonb, 'bosta') RETURNING id",
            UUID.class, a, storeA);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
                    "VALUES (?, ?, 'bosta', '4000000001', 'delivered'::shipment_internal_state, 'forward', '2026-09-18T10:00:00Z')",
                    a, order);
        UUID req = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference) " +
            "VALUES (?, ?, 'refund', 'requested', 'RR-7K3F9M') RETURNING id", UUID.class, a, order);

        Map<String, Object> d = get("/api/v1/return-requests/" + req, ownerA).getBody();
        assertThat(d).containsEntry("customerPhone", "01012345678").containsEntry("pickupCity", "Cairo")
            .containsEntry("pickupZone", "Nasr City");
        assertThat((String) d.get("deliveredAt")).startsWith("2026-09-18");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> settings(String slug, String logo, String color, String policy) {
        Map<String, Object> m = new HashMap<>();
        m.put("slug", slug);
        m.put("enabled", true);
        m.put("autoApprove", false);
        m.put("returnWindowDays", 30);
        m.put("logoUrl", logo);
        m.put("brandColor", color);
        m.put("policyText", policy);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private UUID tenant(String name, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", id, name, slug);
        return id;
    }

    private UUID store(UUID tenant, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            id, tenant, slug + ".myshopify.com");
        return id;
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@test.local", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private UUID variant(UUID tenant, UUID store, String product, String title, String sku) {
        UUID p = jdbc.query("SELECT id FROM products WHERE tenant_id = ? AND title = ?",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenant, product);
        if (p == null) {
            p = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
                p, tenant, store, "P-" + p, product);
        }
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, p, "V-" + v, title, sku);
        return v;
    }

    private String base() { return "http://localhost:" + port; }

    private String login(UUID userId) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<Map> get(String path, String token) {
        // A URI, not a template string: the path is already encoded (e.g. search=%25).
        return rest.exchange(java.net.URI.create(base() + path), HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
    }

    private ResponseEntity<Map> put(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.PUT, new HttpEntity<>(body, auth(token)), Map.class);
    }
}
