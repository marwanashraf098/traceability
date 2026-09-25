package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalTokenService;
import com.traceability.security.EncryptionService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Returns portal Step 4a — public config + order lookup, hatch #14, tokens, delivered_at ingest.
 *
 * Fixtures: Bosta-linked forward leg with a bare numeric tracking number, orders.number with a
 * leading '#', customer phones stored in non-canonical forms ("+20 101 234 5678").
 * The cross-tenant RLS proof on a real app_user connection lives in PortalLookupRlsTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalLookupTest {

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

    static final String NOT_FOUND = "We couldn't find a returnable order with those details.";
    static final String RAW_API_KEY = "portal-test-api-key";

    @LocalServerPort int port;
    @Autowired TestRestTemplate     rest;
    @Autowired JdbcTemplate         jdbc;       // postgres — seeding/verification only
    @Autowired ObjectMapper         mapper;
    @Autowired PortalTokenService   tokens;
    @Autowired EncryptionService    encryptionService;
    @Autowired BostaWebhookJob      webhookJob;
    @Autowired BostaIngestionHelper ingestionHelper;

    @MockBean BostaGateway bostaGateway;
    @MockBean JobScheduler jobScheduler;

    UUID tenantA, tenantB, storeA, storeB, variantA, variantA2, variantB;

    @BeforeAll
    void setup() {
        tenantA = UUID.randomUUID(); tenantB = UUID.randomUUID();
        storeA = UUID.randomUUID();  storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, 'Snouts Store', 'snouts', true)", tenantA);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, 'Jumi Store', 'jumi', true)", tenantB);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (gen_random_uuid(), 'Off Store', 'offline-store', false)");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'p-a.myshopify.com', 'disconnected')", storeA, tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'p-b.myshopify.com', 'disconnected')", storeB, tenantB);
        variantA  = variant(tenantA, storeA, "Linen Shirt", "Sand / M", "https://cdn.shopify.com/s/files/1/linen.jpg");
        variantA2 = variant(tenantA, storeA, "Wool Scarf",  "Grey",     null);
        variantB  = variant(tenantB, storeB, "Other Shirt", "Blue",     null);
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'portal-hash', 'active')",
                    tenantA, encryptionService.encrypt(RAW_API_KEY));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID t : List.of(tenantA, tenantB)) {
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
        }
        jdbc.update("UPDATE variants SET non_returnable = false WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    // ── Hatch #14 ───────────────────────────────────────────────────────────

    @Test
    void hatch_resolvesEnabledSlug_nullForDisabledOrUnknown_caseInsensitive_asAppUser() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            assertThat(resolve(c, "snouts")).isEqualTo(tenantA);
            assertThat(resolve(c, "SNOUTS")).as("case-insensitive").isEqualTo(tenantA);
            assertThat(resolve(c, "offline-store")).as("disabled").isNull();
            assertThat(resolve(c, "no-such-store")).as("unknown").isNull();
        }
    }

    // ── Config ──────────────────────────────────────────────────────────────

    @Test
    void config_200ForEnabledSlug_404ForDisabledOrUnknown() {
        ResponseEntity<Map> ok = rest.getForEntity(url("/snouts/config"), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("storeName", "Snouts Store")
            .containsEntry("returnWindowDays", 30)
            .containsKeys("reasonCodes");
        @SuppressWarnings("unchecked")
        List<Object> reasonCodes = (List<Object>) ok.getBody().get("reasonCodes");
        assertThat(reasonCodes).containsExactly("wrong_size", "damaged", "not_as_pictured", "wrong_item", "changed_mind", "other");

        assertThat(rest.getForEntity(url("/offline-store/config"), String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity(url("/no-such-store/config"), String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Lookup ──────────────────────────────────────────────────────────────

    @Test
    void lookup_success_nonCanonicalStoredPhone_customerTypesNumberWithoutHash() throws Exception {
        UUID order = order(tenantA, storeA, "#1047", "+20 101 234 5678", false);
        forwardLeg(tenantA, order, Duration.ofDays(3));
        deliveredPiece(tenantA, order, variantA);

        ResponseEntity<Map> resp = lookup("snouts", "1047", "01012345678");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("orderNumber")).isEqualTo("#1047");
        assertThat(resp.getBody().get("deliveredAt")).isNotNull();
        String token = (String) resp.getBody().get("token");
        assertThat(tokens.verify(token, tenantA)).get()
            .satisfies(c -> assertThat(c.orderId()).isEqualTo(order));

        // Customer PII never appears in the response.
        String json = mapper.writeValueAsString(resp.getBody());
        assertThat(resp.getBody().keySet()).containsExactlyInAnyOrder("token", "orderNumber", "deliveredAt", "lines", "pickup");
        assertThat(json).doesNotContain("Mona Customer").doesNotContain("1012345678").doesNotContain("12 Customer St")
            .doesNotContain("customer").doesNotContain("phone").doesNotContain("address").doesNotContain("email");
        @SuppressWarnings("unchecked")
        Map<String, Object> line = ((List<Map<String, Object>>) resp.getBody().get("lines")).get(0);
        assertThat(line.keySet()).containsExactlyInAnyOrder("variantId", "productTitle", "variantTitle", "imageUrl",
            "deliveredQuantity", "returnableQuantity", "nonReturnable");
        assertThat(line.get("imageUrl")).isEqualTo("https://cdn.shopify.com/s/files/1/linen.jpg");

        // '#'-prefixed input and a +20 international-form phone also match.
        assertThat(lookup("snouts", "#1047", "+201012345678").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void lookup_everyFailure_isTheSameGeneric404() {
        UUID ok = order(tenantA, storeA, "#2001", "01011110001", false);
        forwardLeg(tenantA, ok, Duration.ofDays(2));

        UUID notDelivered = order(tenantA, storeA, "#2002", "01011110002", false);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', ?, 'with_courier'::shipment_internal_state, 'forward')",
                    tenantA, notDelivered, bareTracking());

        UUID noDeliveredAt = order(tenantA, storeA, "#2003", "01011110003", false);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward')",
                    tenantA, noDeliveredAt, bareTracking());

        UUID outside = order(tenantA, storeA, "#2004", "01011110004", false);
        forwardLeg(tenantA, outside, Duration.ofDays(31));

        UUID redacted = order(tenantA, storeA, "#2005", "01011110005", true);
        forwardLeg(tenantA, redacted, Duration.ofDays(2));

        List<ResponseEntity<Map>> failures = List.of(
            lookup("snouts", "2001", "01099999999"),   // wrong phone
            lookup("snouts", "2002", "01011110002"),   // not delivered
            lookup("snouts", "2003", "01011110003"),   // delivered but no delivered_at
            lookup("snouts", "2004", "01011110004"),   // outside the 30-day window
            lookup("snouts", "2005", "01011110005"),   // PII redacted
            lookup("snouts", "9999", "01011110001"),   // unknown order
            lookup("snouts", "2001", "not a phone"));  // unparseable phone
        for (ResponseEntity<Map> r : failures) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody()).isEqualTo(Map.of("message", NOT_FOUND));
        }
        assertThat(lookup("snouts", "2001", "01011110001").getStatusCode()).as("control").isEqualTo(HttpStatus.OK);

        assertThat(lookup("no-such-store", "2001", "01011110001").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(lookup("offline-store", "2001", "01011110001").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lookup_ambiguousNumber_disambiguatedByPhone() {
        UUID first  = order(tenantA, storeA, "#3001", "01022220001", false);
        UUID second = order(tenantA, storeA, "3001",  "01022220002", false);   // same key, different form
        forwardLeg(tenantA, first, Duration.ofDays(1));
        forwardLeg(tenantA, second, Duration.ofDays(1));

        ResponseEntity<Map> r = lookup("snouts", "3001", "01022220002");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("orderNumber")).isEqualTo("3001");
    }

    // ── Throttle ────────────────────────────────────────────────────────────

    @Test
    void throttle_fiveFailuresThen429_evenWithCorrectPhone_throttledCallsNotRecorded_liftsAfter60Min() {
        UUID order = order(tenantA, storeA, "#4001", "01033330001", false);
        forwardLeg(tenantA, order, Duration.ofDays(1));

        for (int i = 0; i < 5; i++) {
            assertThat(lookup("snouts", "4001", "01000000000").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        ResponseEntity<Map> sixth = lookup("snouts", "4001", "01033330001");
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getBody()).isEqualTo(Map.of("message", "Too many attempts. Please try again later."));
        // Retrying while locked out is also 429 and also not recorded.
        assertThat(lookup("snouts", "4001", "01033330001").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM portal_lookup_attempts WHERE tenant_id = ? AND order_key = '4001'",
            Integer.class, tenantA))
            .as("only the 5 real failures are recorded — throttled calls are not").isEqualTo(5);

        // Move the clock past the 60-minute mark: the 5 real failures age out, the lockout lifts.
        jdbc.update("UPDATE portal_lookup_attempts SET attempted_at = attempted_at - interval '61 minutes' " +
                    "WHERE tenant_id = ? AND order_key = '4001'", tenantA);
        assertThat(lookup("snouts", "4001", "01033330001").getStatusCode())
            .as("correct phone succeeds once the window has passed").isEqualTo(HttpStatus.OK);
    }

    @Test
    void throttle_failuresOlderThan60MinDontCount_andAttemptsAreTenantScoped() {
        UUID order = order(tenantA, storeA, "#4002", "01033330002", false);
        forwardLeg(tenantA, order, Duration.ofDays(1));
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO portal_lookup_attempts (tenant_id, order_key, attempted_at, success) " +
                        "VALUES (?, '4002', now() - interval '61 minutes', false)", tenantA);
            jdbc.update("INSERT INTO portal_lookup_attempts (tenant_id, order_key, attempted_at, success) " +
                        "VALUES (?, '4002', now(), false)", tenantB);   // other tenant, same key
        }

        assertThat(lookup("snouts", "4002", "01033330002").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Returnable quantities ───────────────────────────────────────────────

    @Test
    void lines_groupedByVariant_excludeActiveRequestPieces_zeroWhenNonReturnable() {
        UUID order = order(tenantA, storeA, "#5001", "01044440001", false);
        forwardLeg(tenantA, order, Duration.ofDays(1));
        String p1 = deliveredPiece(tenantA, order, variantA);
        deliveredPiece(tenantA, order, variantA);                    // 2 identical pieces
        deliveredPiece(tenantA, order, variantA2);
        String kept = deliveredPiece(tenantA, order, variantA);      // 3rd of variantA
        jdbc.update("UPDATE pieces SET status = 'return_pending_inspection' WHERE id = ?", kept); // not delivered any more

        UUID req = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, reference) VALUES (?, ?, 'RR-TEST2A') RETURNING id",
            UUID.class, tenantA, order);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) " +
                    "VALUES (?, ?, ?, ?, 'wrong_size')", tenantA, req, p1, variantA);
        jdbc.update("UPDATE variants SET non_returnable = true WHERE id = ?", variantA2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) lookup("snouts", "5001", "01044440001")
            .getBody().get("lines");
        assertThat(lines).hasSize(2);
        Map<String, Object> shirt = lines.stream().filter(l -> variantA.toString().equals(l.get("variantId"))).findFirst().orElseThrow();
        Map<String, Object> scarf = lines.stream().filter(l -> variantA2.toString().equals(l.get("variantId"))).findFirst().orElseThrow();
        assertThat(shirt.get("deliveredQuantity")).isEqualTo(2);
        assertThat(shirt.get("returnableQuantity")).as("one of two is in an active request").isEqualTo(1);
        assertThat(shirt.get("nonReturnable")).isEqualTo(false);
        assertThat(scarf.get("deliveredQuantity")).isEqualTo(1);
        assertThat(scarf.get("returnableQuantity")).isEqualTo(0);
        assertThat(scarf.get("nonReturnable")).isEqualTo(true);
    }

    // ── Token ───────────────────────────────────────────────────────────────

    @Test
    void token_validVerifies_tamperedExpiredWrongTenantFail() {
        UUID order = UUID.randomUUID();
        String token = tokens.issue(tenantA, order);
        assertThat(tokens.verify(token, tenantA)).isPresent();
        assertThat(tokens.verify(token, null)).isPresent();

        String[] parts = token.split("\\.");
        char c = parts[0].charAt(3);
        String tampered = parts[0].substring(0, 3) + (c == 'A' ? 'B' : 'A') + parts[0].substring(4) + "." + parts[1];
        assertThat(tokens.verify(tampered, tenantA)).as("tampered").isEmpty();
        assertThat(tokens.verify(token + "x", tenantA)).as("bad signature").isEmpty();
        assertThat(tokens.verify("garbage", tenantA)).isEmpty();

        assertThat(tokens.verify(token, tenantB)).as("wrong tenant").isEmpty();

        PortalTokenService past = new PortalTokenService("test-portal-token-secret-at-least-32-bytes!!", mapper,
            Clock.fixed(Instant.now().minus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        assertThat(tokens.verify(past.issue(tenantA, order), tenantA)).as("expired").isEmpty();

        PortalTokenService otherSecret = new PortalTokenService("a-completely-different-secret-of-32-bytes!!", mapper,
            Clock.systemUTC());
        assertThat(tokens.verify(otherSecret.issue(tenantA, order), tenantA)).as("other secret").isEmpty();
    }

    @Test
    void token_missingOrShortSecret_failsFast() {
        Assertions.assertThrows(IllegalStateException.class,
            () -> new PortalTokenService("", mapper, Clock.systemUTC()));
        Assertions.assertThrows(IllegalStateException.class,
            () -> new PortalTokenService("too-short", mapper, Clock.systemUTC()));
    }

    // ── delivered_at ingest ─────────────────────────────────────────────────

    @Test
    void deliveredAt_setOnceOnForwardLeg_neverOverwritten_returnLegUntouched() {
        UUID order = order(tenantA, storeA, "#6001", "01055550001", false);
        String fwd = bareTracking();
        ingest(fwd, 10, "SEND", 45, "#6001", "2026-09-20T10:00:00.000Z");
        java.sql.Timestamp first = deliveredAt(fwd);
        assertThat(first).as("set on the first 'delivered'").isNotNull();

        jdbc.update("UPDATE shipments SET delivered_at = now() - interval '5 days' WHERE tracking_number = ?", fwd);
        java.sql.Timestamp pinned = deliveredAt(fwd);
        // A later, genuinely new 'delivered' webhook (new updatedAt → new idem key; the poll
        // path's unchanged-state guard would skip it, so it arrives as a real webhook row).
        webhook(fwd, 45, "2026-09-21T10:00:00.000Z");
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? AND status = 'processed'",
            Integer.class, tenantA, fwd)).as("second delivered event really processed").isEqualTo(2);
        assertThat(deliveredAt(fwd)).as("never overwritten").isEqualTo(pinned);

        String crp = bareTracking();
        ingest(crp, 25, "CUSTOMER RETURN PICKUP", 45, "#6001", "2026-09-22T10:00:00.000Z");
        assertThat(jdbc.queryForObject("SELECT shipment_leg FROM shipments WHERE tracking_number = ?", String.class, crp))
            .isEqualTo("return");
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE tracking_number = ?", String.class, crp))
            .isEqualTo("delivered");
        assertThat(deliveredAt(crp)).as("return legs never get delivered_at").isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID variant(UUID tenant, UUID store, String product, String title, String image) {
        UUID productId = UUID.randomUUID(), variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, image_url) " +
                    "VALUES (?, ?, ?, ?, ?, 'active', ?)", productId, tenant, store, "P-" + productId, product, image);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
                    variantId, tenant, productId, "V-" + variantId, title, "SKU-" + variantId.toString().substring(0, 6));
        return variantId;
    }

    private UUID order(UUID tenant, UUID store, String number, String phone, boolean redacted) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, address, pii_source, pii_redacted_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona Customer', ?, " +
            "    '{\"firstLine\":\"12 Customer St\",\"city\":\"Giza\"}'::jsonb, 'bosta', CASE WHEN ? THEN now() END) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number, phone, redacted);
    }

    private void forwardLeg(UUID tenant, UUID order, Duration deliveredAgo) {
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, " +
                    "    created_at, delivered_at) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', " +
                    "    now() - (interval '1 second' * ?) - interval '1 day', now() - (interval '1 second' * ?))",
                    tenant, order, bareTracking(), deliveredAgo.toSeconds(), deliveredAgo.toSeconds());
    }

    private String deliveredPiece(UUID tenant, UUID order, UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                    "        'delivered'::piece_status, ?, now())",
                    id, tenant, variant, "PC-" + id, id, order);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                    item, tenant, order, variant);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                    tenant, item, id);
        return id;
    }

    private void ingest(String tracking, int typeCode, String type, int state, String businessRef, String updatedAt) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("_id", "d-" + tracking + "-" + state);
        raw.put("trackingNumber", tracking);
        raw.putObject("type").put("code", typeCode).put("value", typeCode == 25 ? "Customer Return Pickup" : "Send");
        raw.putObject("state").put("code", state);
        raw.put("businessReference", businessRef);
        raw.put("updatedAt", updatedAt);
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, state, type, 1, businessRef, null, raw));
        TenantContext.runAs(tenantA, () -> ingestionHelper.ingestDelivery(tenantA, RAW_API_KEY, tracking, "bosta_poll"));
        Long eventId = jdbc.queryForObject(
            "SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, tenantA, tracking);
        webhookJob.process(eventId, tenantA);
    }

    private void webhook(String tracking, int state, String updatedAt) {
        Long eventId = jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenantA,
            "{\"trackingNumber\":\"" + tracking + "\",\"state\":" + state + ",\"updatedAt\":\"" + updatedAt + "\"}");
        webhookJob.process(eventId, tenantA);
    }

    private java.sql.Timestamp deliveredAt(String tracking) {
        return jdbc.queryForObject("SELECT delivered_at FROM shipments WHERE tracking_number = ?",
            java.sql.Timestamp.class, tracking);
    }

    private static UUID resolve(Connection c, String slug) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT resolve_tenant_by_portal_slug(?)")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getObject(1, UUID.class); }
        }
    }

    private static String bareTracking() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    private String url(String path) { return "http://localhost:" + port + "/api/v1/portal" + path; }

    private ResponseEntity<Map> lookup(String slug, String orderNumber, String phone) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new HashMap<>();
        body.put("orderNumber", orderNumber);
        body.put("phone", phone);
        return rest.exchange(url("/" + slug + "/lookup"), HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }
}
