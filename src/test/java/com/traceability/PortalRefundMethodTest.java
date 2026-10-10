package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
import com.traceability.portal.RefundDetailsCipher;
import com.traceability.portal.RefundDetailsPurgeService;
import com.traceability.privacy.CustomerDataRequestService;
import com.traceability.privacy.CustomerRedaction;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns portal P2 — the refund method a customer chooses (V160).
 *   s1 no methods on → /config has no refundMethods; a refund needs none; a method sent anyway → refused
 *   s2 methods on → /config lists them in order; refund without one → refused; a method the store doesn't
 *      offer (switched off since lookup) → refused; a valid one → stored: method, hint, encrypted blob
 *      (no plaintext in the row)
 *   s3 plain exchange → not asked (a method sent → refused); exchange with the refund fallback → required
 *   d1 the detail payload carries method + hint + flags only; details never in the payload or return_refunds
 *   d2 refund-details: owner / manager get them (no-store); worker 403; another tenant 404 (positive control)
 *   a1 AAD: the ciphertext copied onto another request doesn't decrypt
 *   p1 purge: 30 days after refunded / closed / rejected → blob gone, purged_at, method + hint kept,
 *      endpoint 410; 29 days or still open → kept
 *   g1 customers/redact: blob + hint gone, method kept; export: decrypted details while held, null after
 *   l1 no detail ever reaches the logs (submit, endpoint, purge, export)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class PortalRefundMethodTest {

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

    static final String IBAN = "EG380019000500000000263180002";
    static final String WALLET = "01012344521";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PortalService portal;
    @Autowired RefundDetailsCipher cipher;
    @Autowired RefundDetailsPurgeService purge;
    @Autowired CustomerDataRequestService dataRequests;
    @Autowired ObjectMapper mapper;

    record T(UUID id, UUID store, UUID variant, UUID replacement, String slug, String owner, String manager, String worker,
             UUID ownerId) {}

    T a, b;

    @BeforeAll
    void setup() {
        a = tenant("Snouts P2", "snouts-p2");
        b = tenant("Jumi P2", "jumi-p2");
    }

    @AfterEach
    void reset() {
        for (T t : List.of(a, b)) {
            jdbc.update("UPDATE tenants SET portal_refund_methods = '{}', portal_exchanges_enabled = false WHERE id = ?", t.id());
        }
    }

    // ── s1 ───────────────────────────────────────────────────────────────────

    @Test
    void s1_noMethods_notAsked_methodSentAnywayRefused() {
        assertThat(config(a)).doesNotContainKey("refundMethods");
        Order o = order(a, "#S1A");
        assertThat(submit(a, o, null, null).outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        Order o2 = order(a, "#S1B");
        assertThat(submit(a, o2, "cash", null).outcome()).isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(jdbc.queryForObject("SELECT refund_method FROM return_requests WHERE order_id = ?", String.class, o.id())).isNull();
    }

    // ── s2 ───────────────────────────────────────────────────────────────────

    @Test
    void s2_methodsOn_required_onlyOffered_storedEncrypted() {
        methods(a, "wallet", "bank_transfer", "instapay");
        assertThat(config(a)).containsEntry("refundMethods", List.of("bank_transfer", "instapay", "wallet"));

        assertThat(submit(a, order(a, "#S2A"), null, null).outcome()).as("required").isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(submit(a, order(a, "#S2B"), "cash", null).outcome()).as("not offered").isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(submit(a, order(a, "#S2C"), "wallet", details("provider", "vodafone_cash", "walletNumber", "0150 123 45")).outcome())
            .as("bad number").isEqualTo(PortalService.SubmitOutcome.INVALID);

        // Switched off between lookup and submit → refused (re-checked at submit).
        Order late = order(a, "#S2D");
        String token = lookup(a, late);
        methods(a, "bank_transfer");
        assertThat(portal.submit(a.slug(), token, request(late, "wallet", wallet())).orElseThrow().outcome())
            .isEqualTo(PortalService.SubmitOutcome.INVALID);
        methods(a, "bank_transfer", "instapay", "wallet");

        Order o = order(a, "#S2E");
        assertThat(submit(a, o, "wallet", details("provider", "vodafone_cash", "walletNumber", "+20 10 1234 4521")).outcome())
            .isEqualTo(PortalService.SubmitOutcome.CREATED);
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT id, refund_method, refund_details_hint, refund_details_encrypted, refund_details_purged_at FROM return_requests " +
            "WHERE order_id = ?", o.id());
        assertThat(row).containsEntry("refund_method", "wallet").containsEntry("refund_details_hint", "••••4521")
            .containsEntry("refund_details_purged_at", null);
        String enc = (String) row.get("refund_details_encrypted");
        assertThat(enc).isNotBlank().doesNotContain(WALLET).doesNotContain("vodafone");
        assertThat(cipher.decrypt(a.id(), (UUID) row.get("id"), enc))
            .contains("\"walletNumber\":\"" + WALLET + "\"").contains("\"provider\":\"vodafone_cash\"");

        // Cash: method only, no blob, no hint.
        methods(a, "cash");
        Order c = order(a, "#S2F");
        assertThat(submit(a, c, "cash", null).outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        assertThat(jdbc.queryForMap("SELECT refund_method, refund_details_encrypted, refund_details_hint FROM return_requests " +
            "WHERE order_id = ?", c.id())).containsEntry("refund_method", "cash")
            .containsEntry("refund_details_encrypted", null).containsEntry("refund_details_hint", null);
    }

    // ── s3 ───────────────────────────────────────────────────────────────────

    @Test
    void s3_plainExchangeNotAsked_fallbackExchangeRequired() {
        methods(a, "instapay");
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = true WHERE id = ?", a.id());

        Order plain = order(a, "#S3A");
        assertThat(exchange(a, plain, false, "instapay", details("instapay", "ahmed.k@instapay")).outcome())
            .as("plain exchange: a method sent is refused").isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(exchange(a, plain, false, null, null).outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);

        Order fallback = order(a, "#S3B");
        assertThat(exchange(a, fallback, true, null, null).outcome()).as("fallback: required").isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(exchange(a, fallback, true, "instapay", details("instapay", "Ahmed.K@instapay")).outcome())
            .isEqualTo(PortalService.SubmitOutcome.CREATED);
        assertThat(jdbc.queryForMap("SELECT type, refund_method, refund_details_hint FROM return_requests WHERE order_id = ?",
            fallback.id())).containsEntry("type", "exchange").containsEntry("refund_method", "instapay")
            .containsEntry("refund_details_hint", "••••ed.k");
    }

    // ── d1 ───────────────────────────────────────────────────────────────────

    @Test
    void d1_detailCarriesMethodAndHintOnly_neverInRefundLedger() {
        methods(a, "bank_transfer");
        UUID id = bankRequest(a, "#D1A");

        ResponseEntity<String> detail = get("/api/v1/return-requests/" + id, a.owner(), String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).contains("\"refundMethod\":\"bank_transfer\"").contains("\"refundHint\":\"••••0002\"")
            .contains("\"refundDetailsAvailable\":true")
            .doesNotContain(IBAN).doesNotContain("Mona Adel").doesNotContain("CIB Bank");

        // Move it to refund_pending, record a refund prefilled with the customer's method.
        jdbc.update("UPDATE return_requests SET status = 'refund_pending', refund_pending_at = now() WHERE id = ?", id);
        ResponseEntity<String> refund = rest.exchange(base() + "/api/v1/return-requests/" + id + "/refunds", HttpMethod.POST,
            new HttpEntity<>(Map.of("method", "bank_transfer", "amount", "450.00", "refundedOn", "2026-10-10",
                "reference", "TRX-1"), auth(a.owner())), String.class);
        assertThat(refund.getStatusCode().is2xxSuccessful()).as(String.valueOf(refund.getBody())).isTrue();
        String ledger = String.join("|", jdbc.queryForList(
            "SELECT row_to_json(r)::text FROM return_refunds r WHERE request_id = ?", String.class, id));
        assertThat(ledger).contains("bank_transfer").doesNotContain(IBAN).doesNotContain("Mona Adel").doesNotContain("CIB Bank");
    }

    // ── d2 ───────────────────────────────────────────────────────────────────

    @Test
    void d2_refundDetails_ownerManager_noStore_workerDenied_crossTenant404() {
        methods(a, "bank_transfer");
        UUID id = bankRequest(a, "#D2A");

        ResponseEntity<Map> own = get("/api/v1/return-requests/" + id + "/refund-details", a.owner(), Map.class);
        assertThat(own.getStatusCode()).as("same-tenant positive control").isEqualTo(HttpStatus.OK);
        assertThat(own.getBody()).containsEntry("method", "bank_transfer").containsEntry("account", IBAN)
            .containsEntry("holderName", "Mona Adel").containsEntry("bankName", "CIB Bank");
        assertThat(own.getHeaders().getCacheControl()).contains("no-store");
        assertThat(get("/api/v1/return-requests/" + id + "/refund-details", a.manager(), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/return-requests/" + id + "/refund-details", a.worker(), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> other = get("/api/v1/return-requests/" + id + "/refund-details", b.owner(), String.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(other.getBody())).doesNotContain(IBAN);
    }

    // ── a1 ───────────────────────────────────────────────────────────────────

    @Test
    void a1_aad_ciphertextCopiedToAnotherRequest_doesNotDecrypt() {
        methods(a, "bank_transfer");
        UUID source = bankRequest(a, "#A1A");
        UUID target = bankRequest(a, "#A1B");
        String enc = jdbc.queryForObject("SELECT refund_details_encrypted FROM return_requests WHERE id = ?", String.class, source);

        assertThat(cipher.decrypt(a.id(), source, enc)).contains(IBAN);   // positive control
        assertThatThrownBy(() -> cipher.decrypt(a.id(), target, enc)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(b.id(), source, enc)).isInstanceOf(IllegalStateException.class);

        jdbc.update("UPDATE return_requests SET refund_details_encrypted = ? WHERE id = ?", enc, target);
        assertThat(get("/api/v1/return-requests/" + target + "/refund-details", a.owner(), String.class).getStatusCode())
            .as("a copied blob never reveals another request's details").isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ── p1 ───────────────────────────────────────────────────────────────────

    @Test
    void p1_purge_30DaysAfterTheRequestEnded() {
        methods(a, "bank_transfer");
        UUID refunded31 = bankRequest(a, "#P1A"), refunded29 = bankRequest(a, "#P1B"), open = bankRequest(a, "#P1C");
        UUID closed31 = bankRequest(a, "#P1D"), rejected31 = bankRequest(a, "#P1E");
        jdbc.update("UPDATE return_requests SET status = 'refunded', refunded_at = now() - interval '31 days' WHERE id = ?", refunded31);
        jdbc.update("UPDATE return_requests SET status = 'refunded', refunded_at = now() - interval '29 days' WHERE id = ?", refunded29);
        jdbc.update("UPDATE return_requests SET status = 'closed', closed_at = now() - interval '31 days', close_reason = 'other' " +
            "WHERE id = ?", closed31);
        jdbc.update("UPDATE return_requests SET status = 'rejected', decided_at = now() - interval '31 days' WHERE id = ?", rejected31);
        jdbc.update("UPDATE return_requests SET created_at = now() - interval '90 days' WHERE id = ?", open);

        assertThat(purge.purgeTenant(a.id())).isEqualTo(3);
        for (UUID id : List.of(refunded31, closed31, rejected31)) {
            assertThat(jdbc.queryForMap("SELECT refund_method, refund_details_hint, refund_details_encrypted, " +
                "refund_details_purged_at IS NOT NULL AS purged FROM return_requests WHERE id = ?", id))
                .containsEntry("refund_method", "bank_transfer").containsEntry("refund_details_hint", "••••0002")
                .containsEntry("refund_details_encrypted", null).containsEntry("purged", true);
        }
        for (UUID id : List.of(refunded29, open)) {
            assertThat(jdbc.queryForObject("SELECT refund_details_encrypted FROM return_requests WHERE id = ?", String.class, id))
                .isNotNull();
        }
        assertThat(get("/api/v1/return-requests/" + refunded31 + "/refund-details", a.owner(), String.class).getStatusCode())
            .isEqualTo(HttpStatus.GONE);
        assertThat(get("/api/v1/return-requests/" + refunded31, a.owner(), String.class).getBody())
            .contains("\"refundDetailsAvailable\":false").contains("\"refundHint\":\"••••0002\"");
        assertThat(purge.purgeTenant(a.id())).as("idempotent").isZero();
    }

    // ── g1 ───────────────────────────────────────────────────────────────────

    @Test
    void g1_redactAndExport() throws Exception {
        methods(a, "bank_transfer");
        Order kept = order(a, "#G1A");
        assertThat(submit(a, kept, "bank_transfer", bank()).outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);

        // Export (held): decrypted details.
        UUID dr = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_data_requests (id, tenant_id, webhook_event_id, shop_domain, orders_requested) " +
            "VALUES (?, ?, ?, 'snouts-p2.myshopify.com', ?::text[])", dr, a.id(), UUID.randomUUID(), new String[]{kept.gid()});
        JsonNode export = mapper.readTree(TenantContext.runAs(a.id(), () -> dataRequests.export(a.id(), dr, a.ownerId())).json());
        JsonNode req = export.path("return_requests").get(0);
        assertThat(req.path("refund_method").asText()).isEqualTo("bank_transfer");
        assertThat(req.path("refund_details").path("account").asText()).isEqualTo(IBAN);
        assertThat(req.has("refund_details_encrypted")).isFalse();

        // customers/redact: blob and hint gone, method kept.
        TenantContext.runAs(a.id(), () -> new CustomerRedaction(jdbc).redactCustomer(a.id(), List.of(kept.gid()), null, null));
        assertThat(jdbc.queryForMap("SELECT refund_method, refund_details_hint, refund_details_encrypted, " +
            "refund_details_purged_at IS NOT NULL AS purged FROM return_requests WHERE order_id = ?", kept.id()))
            .containsEntry("refund_method", "bank_transfer").containsEntry("refund_details_hint", null)
            .containsEntry("refund_details_encrypted", null).containsEntry("purged", true);

        // Export after: no details.
        UUID dr2 = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_data_requests (id, tenant_id, webhook_event_id, shop_domain, orders_requested) " +
            "VALUES (?, ?, ?, 'snouts-p2.myshopify.com', ?::text[])", dr2, a.id(), UUID.randomUUID(), new String[]{kept.gid()});
        JsonNode after = mapper.readTree(TenantContext.runAs(a.id(), () -> dataRequests.export(a.id(), dr2, a.ownerId())).json());
        assertThat(after.path("return_requests").get(0).path("refund_details").isNull()).isTrue();

        // shop/redact: tenant-wide.
        methods(b, "instapay");
        Order bo = order(b, "#G1B");
        assertThat(submit(b, bo, "instapay", details("instapay", "01012344521")).outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        TenantContext.runAs(b.id(), () -> new CustomerRedaction(jdbc).redactShop(b.id()));
        assertThat(jdbc.queryForMap("SELECT refund_details_hint, refund_details_encrypted FROM return_requests WHERE order_id = ?",
            bo.id())).containsEntry("refund_details_hint", null).containsEntry("refund_details_encrypted", null);
    }

    // ── l1 ───────────────────────────────────────────────────────────────────

    @Test
    void l1_detailsNeverLogged(CapturedOutput output) {
        methods(a, "bank_transfer");
        UUID id = bankRequest(a, "#L1A");
        get("/api/v1/return-requests/" + id + "/refund-details", a.owner(), Map.class);
        get("/api/v1/return-requests/" + id, a.owner(), String.class);
        jdbc.update("UPDATE return_requests SET status = 'refunded', refunded_at = now() - interval '40 days' WHERE id = ?", id);
        purge.purgeTenant(a.id());
        assertThat(output.getAll()).doesNotContain(IBAN).doesNotContain("Mona Adel").doesNotContain("CIB Bank")
            .doesNotContain("0019 0005");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    record Order(UUID id, String number, String phone, String gid, UUID orderItem) {}

    private UUID bankRequest(T t, String number) {
        Order o = order(t, number);
        assertThat(submit(t, o, "bank_transfer", bank()).outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ?", UUID.class, o.id());
    }

    private ObjectNode bank() {
        return details("holderName", "Mona Adel", "bankName", "CIB Bank", "account", "EG38 0019 0005 0000 0000 2631 8000 2");
    }

    private ObjectNode wallet() {
        return details("provider", "we_pay", "walletNumber", WALLET);
    }

    private ObjectNode details(String... kv) {
        ObjectNode n = mapper.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) n.put(kv[i], kv[i + 1]);
        return n;
    }

    private void methods(T t, String... m) {
        jdbc.update("UPDATE tenants SET portal_refund_methods = ?::text[] WHERE id = ?", m, t.id());
    }

    private Map<String, Object> config(T t) {
        return portal.config(t.slug()).orElseThrow();
    }

    private String lookup(T t, Order o) {
        PortalService.LookupResult r = portal.lookup(t.slug(), o.number(), o.phone()).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return (String) r.body().get("token");
    }

    private PortalService.SubmitRequest request(Order o, String method, JsonNode details) {
        return new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(null, 1, "wrong_size", o.orderItem())),
            null, null, null, null, null, null, null, null, method, details);
    }

    private PortalService.SubmitResult submit(T t, Order o, String method, JsonNode details) {
        return portal.submit(t.slug(), lookup(t, o), request(o, method, details)).orElseThrow();
    }

    private PortalService.SubmitResult exchange(T t, Order o, boolean fallback, String method, JsonNode details) {
        PortalService.SubmitRequest req = new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(null, 1, "wrong_size", o.orderItem())),
            null, null, null, "exchange", t.replacement(), fallback, null, null, method, details);
        return portal.submit(t.slug(), lookup(t, o), req).orElseThrow();
    }

    /** A delivered order with one UNTRACKED line of the tenant's variant (no pieces needed). */
    private Order order(T t, String number) {
        String phone = "010" + (10_000_000 + new Random().nextInt(89_999_999));
        String gid = "gid://shopify/Order/" + UUID.randomUUID();
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_phone) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), ?) RETURNING id",
            UUID.class, t.id(), t.store(), gid, number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '2 days')",
            t.id(), order, String.valueOf(4_200_000_000L + Math.abs(order.getLeastSignificantBits() % 1_000_000_000L)));
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.id(), order, t.variant());
        return new Order(order, number, phone, gid, item);
    }

    private T tenant(String name, String slug) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", id, name, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID p = UUID.randomUUID(), v = UUID.randomUUID(), r = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            p, id, store, "P-" + p);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'White / M', ?)",
            v, id, p, "V-" + v, "SKU-" + v.toString().substring(0, 6));
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'White / L', ?)",
            r, id, p, "V-" + r, "SKU-" + r.toString().substring(0, 6));
        for (int i = 0; i < 4; i++) {   // the exchange replacement is in stock
            String piece = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
                piece, id, r, "PC-" + piece, piece, location);
        }
        UUID ownerId = user(id, "owner");
        return new T(id, store, v, r, slug, login(ownerId), login(user(id, "manager")), login(user(id, "worker")), ownerId);
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@test.local", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private <X> ResponseEntity<X> get(String path, String token, Class<X> type) {
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(auth(token)), type);
    }

    private String login(UUID userId) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), auth("x")), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }
}
