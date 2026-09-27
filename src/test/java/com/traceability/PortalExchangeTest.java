package com.traceability;

import com.traceability.inventory.UlidGenerator;
import com.traceability.inventory.VariantStockService;
import com.traceability.portal.PortalService;
import com.traceability.portal.PortalTokenService;
import com.traceability.portal.ReturnPickupBookingService;
import com.traceability.portal.ReturnRequestService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 5b — portal exchange requests (same product, another size / colour, same price) up to
 * approval. No Bosta booking, no Shopify write.
 *
 * L* lookup (exchangeOptions only when enabled; siblings only; inStock = VariantStockService;
 * option values from the title or the stored Shopify options), S* submit, A* approve /
 * switch-to-refund / no CRP booking, X* cross-tenant on a real app_user connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalExchangeTest {

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

    private static final String SLUG = "exchange-5b";
    private static final String SLUG_B = "exchange-5b-other";
    private static final String REF_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    @Autowired JdbcTemplate               jdbc;
    @Autowired PortalService              portal;
    @Autowired PortalTokenService         tokens;
    @Autowired ReturnRequestService       requests;
    @Autowired ReturnPickupBookingService booking;
    @Autowired org.springframework.transaction.PlatformTransactionManager txm;

    @MockBean JobScheduler jobScheduler;

    UUID tenantId, storeId, locationId, ownerId, product, whiteM, whiteL, whiteS, blackM, otherProductVariant;
    UUID tenantB, storeB, variantB, variantB2;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID(); locationId = UUID.randomUUID(); ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_exchanges_enabled) VALUES (?, 'Nour Studio', ?, true, true)",
            tenantId, SLUG);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            ownerId, tenantId, "o-" + ownerId + "@exchange.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'x5b.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", locationId, tenantId);
        product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-LIN', 'Linen Shirt', 'active')",
            product, tenantId, storeId);
        whiteM = variant(tenantId, product, "White / M");
        whiteL = variant(tenantId, product, "White / L");
        whiteS = variant(tenantId, product, "White / S");
        blackM = variant(tenantId, product, "Black / M");
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-PANTS', 'Flipped Pants', 'active')",
            other, tenantId, storeId);
        otherProductVariant = variant(tenantId, other, "Black / L");

        tenantB = UUID.randomUUID(); storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_exchanges_enabled) VALUES (?, 'Other', ?, true, true)",
            tenantB, SLUG_B);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'x5b-b.myshopify.com', 'disconnected')",
            storeB, tenantB);
        UUID productB = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-B', 'Tote', 'active')",
            productB, tenantB, storeB);
        variantB = variant(tenantB, productB, "Black");
        variantB2 = variant(tenantB, productB, "Sand");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID t : List.of(tenantId, tenantB)) {
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
        }
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = true, portal_auto_approve = false, portal_pickup_booking = false, " +
                    "portal_pickup_booking_since = NULL WHERE id = ?", tenantId);
        jdbc.update("UPDATE variants SET raw = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE products SET raw = NULL WHERE tenant_id = ?", tenantId);
    }

    // ── L: lookup ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_disabled_noExchangeKeysAnywhere() {
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = false WHERE id = ?", tenantId);
        Order o = deliveredOrder("#5001", "01055550001", whiteM);
        assertThat(portal.config(SLUG).orElseThrow()).doesNotContainKey("exchangesEnabled");
        Map<String, Object> line = firstLine(o);
        assertThat(line).doesNotContainKeys("exchangeOptions", "optionAxes", "currentOptions");
    }

    @Test
    @SuppressWarnings("unchecked")
    void l2_enabled_siblingsOnly_currentExcluded_inStockMatchesVariantStockService() {
        stock(whiteL, 2);                 // in stock
        stock(blackM, 1);                 // in stock
        // whiteS: nothing on hand → out of stock
        Order o = deliveredOrder("#5002", "01055550002", whiteM);

        assertThat(portal.config(SLUG).orElseThrow()).containsEntry("exchangesEnabled", true);
        Map<String, Object> line = firstLine(o);
        List<Map<String, Object>> options = (List<Map<String, Object>>) line.get("exchangeOptions");
        assertThat(options).extracting(m -> m.get("variantId"))
            .containsExactlyInAnyOrder(whiteL.toString(), whiteS.toString(), blackM.toString())
            .doesNotContain(whiteM.toString(), otherProductVariant.toString());

        VariantStockService svc = new VariantStockService(jdbc);
        Map<UUID, VariantStockService.VariantStock> all = TenantContext.runAs(tenantId,
            () -> new TransactionTemplate(txm).execute(s -> svc.computeAll()));
        for (Map<String, Object> opt : options) {
            UUID id = UUID.fromString((String) opt.get("variantId"));
            assertThat(opt.get("inStock")).as("inStock of %s", opt.get("title"))
                .isEqualTo(svc.forVariant(all, id).available() > 0);
        }
        Map<String, Object> whiteLOpt = options.stream().filter(m -> whiteL.toString().equals(m.get("variantId"))).findFirst().orElseThrow();
        assertThat(whiteLOpt.get("options")).isEqualTo(List.of("White", "L"));
        assertThat(line.get("currentOptions")).isEqualTo(List.of("White", "M"));
        assertThat((List<Map<String, Object>>) line.get("optionAxes")).extracting(m -> m.get("kind"))
            .containsExactly("colour", "size");
    }

    @Test
    @SuppressWarnings("unchecked")
    void l3_storedShopifyOptionsWinOverTheTitle() {
        jdbc.update("UPDATE products SET raw = '{\"options\":[{\"name\":\"Colour\"},{\"name\":\"Size\"}]}'::jsonb WHERE id = ?", product);
        jdbc.update("UPDATE variants SET raw = '{\"option1\":\"Off-white\",\"option2\":\"Medium\"}'::jsonb WHERE id = ?", whiteM);
        jdbc.update("UPDATE variants SET raw = '{\"option1\":\"Off-white\",\"option2\":\"Large\"}'::jsonb WHERE id = ?", whiteL);
        Order o = deliveredOrder("#5003", "01055550003", whiteM);
        Map<String, Object> line = firstLine(o);
        assertThat(line.get("currentOptions")).isEqualTo(List.of("Off-white", "Medium"));
        assertThat((List<Map<String, Object>>) line.get("optionAxes")).extracting(m -> m.get("name") + ":" + m.get("kind"))
            .containsExactly("Colour:colour", "Size:size");
    }

    // ── S: submit ───────────────────────────────────────────────────────────────

    @Test
    void s1_invalidExchangeSubmissions_allRejected() {
        stock(whiteL, 3);
        Order o = deliveredOrder("#5004", "01055550004", whiteM, whiteM);
        String token = tokens.issue(tenantId, o.id());
        PortalService.SubmitLine one = new PortalService.SubmitLine(whiteM, 1, "wrong_size");

        assertInvalid(token, exchange(List.of(one, one), whiteL));                                                      // two lines
        assertInvalid(token, exchange(List.of(new PortalService.SubmitLine(whiteM, 2, "wrong_size")), whiteL));          // quantity 2
        assertInvalid(token, exchange(List.of(one), otherProductVariant));                                              // other product
        assertInvalid(token, exchange(List.of(one), whiteM));                                                           // same variant
        assertInvalid(token, exchange(List.of(one), whiteS));                                                           // out of stock
        assertInvalid(token, exchange(List.of(new PortalService.SubmitLine(whiteM, 1, "nope")), whiteL));               // reason
        assertInvalid(token, exchange(List.of(one), null));                                                             // no replacement
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = false WHERE id = ?", tenantId);
        assertInvalid(token, exchange(List.of(one), whiteL));                                                           // disabled
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE tenant_id = ?", Integer.class, tenantId)).isZero();
    }

    @Test
    void s2_validExchange_storedAsRequested_fallbackKept_autoApproveIgnored() {
        jdbc.update("UPDATE tenants SET portal_auto_approve = true WHERE id = ?", tenantId);
        stock(whiteL, 1);
        Order o = deliveredOrder("#5005", "01055550005", whiteM);
        PortalService.SubmitResult res = portal.submit(SLUG, tokens.issue(tenantId, o.id()),
            exchange(List.of(new PortalService.SubmitLine(whiteM, 1, "wrong_size")), whiteL)).orElseThrow();

        assertThat(res.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        assertThat(res.body().get("status")).isEqualTo("requested");
        Map<String, Object> rr = jdbc.queryForMap("SELECT id, type, status::text AS status, refund_fallback_ok, decided_at FROM return_requests WHERE tenant_id = ?", tenantId);
        assertThat(rr.get("type")).isEqualTo("exchange");
        assertThat(rr.get("status")).as("exchanges wait for the merchant even with auto-approve").isEqualTo("requested");
        assertThat(rr.get("decided_at")).isNull();
        assertThat(rr.get("refund_fallback_ok")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT replacement_variant_id FROM return_request_items WHERE request_id = ?", UUID.class, rr.get("id")))
            .isEqualTo(whiteL);
        assertThat(jdbc.queryForObject("SELECT metadata->>'type' FROM return_request_events WHERE request_id = ? AND event_type = 'requested'",
            String.class, rr.get("id"))).isEqualTo("exchange");
    }

    @Test
    void s3_refundModeUnchanged() {
        Order o = deliveredOrder("#5006", "01055550006", whiteM);
        PortalService.SubmitResult res = portal.submit(SLUG, tokens.issue(tenantId, o.id()), new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(whiteM, 1, "changed_mind")), null, null)).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        Map<String, Object> rr = jdbc.queryForMap("SELECT id, type, refund_fallback_ok FROM return_requests WHERE tenant_id = ?", tenantId);
        assertThat(rr.get("type")).isEqualTo("refund");
        assertThat(rr.get("refund_fallback_ok")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT replacement_variant_id FROM return_request_items WHERE request_id = ?", UUID.class, rr.get("id")))
            .isNull();
    }

    // ── A: merchant approve / switch ────────────────────────────────────────────

    @Test
    void a1_approveInStock_approved_andNoCrpBooking() {
        jdbc.update("UPDATE tenants SET portal_pickup_booking = true, portal_pickup_booking_since = now() - interval '1 day' WHERE id = ?", tenantId);
        stock(whiteL, 1);
        UUID r = exchangeRequest(whiteM, whiteL, false);
        TenantContext.set(tenantId);
        requests.approve(r, ownerId);
        TenantContext.clear();
        assertThat(status(r)).isEqualTo("approved");
        assertThat(eventTypes(r)).endsWith("approved");

        // No Bosta CRP (type 25) for an exchange. Since Step 5c the booking job takes the exchange
        // path (type 30 — PortalExchangeBookingTest), which here stops at its preconditions: this
        // fixture has no Bosta account. No return leg exists either way.
        booking.book(r, tenantId);
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '5 minutes' WHERE id = ?", r);
        booking.sweepTenant(tenantId);
        assertThat(jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE id = ?", String.class, r)).isEqualTo("failed");
        assertThat(status(r)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND shipment_leg = 'return'",
            Integer.class, tenantId)).isZero();
    }

    @Test
    void a2_approveOutOfStock_409_staysRequested() {
        UUID r = exchangeRequest(whiteM, whiteS, true);   // whiteS has nothing on hand
        TenantContext.set(tenantId);
        assertThatThrownBy(() -> requests.approve(r, ownerId))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode().value()).isEqualTo(409);
                assertThat(e.getReason()).contains(ReturnRequestService.REPLACEMENT_OUT_OF_STOCK);
            });
        TenantContext.clear();
        assertThat(status(r)).isEqualTo("requested");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a3_switchToRefund_onlyWithTheFallbackFlag_convertsThenApproves() {
        UUID noFallback = exchangeRequest(whiteM, whiteS, false);
        UUID withFallback = exchangeRequest(whiteM, whiteS, true);
        TenantContext.set(tenantId);
        assertStatus(409, () -> requests.switchToRefundAndApprove(noFallback, ownerId));
        requests.switchToRefundAndApprove(withFallback, ownerId);
        Map<String, Object> d = requests.detail(withFallback);
        TenantContext.clear();

        assertThat(jdbc.queryForObject("SELECT type FROM return_requests WHERE id = ?", String.class, noFallback)).isEqualTo("exchange");
        assertThat(status(noFallback)).isEqualTo("requested");
        assertThat(jdbc.queryForObject("SELECT type FROM return_requests WHERE id = ?", String.class, withFallback)).isEqualTo("refund");
        assertThat(status(withFallback)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT replacement_variant_id FROM return_request_items WHERE request_id = ?", UUID.class, withFallback))
            .isNull();
        assertThat(eventTypes(withFallback)).containsSubsequence("switched_to_refund", "approved");
        assertThat(((List<Map<String, Object>>) d.get("items")).get(0).get("replacementVariantId")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void a4_detailCarriesLiveReplacementStock_andListCarriesType() {
        stock(whiteL, 3);
        UUID r = exchangeRequest(whiteM, whiteL, true);
        TenantContext.set(tenantId);
        Map<String, Object> d = requests.detail(r);
        Map<String, Object> item = ((List<Map<String, Object>>) d.get("items")).get(0);
        assertThat(d.get("type")).isEqualTo("exchange");
        assertThat(d.get("refundFallbackOk")).isEqualTo(true);
        assertThat(item.get("replacementVariantTitle")).isEqualTo("White / L");
        assertThat(item.get("replacementAvailable")).isEqualTo(3L);
        assertThat(item.get("replacementInStock")).isEqualTo(true);
        Map<String, Object> row = ((List<Map<String, Object>>) requests.list(null, 0, 50).get("items")).get(0);
        assertThat(row.get("type")).isEqualTo("exchange");
        TenantContext.clear();
    }

    // ── X: cross-tenant on a real app_user connection ─────────────────────────

    @Test
    void x1_approveAndSwitch_crossTenant404_sameTenantControlSucceeds() {
        stock(whiteL, 1);
        UUID mineApprove = exchangeRequest(whiteM, whiteL, false);
        UUID mineSwitch = exchangeRequest(whiteM, whiteS, true);
        UUID theirs = requestFor(tenantB, storeB, variantB, variantB2, true);

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        ReturnRequestService appRequests = new ReturnRequestService(appJdbc);
        TransactionTemplate appTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));

        assertStatus(404, () -> TenantContext.runAs(tenantId, () -> appTx.execute(s -> { appRequests.approve(theirs, ownerId); return null; })));
        assertStatus(404, () -> TenantContext.runAs(tenantId, () -> appTx.execute(s -> { appRequests.switchToRefundAndApprove(theirs, ownerId); return null; })));
        assertThat(jdbc.queryForObject("SELECT type || ':' || status FROM return_requests WHERE id = ?", String.class, theirs))
            .isEqualTo("exchange:requested");

        TenantContext.runAs(tenantId, () -> appTx.execute(s -> { appRequests.approve(mineApprove, ownerId); return null; }));
        TenantContext.runAs(tenantId, () -> appTx.execute(s -> { appRequests.switchToRefundAndApprove(mineSwitch, ownerId); return null; }));
        assertThat(status(mineApprove)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT type || ':' || status FROM return_requests WHERE id = ?", String.class, mineSwitch))
            .isEqualTo("refund:approved");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    record Order(UUID id, List<String> pieces) {}

    private UUID variant(UUID tenant, UUID productId, String title) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, productId, "V-" + v, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    /** {@code count} available pieces of the variant at the fulfillment location. */
    private void stock(UUID variant, int count) {
        for (int i = 0; i < count; i++) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
                id, tenantId, variant, "PC-" + id, id, locationId);
        }
    }

    private Order deliveredOrder(String number, String phone, UUID... variants) {
        return orderFor(tenantId, storeId, number, phone, variants);
    }

    private Order orderFor(UUID tenant, UUID store, String number, String phone, UUID... variants) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona', ?, 'bosta') RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '1 day')",
            tenant, order, String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
        List<String> ids = new ArrayList<>();
        for (UUID v : variants) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                id, tenant, v, "PC-" + id, id, order);
            UUID item = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)", item, tenant, order, v);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", tenant, item, id);
            ids.add(id);
        }
        return new Order(order, ids);
    }

    /** A requested exchange of one delivered piece of {@code from} for {@code to}. */
    private UUID exchangeRequest(UUID from, UUID to, boolean fallback) {
        return requestFor(tenantId, storeId, from, to, fallback);
    }

    private UUID requestFor(UUID tenant, UUID store, UUID from, UUID to, boolean fallback) {
        Order o = orderFor(tenant, store, "#" + ThreadLocalRandom.current().nextInt(10_000, 99_999), "01000000000", from);
        StringBuilder ref = new StringBuilder("RR-");
        for (int i = 0; i < 6; i++) ref.append(REF_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(REF_ALPHABET.length())));
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, refund_fallback_ok) " +
            "VALUES (?, ?, 'exchange', 'requested', ?, ?) RETURNING id", UUID.class, tenant, o.id(), ref.toString(), fallback);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, replacement_variant_id) " +
            "VALUES (?, ?, ?, ?, 'wrong_size', ?)", tenant, id, o.pieces().get(0), from, to);
        return id;
    }

    private static PortalService.SubmitRequest exchange(List<PortalService.SubmitLine> lines, UUID replacement) {
        return new PortalService.SubmitRequest(lines, null, null, null, "exchange", replacement, true);
    }

    private void assertInvalid(String token, PortalService.SubmitRequest req) {
        assertThat(portal.submit(SLUG, token, req).orElseThrow().outcome()).isEqualTo(PortalService.SubmitOutcome.INVALID);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstLine(Order o) {
        String number = jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, o.id());
        String phone = jdbc.queryForObject("SELECT customer_phone FROM orders WHERE id = ?", String.class, o.id());
        PortalService.LookupResult res = portal.lookup(SLUG, number, phone).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return ((List<Map<String, Object>>) res.body().get("lines")).get(0);
    }

    private String status(UUID r) {
        return jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, r);
    }

    private List<String> eventTypes(UUID r) {
        return jdbc.queryForList("SELECT event_type FROM return_request_events WHERE request_id = ? ORDER BY occurred_at, id", String.class, r);
    }

    private void assertStatus(int status, Runnable body) {
        assertThatThrownBy(body::run).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }
}
