package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.inventory.PhoneScanSource;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q1b — "via phone" is decided server-side. A stock-take / transfer scan is 'phone' only when
 * its relayEventId names a relay event of the caller's own live pairing, in the caller's
 * tenant, for the same code; every other case (no id, unknown id, another worker's pairing,
 * another tenant's event, a revoked pairing, a different code) is processed normally as
 * 'hardware' — never rejected. phoneScanCount on the stock-take review; per-line phone scans
 * on the transfer detail (piece_events metadata {"via":"phone"}). The verifier on an app_user
 * connection: tenant isolation with a same-tenant positive control.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhoneScanSourceTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @MockBean  JobScheduler     jobScheduler;

    /** One tenant: an owner (the scanner), a colleague, a fulfillment + a showroom location, one variant. */
    record Shop(UUID tenant, UUID owner, UUID colleague, UUID fulfillment, UUID showroom, UUID variant, String token) {}

    private Shop shop(String name) {
        UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID(), colleague = UUID.randomUUID();
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        UUID fulfillment = UUID.randomUUID(), showroom = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, name);
        for (UUID u : List.of(owner, colleague)) {
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, 'U', ?, 'x', 'owner', true)", u, tenant, "u-" + u + "@t.local");
        }
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "ps-" + store + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', 'TEE-M')",
            variant, tenant, product, "V-" + variant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, 'Main', 'warehouse', true, true)",
            fulfillment, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, 'Showroom', 'showroom', false, false)",
            showroom, tenant);
        return new Shop(tenant, owner, colleague, fulfillment, showroom, variant, jwt.issueAccessToken(owner, tenant, "owner"));
    }

    private String piece(Shop s) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
            id, s.tenant, s.variant, "PC-" + id, id, s.fulfillment);
        return "PC-" + id;
    }

    /** A claimed pairing for the user; revoked / expired on request. */
    private UUID pairing(UUID tenant, UUID user, boolean revoked, boolean expired) {
        String device = "dev" + UUID.randomUUID().toString().replace("-", "");
        return jdbc.queryForObject(
            "INSERT INTO scan_pairings (tenant_id, station_device_id, station_user_id, pair_code_hash, device_secret_hash, " +
            "  pair_code_expires_at, claimed_at, expires_at, revoked_at, revoked_reason) " +
            "VALUES (?, ?, ?, ?, ?, now() + interval '2 minutes', now(), " +
            "        now() + (CASE WHEN ? THEN interval '-1 minute' ELSE interval '12 hours' END), " +
            "        CASE WHEN ? THEN now() END, CASE WHEN ? THEN 'unpaired' END) RETURNING id",
            UUID.class, tenant, device, user, "pc-" + UUID.randomUUID(), "ds-" + UUID.randomUUID(),
            expired, revoked, revoked);
    }

    private long seq = 0;
    private UUID relay(UUID tenant, UUID pairing, String code) {
        return jdbc.queryForObject(
            "INSERT INTO scan_relay_events (tenant_id, pairing_id, seq, code, status) VALUES (?, ?, ?, ?, 'delivered') RETURNING id",
            UUID.class, tenant, pairing, ++seq, code);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(Shop s, String path, Map<String, Object> body) {
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
            new HttpEntity<>(body, auth(s.token)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(path + " → " + r.getBody()).isTrue();
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(Shop s, String path) {
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
            new HttpEntity<>(auth(s.token)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static Map<String, Object> scanBody(String code, String condition, UUID relayEventId) {
        Map<String, Object> m = new HashMap<>();
        m.put("barcode", code);
        if (condition != null) m.put("condition", condition);
        if (relayEventId != null) m.put("relayEventId", relayEventId.toString());
        return m;
    }

    // ── stock take ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void stockTake_onlyAVerifiedRelayEventIsPhone_everythingElseIsHardware_neverRejected_andTheReviewCountsThem() {
        Shop a = shop("ST A"), b = shop("ST B");
        String p1 = piece(a), p2 = piece(a), p3 = piece(a), p4 = piece(a), p5 = piece(a), p6 = piece(a), p7 = piece(a), p8 = piece(a);
        String sid = (String) post(a, "/api/v1/stock-takes/sessions", Map.of("scopeType", "all")).get("sessionId");
        String base = "/api/v1/stock-takes/sessions/" + sid + "/scan";
        UUID mine = pairing(a.tenant, a.owner, false, false);
        // valid → phone (code compared without whitespace)
        Map<String, Object> phone = post(a, base, scanBody(p1, "good", relay(a.tenant, mine, " " + p1 + " ")));
        assertThat(phone.get("scanDevice")).isEqualTo("phone");
        // keyboard: no relayEventId → hardware
        assertThat(post(a, base, scanBody(p2, "good", null)).get("scanDevice")).isEqualTo("hardware");
        // another worker's pairing (same tenant) → hardware
        UUID colleagues = pairing(a.tenant, a.colleague, false, false);
        assertThat(post(a, base, scanBody(p3, "good", relay(a.tenant, colleagues, p3))).get("scanDevice")).isEqualTo("hardware");
        // mismatched code → hardware
        assertThat(post(a, base, scanBody(p4, "good", relay(a.tenant, mine, p5))).get("scanDevice")).isEqualTo("hardware");
        // unknown relayEventId → hardware
        assertThat(post(a, base, scanBody(p5, "good", UUID.randomUUID())).get("scanDevice")).isEqualTo("hardware");
        // another tenant's relay event → hardware
        UUID theirs = pairing(b.tenant, b.owner, false, false);
        assertThat(post(a, base, scanBody(p6, "good", relay(b.tenant, theirs, p6))).get("scanDevice")).isEqualTo("hardware");
        // revoked pairing → hardware; expired pairing → hardware
        UUID revoked = pairing(a.tenant, a.colleague, true, false);
        assertThat(post(a, base, scanBody(p7, "good", relay(a.tenant, revoked, p7))).get("scanDevice")).isEqualTo("hardware");
        jdbc.update("UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'unpaired' WHERE id = ?", mine);
        UUID expired = pairing(a.tenant, a.owner, false, true);
        assertThat(post(a, base, scanBody(p8, "good", relay(a.tenant, expired, p8))).get("scanDevice")).isEqualTo("hardware");
        // an unknown barcode with a verified relay event: still counted as a phone scan
        jdbc.update("UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'unpaired' WHERE id = ?", expired);
        UUID again = pairing(a.tenant, a.owner, false, false);
        Map<String, Object> unknown = post(a, base, scanBody("NOPE-123", "good", relay(a.tenant, again, "NOPE-123")));
        assertThat(unknown.get("classification")).isEqualTo("unknown");
        assertThat(unknown.get("scanDevice")).isEqualTo("phone");
        // a re-scan keeps the first row's device
        assertThat(post(a, base, scanBody(p1, "good", null)).get("scanDevice")).isEqualTo("phone");
        assertThat(post(a, base, scanBody(p2, "good", relay(a.tenant, again, p2))).get("scanDevice")).isEqualTo("hardware");

        Map<String, Object> review = get(a, "/api/v1/stock-takes/sessions/" + sid + "/reconciliation");
        assertThat(review.get("scanCount")).isEqualTo(9);
        assertThat(review.get("phoneScanCount")).isEqualTo(2);
        List<Map<String, Object>> counted = ((Map<String, List<Map<String, Object>>>) review.get("buckets")).get("on_shelf_counted");
        Map<String, Object> row1 = counted.stream().filter(r -> p1.equals("PC-" + r.get("pieceId"))).findFirst().orElseThrow();
        assertThat(row1.get("scanDevice")).isEqualTo("phone");
        Map<String, Object> row2 = counted.stream().filter(r -> p2.equals("PC-" + r.get("pieceId"))).findFirst().orElseThrow();
        assertThat(row2.get("scanDevice")).isEqualTo("hardware");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_take_scans WHERE session_id = ?::uuid AND scan_device = 'phone'",
            Integer.class, sid)).isEqualTo(2);
    }

    // ── transfers ───────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void transfer_scanOutAndScanBack_viaPhoneLandsInPieceEventsMetadata_andTheDetailCountsItPerLine() {
        Shop a = shop("TR A"), b = shop("TR B");
        String tid = (String) post(a, "/api/v1/transfers", Map.of("transferType", "showroom",
            "destinationLocationId", a.showroom.toString())).get("id");
        UUID mine = pairing(a.tenant, a.owner, false, false);
        String p1 = piece(a), p2 = piece(a), p3 = piece(a);

        assertThat(post(a, "/api/v1/transfers/" + tid + "/scan-out", scanBody(p1, null, relay(a.tenant, mine, p1))).get("success")).isEqualTo(true);
        assertThat(post(a, "/api/v1/transfers/" + tid + "/scan-out", scanBody(p2, null, relay(a.tenant, mine, p3))).get("success")).isEqualTo(true);
        UUID theirs = pairing(b.tenant, b.owner, false, false);
        assertThat(post(a, "/api/v1/transfers/" + tid + "/scan-out", scanBody(p3, null, relay(b.tenant, theirs, p3))).get("success")).isEqualTo(true);

        assertThat(via(a, p1, "transferred_out")).isEqualTo("phone");
        assertThat(via(a, p2, "transferred_out")).as("code mismatch").isNull();
        assertThat(via(a, p3, "transferred_out")).as("another tenant's event").isNull();
        Map<String, Object> detail = get(a, "/api/v1/transfers/" + tid);
        assertThat(((Number) detail.get("phoneScanCount")).intValue()).isEqualTo(1);
        assertThat(((Number) ((List<Map<String, Object>>) detail.get("lines")).get(0).get("phone_scans")).intValue()).isEqualTo(1);

        post(a, "/api/v1/transfers/" + tid + "/mark-sent", Map.of());
        post(a, "/api/v1/transfers/" + tid + "/begin-reconcile", Map.of());
        assertThat(post(a, "/api/v1/transfers/" + tid + "/scan-back", scanBody(p1, "good", relay(a.tenant, mine, p1))).get("success")).isEqualTo(true);
        assertThat(post(a, "/api/v1/transfers/" + tid + "/scan-back", scanBody(p2, "condemned", null)).get("success")).isEqualTo(true);
        assertThat(via(a, p1, "returned_from_transfer")).isEqualTo("phone");
        assertThat(via(a, p2, "condemned_at_vendor")).isNull();
        detail = get(a, "/api/v1/transfers/" + tid);
        assertThat(((Number) detail.get("phoneScanCount")).intValue()).isEqualTo(2);
        assertThat(((Number) ((List<Map<String, Object>>) detail.get("lines")).get(0).get("phone_scans")).intValue()).isEqualTo(2);
    }

    private String via(Shop s, String barcode, String eventType) {
        return jdbc.queryForObject(
            "SELECT pe.metadata->>'via' FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.barcode = ? AND pe.tenant_id = ? AND pe.event_type = ? ORDER BY pe.id DESC LIMIT 1",
            String.class, barcode, s.tenant, eventType);
    }

    // ── app_user: tenant isolation ──────────────────────────────────────────

    @Test
    void verifier_asAppUser_seesOnlyItsOwnTenantsRelayEvents_withSameTenantPositiveControl() {
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        PhoneScanSource verifier = new PhoneScanSource(new JdbcTemplate(ds),
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        Shop a = shop("Iso A"), b = shop("Iso B");
        UUID pa = pairing(a.tenant, a.owner, false, false);
        UUID eventA = relay(a.tenant, pa, "PC-ISO-1");

        assertThat(TenantContext.runAs(a.tenant, () -> verifier.isPhone(eventA, "PC-ISO-1", a.owner)))
            .as("same-tenant positive control").isTrue();
        assertThat(TenantContext.runAs(b.tenant, () -> verifier.isPhone(eventA, "PC-ISO-1", a.owner)))
            .as("tenant B can't see tenant A's relay event, even naming A's user").isFalse();
        assertThat(TenantContext.runAs(b.tenant, () -> verifier.isPhone(eventA, "PC-ISO-1", b.owner))).isFalse();
        assertThat(TenantContext.runAs(a.tenant, () -> verifier.isPhone(eventA, "PC-ISO-1", a.colleague)))
            .as("another worker of the same tenant").isFalse();
        assertThat(TenantContext.runAs(a.tenant, () -> verifier.isPhone(null, "PC-ISO-1", a.owner))).isFalse();
    }
}
