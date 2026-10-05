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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q2 — Scan returns from a paired phone. The return-session scan is 'phone' only when its
 * relayEventId verifies (PhoneScanSource: caller's tenant, caller's own live pairing, same code);
 * the source is recorded as {"via":"phone"} on the scan's return_received event(s) and exposed as
 * via_phone per session item, the session's phoneScanCount and viaPhone per return-request item.
 * Every other case is a normal hardware scan — never rejected — and the scan itself (piece
 * status, return_kind, request attribution) is unchanged. app_user: tenant isolation with a
 * same-tenant positive control.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnPhoneScanTest {

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

    record Shop(UUID tenant, UUID owner, UUID colleague, UUID store, UUID location, UUID variant, String token) {}

    private Shop shop(String name) {
        UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID(), colleague = UUID.randomUUID();
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, name);
        for (UUID u : List.of(owner, colleague)) {
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'U', ?, 'x', 'owner', true)",
                u, tenant, "u-" + u + "@t.local");
        }
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "rp-" + store + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Hoodie', 'active')",
            product, tenant, store, "P-" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'L', 'HOOD-L')",
            variant, tenant, product, "V-" + variant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')", location, tenant);
        return new Shop(tenant, owner, colleague, store, location, variant, jwt.issueAccessToken(owner, tenant, "owner"));
    }

    /** A piece coming back (RTO, return_in_transit) on its own order with a packed allocation. */
    private String returningPiece(Shop s) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'returning'::order_status, 'Buyer', '01000000001', 'cod', now()) RETURNING id",
            UUID.class, s.tenant, s.store, "EXT-" + UUID.randomUUID(), "#" + (10000 + new Random().nextInt(89999)));
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) VALUES (?, ?, ?, ?, 'returning'::shipment_internal_state)",
            UUID.randomUUID(), s.tenant, order, String.valueOf(9_000_000_000L + new Random().nextInt(999_999_999)));
        return piece(s, "return_in_transit", order);
    }

    private String piece(Shop s, String status, UUID order) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, now())",
            id, s.tenant, s.variant, "PC-" + id, id, status, order);
        if (order != null) {
            UUID item = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)", item, s.tenant, order, s.variant);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", s.tenant, item, id);
        }
        return "PC-" + id;
    }

    private UUID pairing(UUID tenant, UUID user, boolean expired) {
        return jdbc.queryForObject(
            "INSERT INTO scan_pairings (tenant_id, station_device_id, station_user_id, pair_code_hash, device_secret_hash, " +
            "  pair_code_expires_at, claimed_at, expires_at) " +
            "VALUES (?, ?, ?, ?, ?, now() + interval '2 minutes', now(), " +
            "        now() + (CASE WHEN ? THEN interval '-1 minute' ELSE interval '12 hours' END)) RETURNING id",
            UUID.class, tenant, "dev" + UUID.randomUUID().toString().replace("-", ""), user,
            "pc-" + UUID.randomUUID(), "ds-" + UUID.randomUUID(), expired);
    }

    private long seq = 0;
    private UUID relay(UUID tenant, UUID pairing, String code) {
        return jdbc.queryForObject(
            "INSERT INTO scan_relay_events (tenant_id, pairing_id, seq, code, status) VALUES (?, ?, ?, ?, 'delivered') RETURNING id",
            UUID.class, tenant, pairing, ++seq, code);
    }

    private HttpHeaders auth(Shop s) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(s.token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Shop s, HttpMethod m, String path, Object body) {
        ResponseEntity<Map> r = rest.exchange("http://localhost:" + port + path, m, new HttpEntity<>(body, auth(s)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(path + " → " + r.getBody()).isTrue();
        return r.getBody();
    }

    private String openSession(Shop s) {
        return (String) call(s, HttpMethod.POST, "/api/v1/returns/sessions", Map.of()).get("sessionId");
    }

    private Map<String, Object> scan(Shop s, String session, String code, UUID relayEventId) {
        Map<String, Object> body = new HashMap<>();
        body.put("scan", code);
        body.put("locationId", s.location.toString());
        if (relayEventId != null) body.put("relayEventId", relayEventId.toString());
        return call(s, HttpMethod.POST, "/api/v1/returns/sessions/" + session + "/scan", body);
    }

    private String via(Shop s, String barcode) {
        List<String> v = jdbc.queryForList(
            "SELECT pe.metadata->>'via' FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.barcode = ? AND pe.tenant_id = ? AND pe.event_type = 'return_received' ORDER BY pe.id DESC LIMIT 1",
            String.class, barcode, s.tenant);
        return v.isEmpty() ? "NO_EVENT" : v.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> viaPhoneByBarcode(Map<String, Object> session) {
        Map<String, Boolean> out = new HashMap<>();
        for (Map<String, Object> i : (List<Map<String, Object>>) session.get("items")) {
            out.put((String) i.get("barcode"), (Boolean) i.get("via_phone"));
        }
        return out;
    }

    // ── the scan endpoint ────────────────────────────────────────────────────

    @Test
    void onlyAVerifiedRelayEventIsPhone_everythingElseIsHardware_neverRejected_andTheSessionCountsIt() {
        Shop a = shop("Ret A"), b = shop("Ret B");
        String session = openSession(a);
        UUID mine = pairing(a.tenant, a.owner, false);
        String p1 = returningPiece(a), p2 = returningPiece(a), p3 = returningPiece(a), p4 = returningPiece(a),
               p5 = returningPiece(a), p6 = returningPiece(a), p7 = returningPiece(a);

        scan(a, session, p1, relay(a.tenant, mine, " " + p1 + " "));                  // valid (spaces ignored)
        scan(a, session, p2, null);                                                    // keyboard
        scan(a, session, p3, relay(a.tenant, pairing(a.tenant, a.colleague, false), p3));  // another worker's pairing
        scan(a, session, p4, relay(a.tenant, mine, p5));                               // mismatched code
        scan(a, session, p5, UUID.randomUUID());                                       // unknown relay event
        scan(a, session, p6, relay(b.tenant, pairing(b.tenant, b.owner, false), p6));   // another tenant's event
        jdbc.update("UPDATE scan_pairings SET revoked_at = now(), revoked_reason = 'unpaired' WHERE id = ?", mine);
        scan(a, session, p7, relay(a.tenant, pairing(a.tenant, a.owner, true), p7));   // expired pairing

        assertThat(via(a, p1)).isEqualTo("phone");
        for (String p : List.of(p2, p3, p4, p5, p6, p7)) assertThat(via(a, p)).as(p).isNull();
        // The scan itself is unchanged: every piece went to inspection, return_kind as always.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pieces WHERE tenant_id = ? AND status = 'return_pending_inspection'",
            Integer.class, a.tenant)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT metadata->>'return_kind' FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.barcode = ? AND pe.event_type = 'return_received'", String.class, p1)).isEqualTo("rto");
        assertThat(jdbc.queryForObject("SELECT metadata->>'session_id' FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.barcode = ? AND pe.event_type = 'return_received'", String.class, p1)).isEqualTo(session);

        Map<String, Object> detail = call(a, HttpMethod.GET, "/api/v1/returns/sessions/" + session, null);
        assertThat(((Number) detail.get("phoneScanCount")).intValue()).isEqualTo(1);
        Map<String, Boolean> flags = viaPhoneByBarcode(detail);
        assertThat(flags.get(p1)).isTrue();
        for (String p : List.of(p2, p3, p4, p5, p6, p7)) assertThat(flags.get(p)).as(p).isFalse();

        // A re-scan (idempotent: the same item row) keeps it.
        assertThat(scan(a, session, p1, null).get("via_phone")).isEqualTo(true);
    }

    @Test
    void anIllegalStateScanFromAPhone_staysAsToday_noEvent_soNoMarker() {
        Shop a = shop("Ret illegal");
        String session = openSession(a);
        UUID mine = pairing(a.tenant, a.owner, false);
        String onShelf = piece(a, "available", null);
        Map<String, Object> item = scan(a, session, onShelf, relay(a.tenant, mine, onShelf));
        assertThat(item.get("unexpected")).isEqualTo(true);
        assertThat(item.get("via_phone")).isEqualTo(false);
        assertThat(via(a, onShelf)).isEqualTo("NO_EVENT");
    }

    // ── the return case detail ───────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void returnRequestDetail_marksTheItemReceivedThroughAPhoneScan() {
        Shop a = shop("Ret request");
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, '#7001', 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Customer', '01000000000', 'bosta') RETURNING id",
            UUID.class, a.tenant, a.store, "gid://shopify/Order/" + UUID.randomUUID());
        jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '60 days')",
            UUID.randomUUID(), a.tenant, order, String.valueOf(8_000_000_000L + new Random().nextInt(999_999_999)));
        String phonePiece = piece(a, "delivered", order), keyPiece = piece(a, "delivered", order);
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '60 days' WHERE tenant_id = ?", a.tenant);
        UUID request = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, decided_at) " +
            "VALUES (?, ?, 'approved'::return_request_status, 'RR-PHNXQ2', now()) RETURNING id", UUID.class, a.tenant, order);
        for (String p : List.of(phonePiece, keyPiece)) {
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) VALUES (?, ?, ?, ?, 'wrong_size')",
                a.tenant, request, p.substring(3), a.variant);
        }
        String session = openSession(a);
        scan(a, session, phonePiece, relay(a.tenant, pairing(a.tenant, a.owner, false), phonePiece));
        scan(a, session, keyPiece, null);

        Map<String, Object> detail = call(a, HttpMethod.GET, "/api/v1/return-requests/" + request, null);
        Map<String, Boolean> byPiece = new HashMap<>();
        for (Map<String, Object> i : (List<Map<String, Object>>) detail.get("items")) {
            byPiece.put((String) i.get("pieceId"), (Boolean) i.get("viaPhone"));
        }
        assertThat(byPiece.get(phonePiece.substring(3))).isTrue();
        assertThat(byPiece.get(keyPiece.substring(3))).isFalse();
        assertThat(jdbc.queryForObject("SELECT metadata->>'return_kind' FROM piece_events WHERE piece_id = ? AND event_type = 'return_received'",
            String.class, phonePiece.substring(3))).as("attribution unchanged").isEqualTo("request_return");
    }

    // ── app_user ─────────────────────────────────────────────────────────────

    @Test
    void asAppUser_theVerifierAndTheMarkerAreTenantIsolated_withSameTenantPositiveControl() {
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        DataSourceTransactionManager txm = new DataSourceTransactionManager(ds);
        JdbcTemplate appUser = new JdbcTemplate(ds);
        PhoneScanSource verifier = new PhoneScanSource(appUser, txm);
        TransactionTemplate tx = new TransactionTemplate(txm);

        Shop a = shop("Iso ret A"), b = shop("Iso ret B");
        String session = openSession(a);
        String p = returningPiece(a);
        UUID event = relay(a.tenant, pairing(a.tenant, a.owner, false), p);
        scan(a, session, p, event);

        assertThat(TenantContext.runAs(a.tenant, () -> verifier.isPhone(event, p, a.owner))).as("same tenant").isTrue();
        assertThat(TenantContext.runAs(b.tenant, () -> verifier.isPhone(event, p, a.owner))).as("other tenant").isFalse();
        String countSql = "SELECT COUNT(*) FROM piece_events WHERE event_type = 'return_received' AND metadata->>'via' = 'phone' " +
                          "AND metadata->>'session_id' = ?";
        Integer mineCount = TenantContext.runAs(a.tenant, () -> tx.execute(s -> appUser.queryForObject(countSql, Integer.class, session)));
        Integer theirCount = TenantContext.runAs(b.tenant, () -> tx.execute(s -> appUser.queryForObject(countSql, Integer.class, session)));
        assertThat(mineCount).as("same-tenant positive control").isEqualTo(1);
        assertThat(theirCount).as("tenant B sees none of A's phone scans").isZero();
    }
}
