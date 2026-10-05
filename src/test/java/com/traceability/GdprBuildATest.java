package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifySameShopGuard;
import com.traceability.integrations.shopify.ShopifySyncService;
import com.traceability.inventory.BlocklistService;
import com.traceability.notifications.EmailGateway;
import com.traceability.privacy.CustomerDataRequestService;
import com.traceability.privacy.CustomerRedaction;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * GDPR build A (V143). Every code path under test runs on an app_user connection (RLS ON, the tenant set
 * only through TenantContext) — never the BYPASSRLS datasource. Fixtures are seeded as postgres.
 *
 *   g1 — a redacted order is not refilled by an import upsert (non-null name/phone/address) nor by an
 *        orders/updated webhook (raw keeps no PII keys); a non-redacted order IS filled (positive control);
 *        a stored orders/* webhook for a redacted order lands stripped.
 *   g2 — customers/redact clears every listed store: orders, return requests (area snapshot stays),
 *        shipments.raw, exchanges.raw, unlinked_bosta_deliveries.raw, stored Shopify webhooks, data
 *        requests; another order of the tenant, the other tenant and the blocklist are untouched.
 *   g3 — after a redact, later Bosta writes (status refresh, upsert, a new shipment) stay stripped.
 *   g4 — shop/redact is tenant-wide and never crosses tenants.
 *   g5 — data_request: recorded once per event; the export holds the customer's data; another tenant
 *        sees nothing (list empty, export 404); expired → 410; the owner email has no customer data.
 *   g6 — HTTP: owner downloads; a manager gets 403; the other tenant's owner gets 404.
 *   g7 — customers/redact replaces the free-text reason of the customer's blocklist rows with "[redacted]";
 *        phone_canonical / source / created_by / created_at / active stay, and the block still holds an
 *        order with that phone. Another customer's row and another tenant's row keep their reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GdprBuildATest {

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

    static final String NAME = "Mona Customer";
    static final String STREET = "12 Customer Street, flat 4";
    static final String EMAIL = "mona.customer@example.com";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EncryptionService encryption;
    @Autowired BlocklistService blocklist;
    @Autowired ShopifySameShopGuard sameShopGuard;
    @Autowired com.traceability.account.AuditService auditService;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean EmailGateway emailGateway;

    record Tenant(UUID id, UUID store, UUID owner, String shop) {}
    record Order(UUID id, String number, String phone, String gid) {}

    Tenant a, b;
    JdbcTemplate appJdbc;
    TransactionTemplate appTx;
    DataSourceTransactionManager appTxm;
    ShopifySyncService appSync;
    CustomerDataRequestService appRequests;
    EmailGateway appEmail;

    @BeforeAll
    void setup() {
        a = tenant("GDPR A");
        b = tenant("GDPR B");
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager txm = new DataSourceTransactionManager(ds);
        appTxm = txm;
        appTx = new TransactionTemplate(txm);
        appSync = new ShopifySyncService(appJdbc, shopifyGateway, encryption, mapper, txm, blocklist, sameShopGuard, 30);
        appEmail = mock(EmailGateway.class);
        appRequests = new CustomerDataRequestService(appJdbc, appTx, appEmail, mapper, "https://app.example.test");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        reset(appEmail);
        for (Tenant t : List.of(a, b)) {
            jdbc.update("DELETE FROM customer_data_requests WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM blocklist WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
        }
    }

    // ── g1 ────────────────────────────────────────────────────────────────────

    @Test
    void g1_redactedOrder_notRefilled_byImportUpsert_norOrdersUpdatedWebhook() throws Exception {
        Order redacted = order(a, "#7001");
        Order live     = order(a, "#7002");
        jdbc.update("UPDATE orders SET customer_name = NULL, customer_phone = NULL, address = NULL, pii_source = NULL, " +
                    "pii_redacted_at = now(), raw = shopify_order_raw_redacted(raw) WHERE id = ?", redacted.id());
        jdbc.update("UPDATE orders SET customer_name = NULL, customer_phone = NULL, address = NULL, pii_source = NULL " +
                    "WHERE id = ?", live.id());

        // Import path (passes non-null name / phone / address into UPSERT_ORDER).
        for (Order o : List.of(redacted, live)) {
            ShopifyGateway.Order incoming = new ShopifyGateway.Order(o.gid(), o.number(), NAME, "+20" + o.phone().substring(1),
                mapper.createObjectNode().put("address1", STREET).put("city", "Cairo"), "paid", List.of(),
                new BigDecimal("100.00"), List.of(), Instant.now(), (JsonNode) shopifyPayload(o));
            TenantContext.runAs(a.id(), () -> appSync.ingestMissingOrder(a.store(), a.id(), incoming));
        }
        Map<String, Object> r = jdbc.queryForMap(
            "SELECT customer_name, customer_phone, address, jsonb_exists(raw, 'customer') AS c, jsonb_exists(raw, 'shipping_address') AS s, " +
            "       jsonb_exists(raw, 'email') AS e FROM orders WHERE id = ?", redacted.id());
        assertThat(r).containsEntry("customer_name", null).containsEntry("customer_phone", null)
            .containsEntry("address", null).containsEntry("c", false).containsEntry("s", false).containsEntry("e", false);
        assertThat(jdbc.queryForMap("SELECT customer_name, customer_phone, jsonb_exists(raw, 'customer') AS c FROM orders WHERE id = ?", live.id()))
            .as("positive control: a non-redacted order is filled by the same upsert")
            .containsEntry("customer_name", NAME).containsEntry("c", true);

        // orders/updated webhook path: raw = EXCLUDED.raw must not restore the stripped keys.
        TenantContext.runAs(a.id(), () -> appSync.ingestOrderWebhook(a.store(), a.id(), shopifyPayload(redacted)));
        assertThat(jdbc.queryForMap("SELECT customer_name, jsonb_exists(raw, 'customer') AS c, jsonb_exists(raw, 'shipping_address') AS s, " +
                                    "jsonb_exists(raw, 'billing_address') AS bl, jsonb_exists(raw, 'client_details') AS cd, raw ->> 'name' AS n " +
                                    "FROM orders WHERE id = ?", redacted.id()))
            .containsEntry("customer_name", null).containsEntry("c", false).containsEntry("s", false)
            .containsEntry("bl", false).containsEntry("cd", false).containsEntry("n", "#7001");

        // A stored orders/updated event for the redacted order lands stripped (V143 trigger), as app_user.
        String redactedJson = json(shopifyPayload(redacted)), liveJson = json(shopifyPayload(live));
        UUID ev = TenantContext.runAs(a.id(), () -> appTx.execute(s -> appJdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, 'orders/updated', ?, ?, ?::jsonb) RETURNING id",
            UUID.class, a.id(), a.shop(), "wh-" + UUID.randomUUID(), redactedJson)));
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload_raw, 'customer') FROM shopify_webhook_events WHERE id = ?",
            Boolean.class, ev)).isFalse();
        UUID evLive = TenantContext.runAs(a.id(), () -> appTx.execute(s -> appJdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, 'orders/updated', ?, ?, ?::jsonb) RETURNING id",
            UUID.class, a.id(), a.shop(), "wh-" + UUID.randomUUID(), liveJson)));
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload_raw, 'customer') FROM shopify_webhook_events WHERE id = ?",
            Boolean.class, evLive)).as("a live order's event is stored verbatim").isTrue();
    }

    // ── g2 + g3 ───────────────────────────────────────────────────────────────

    @Test
    void g2_customersRedact_clearsEveryListedStore_onlyForThatCustomer() throws Exception {
        Order gone = order(a, "#8001");
        Order kept = order(a, "#8002");
        Order other = order(b, "#8001");          // same number, other tenant
        Map<String, Object> f = stores(a, gone);
        Map<String, Object> keptStores = stores(a, kept);
        Map<String, Object> otherStores = stores(b, other);
        jdbc.update("INSERT INTO blocklist (tenant_id, phone_canonical, reason) VALUES (?, ?, 'fraud')", a.id(), gone.phone());

        CustomerRedaction.Result res = TenantContext.runAs(a.id(), () -> appTx.execute(s ->
            new CustomerRedaction(appJdbc).redactCustomer(a.id(), List.of(gone.gid()), "cust-" + gone.number(), null)));
        assertThat(res.orders()).isEqualTo(1);

        assertRedacted(gone, f);
        assertIntact(kept, keptStores);
        assertIntact(other, otherStores);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blocklist WHERE tenant_id = ? AND phone_canonical = ?",
            Integer.class, a.id(), gone.phone())).as("blocklist untouched in build A").isEqualTo(1);

        // g3 — later Bosta writes stay stripped (status refresh / ingest upsert / a brand-new leg).
        String bosta = json(bostaRaw(gone.phone()));
        TenantContext.runAs(a.id(), () -> appTx.execute(s -> {
            appJdbc.update("UPDATE shipments SET raw = ?::jsonb WHERE id = ?", bosta, f.get("shipment"));
            appJdbc.update("UPDATE exchanges SET raw = ?::jsonb WHERE id = ?", bosta, f.get("exchange"));
            appJdbc.update("UPDATE unlinked_bosta_deliveries SET raw = ?::jsonb WHERE id = ?", bosta, f.get("unlinked"));
            appJdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
                "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'return', ?::jsonb)",
                a.id(), gone.id(), tracking(), bosta);
            return null;
        }));
        for (String sql : List.of(
                "SELECT jsonb_exists(raw, 'receiver') FROM shipments WHERE order_id = ?",
                "SELECT jsonb_exists(raw, 'receiver') FROM exchanges WHERE matched_order_id = ?")) {
            assertThat(jdbc.queryForList(sql, Boolean.class, gone.id())).isNotEmpty().containsOnly(false);
        }
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(raw, 'receiver') FROM unlinked_bosta_deliveries WHERE id = ?",
            Boolean.class, f.get("unlinked"))).isFalse();
        assertThat(jdbc.queryForList("SELECT raw #>> '{dropOffAddress,firstLine}' FROM shipments WHERE order_id = ?",
            String.class, gone.id())).containsOnlyNulls();
        assertThat(jdbc.queryForList("SELECT raw #>> '{dropOffAddress,city,name}' FROM shipments WHERE order_id = ?",
            String.class, gone.id())).as("area names stay").containsOnly("Cairo");
    }

    @Test
    void g4_shopRedact_tenantWide_otherTenantUntouched() throws Exception {
        Order a1 = order(a, "#9001");
        Order a2 = order(a, "#9002");
        Order b1 = order(b, "#9001");
        Map<String, Object> fa1 = stores(a, a1);
        Map<String, Object> fa2 = stores(a, a2);
        Map<String, Object> fb1 = stores(b, b1);

        TenantContext.runAs(a.id(), () -> appTx.execute(s -> new CustomerRedaction(appJdbc).redactShop(a.id())));

        assertRedacted(a1, fa1);
        assertRedacted(a2, fa2);
        assertIntact(b1, fb1);
    }

    // ── g5 ────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void g5_dataRequest_recordedOnce_exportHasTheData_invisibleToOtherTenant_expires_emailHasNoCustomerData() throws Exception {
        Order o = order(a, "#6101");
        stores(a, o);
        order(a, "#6102");                         // another customer's order — must not appear
        jdbc.update("INSERT INTO blocklist (tenant_id, phone_canonical, reason) VALUES (?, ?, 'fraud')", a.id(), o.phone());
        String customerId = "7700112233";
        JsonNode payload = mapper.readTree("{\"shop_domain\":\"" + a.shop() + "\",\"orders_requested\":[" + numeric(o.gid()) + "]," +
            "\"customer\":{\"id\":" + customerId + ",\"email\":\"" + EMAIL + "\",\"phone\":\"+20" + o.phone().substring(1) + "\"}," +
            "\"data_request\":{\"id\":9999}}");
        UUID event = UUID.randomUUID();

        CustomerDataRequestService.Recorded first = TenantContext.runAs(a.id(), () -> appRequests.record(a.id(), event, a.shop(), payload));
        CustomerDataRequestService.Recorded again = TenantContext.runAs(a.id(), () -> appRequests.record(a.id(), event, a.shop(), payload));
        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.id()).isEqualTo(first.id());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM customer_data_requests WHERE id = ?", first.id());
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(v -> v.contains(EMAIL));
        assertThat(jdbc.queryForObject("SELECT expires_at - created_at BETWEEN interval '29 days 23 hours' AND interval '30 days 1 hour' " +
            "FROM customer_data_requests WHERE id = ?",
            Boolean.class, first.id())).isTrue();

        // Owner email: sent once, NO customer data.
        TenantContext.runAs(a.id(), () -> appRequests.notifyOwners(a.id(), first.id()));
        TenantContext.runAs(a.id(), () -> appRequests.notifyOwners(a.id(), first.id()));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(appEmail, times(1)).send(anyString(), subject.capture(), body.capture());
        for (String text : List.of(subject.getValue(), body.getValue())) {
            assertThat(text).doesNotContain(NAME).doesNotContain(o.phone()).doesNotContain(o.phone().substring(1))
                .doesNotContain(EMAIL).doesNotContain(customerId).doesNotContain("#6101").doesNotContain(STREET);
        }
        assertThat(body.getValue()).contains("Settings → Privacy");

        // Export as tenant A (app_user): the customer's data, nothing of the other order.
        CustomerDataRequestService.Export export = TenantContext.runAs(a.id(), () -> appRequests.export(a.id(), first.id(), a.owner()));
        Map<String, Object> doc = mapper.readValue(export.json(), Map.class);
        List<Map<String, Object>> orders = (List<Map<String, Object>>) doc.get("orders");
        assertThat(orders).extracting(m -> m.get("number")).containsExactly("#6101");
        assertThat(orders.get(0)).containsEntry("customer_name", NAME).containsEntry("customer_phone", o.phone());
        assertThat((Map<String, Object>) orders.get(0).get("shopify_customer_data")).containsKeys("customer", "shipping_address");
        assertThat((List<Map<String, Object>>) doc.get("return_requests")).singleElement()
            .satisfies(m -> assertThat(m).containsEntry("customer_email", EMAIL).containsEntry("custom_first_line", STREET));
        assertThat((List<Map<String, Object>>) doc.get("shipments")).singleElement()
            .satisfies(m -> assertThat((Map<String, Object>) m.get("customer_data")).containsKey("receiver"));
        assertThat((List<?>) doc.get("exchanges")).hasSize(1);
        assertThat((List<?>) doc.get("unlinked_bosta_deliveries")).hasSize(1);
        assertThat((List<Map<String, Object>>) doc.get("blocklist")).singleElement()
            .satisfies(m -> assertThat(m).containsEntry("phone_canonical", o.phone()));
        assertThat((List<?>) doc.get("shopify_webhook_payloads")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM customer_data_requests WHERE id = ?", String.class, first.id()))
            .isEqualTo("downloaded");

        // Tenant B, app_user + RLS: list is empty, the export is a 404 — even naming A's tenant id in the query.
        assertThat(TenantContext.runAs(b.id(), () -> appRequests.list(b.id()))).isEmpty();
        assertThat(TenantContext.runAs(b.id(), () -> appRequests.list(a.id()))).as("RLS, not just the WHERE").isEmpty();
        for (UUID asTenant : List.of(b.id(), a.id())) {
            assertThatThrownBy(() -> TenantContext.runAs(b.id(), () -> appRequests.export(asTenant, first.id(), b.owner())))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        }

        // Expired → 410.
        jdbc.update("UPDATE customer_data_requests SET created_at = now() - interval '31 days', " +
                    "expires_at = now() - interval '1 day' WHERE id = ?", first.id());
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appRequests.export(a.id(), first.id(), a.owner())))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(410));

        // A later customers/redact for that customer clears the request's phone.
        TenantContext.runAs(a.id(), () -> appTx.execute(s ->
            new CustomerRedaction(appJdbc).redactCustomer(a.id(), List.of(o.gid()), customerId, null)));
        assertThat(jdbc.queryForMap("SELECT customer_phone, pii_redacted_at IS NOT NULL AS r FROM customer_data_requests WHERE id = ?",
            first.id())).containsEntry("customer_phone", null).containsEntry("r", true);
    }

    // ── g6 ────────────────────────────────────────────────────────────────────

    @Test
    void g6_http_ownerOnly_otherTenant404() throws Exception {
        Order o = order(a, "#6201");
        UUID request = jdbc.queryForObject(
            "INSERT INTO customer_data_requests (tenant_id, webhook_event_id, shop_domain, shopify_customer_id, orders_requested) " +
            "VALUES (?, ?, ?, '42', ARRAY[?]::text[]) RETURNING id", UUID.class, a.id(), UUID.randomUUID(), a.shop(), o.gid());
        UUID manager = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Mgr', ?, ?, 'manager', true)",
            manager, a.id(), "mgr-" + manager + "@test.local", passwordEncoder.encode("pass123"));

        String path = "/api/v1/privacy/data-requests/" + request + "/export";
        ResponseEntity<String> owner = get(path, login(a.owner()));
        assertThat(owner.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(owner.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(get("/api/v1/privacy/data-requests", login(a.owner())).getBody()).contains(request.toString());
        assertThat(get(path, login(manager)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/privacy/data-requests", login(manager)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(path, login(b.owner())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/privacy/data-requests", login(b.owner())).getBody()).doesNotContain(request.toString());
    }

    // ── g7 ────────────────────────────────────────────────────────────────────

    @Test
    void g7_customersRedact_clearsBlocklistReason_blockStillHolds() throws Exception {
        Order gone  = order(a, "#7101");
        Order other = order(a, "#7102");
        Order theirs = order(b, "#7101");
        jdbc.update("UPDATE orders SET customer_phone = ? WHERE id = ?", gone.phone(), theirs.id());
        String reason = "Refused 3 deliveries — " + NAME + ", " + STREET;
        jdbc.update("INSERT INTO blocklist (tenant_id, phone_canonical, reason, source, created_by, created_at) " +
                    "VALUES (?, ?, ?, 'manual', ?, now() - interval '3 days')", a.id(), gone.phone(), reason, a.owner());
        jdbc.update("INSERT INTO blocklist (tenant_id, phone_canonical, reason) VALUES (?, ?, 'other customer note')",
                    a.id(), other.phone());
        jdbc.update("INSERT INTO blocklist (tenant_id, phone_canonical, reason) VALUES (?, ?, ?)", b.id(), gone.phone(), reason);
        Map<String, Object> before = jdbc.queryForMap(
            "SELECT phone_canonical, source::text AS source, created_by, created_at, active FROM blocklist " +
            "WHERE tenant_id = ? AND phone_canonical = ?", a.id(), gone.phone());

        CustomerRedaction.Result res = TenantContext.runAs(a.id(), () -> appTx.execute(s ->
            new CustomerRedaction(appJdbc).redactCustomer(a.id(), List.of(gone.gid()), "cust-7101", null)));
        assertThat(res.blocklist()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT reason FROM blocklist WHERE tenant_id = ? AND phone_canonical = ?",
            String.class, a.id(), gone.phone())).isEqualTo(CustomerRedaction.REDACTED_REASON);
        assertThat(jdbc.queryForMap(
            "SELECT phone_canonical, source::text AS source, created_by, created_at, active FROM blocklist " +
            "WHERE tenant_id = ? AND phone_canonical = ?", a.id(), gone.phone())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT reason FROM blocklist WHERE tenant_id = ? AND phone_canonical = ?",
            String.class, a.id(), other.phone())).isEqualTo("other customer note");
        assertThat(jdbc.queryForObject("SELECT reason FROM blocklist WHERE tenant_id = ?", String.class, b.id()))
            .as("other tenant untouched").isEqualTo(reason);

        // The block still works — the real gate, as app_user + RLS: a new order with that phone is held.
        BlocklistService appBlocklist = new BlocklistService(appJdbc, auditService, appTxm);
        Order next = order(a, "#7103");
        jdbc.update("UPDATE orders SET customer_phone = ?, on_hold = false WHERE id = ?", gone.phone(), next.id());
        TenantContext.runAs(a.id(), () -> appTx.execute(s -> {
            assertThat(appBlocklist.isBlocked(gone.phone(), a.id())).isEqualTo(CustomerRedaction.REDACTED_REASON);
            appBlocklist.checkAndHoldIfBlocked(next.id(), "+20" + gone.phone().substring(1), a.id());
            return null;
        }));
        assertThat(jdbc.queryForMap("SELECT on_hold, hold_reason FROM orders WHERE id = ?", next.id()))
            .containsEntry("on_hold", true).containsEntry("hold_reason", "blocked_customer: [redacted]");
    }

    // ── assertions ────────────────────────────────────────────────────────────

    private void assertRedacted(Order o, Map<String, Object> f) {
        assertThat(jdbc.queryForMap("SELECT customer_name, customer_phone, address, pii_redacted_at IS NOT NULL AS r, " +
                                    "jsonb_exists(raw, 'customer') AS c FROM orders WHERE id = ?", o.id()))
            .containsEntry("customer_name", null).containsEntry("customer_phone", null).containsEntry("address", null)
            .containsEntry("r", true).containsEntry("c", false);
        assertThat(jdbc.queryForMap("SELECT customer_email, customer_note, custom_first_line, pickup_district_name, " +
                                    "pickup_address_source FROM return_requests WHERE id = ?", f.get("request")))
            .containsEntry("customer_email", null).containsEntry("customer_note", null).containsEntry("custom_first_line", null)
            .as("area snapshot stays").containsEntry("pickup_district_name", "Smouha").containsEntry("pickup_address_source", "custom");
        for (String[] t : new String[][]{{"shipments", "shipment"}, {"exchanges", "exchange"}, {"unlinked_bosta_deliveries", "unlinked"}}) {
            assertThat(jdbc.queryForMap("SELECT jsonb_exists(raw, 'receiver') AS rc, raw #>> '{dropOffAddress,firstLine}' AS fl, " +
                                        "raw #>> '{dropOffAddress,city,name}' AS city, pii_redacted_at IS NOT NULL AS r " +
                                        "FROM " + t[0] + " WHERE id = ?", f.get(t[1])))
                .as(t[0]).containsEntry("rc", false).containsEntry("fl", null).containsEntry("city", "Cairo").containsEntry("r", true);
        }
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload_raw, 'customer') OR jsonb_exists(payload_raw, 'shipping_address') " +
                                       "FROM shopify_webhook_events WHERE id = ?", Boolean.class, f.get("event"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT payload_raw ->> 'name' FROM shopify_webhook_events WHERE id = ?",
            String.class, f.get("event"))).as("non-PII payload stays").isEqualTo(o.number());
    }

    private void assertIntact(Order o, Map<String, Object> f) {
        assertThat(jdbc.queryForMap("SELECT customer_name, customer_phone, pii_redacted_at, jsonb_exists(raw, 'customer') AS c FROM orders WHERE id = ?", o.id()))
            .containsEntry("customer_name", NAME).containsEntry("customer_phone", o.phone())
            .containsEntry("pii_redacted_at", null).containsEntry("c", true);
        assertThat(jdbc.queryForMap("SELECT customer_email, custom_first_line FROM return_requests WHERE id = ?", f.get("request")))
            .containsEntry("customer_email", EMAIL).containsEntry("custom_first_line", STREET);
        for (String[] t : new String[][]{{"shipments", "shipment"}, {"exchanges", "exchange"}, {"unlinked_bosta_deliveries", "unlinked"}}) {
            assertThat(jdbc.queryForMap("SELECT raw #>> '{receiver,fullName}' AS n, pii_redacted_at FROM " + t[0] + " WHERE id = ?",
                f.get(t[1]))).as(t[0]).containsEntry("n", NAME).containsEntry("pii_redacted_at", null);
        }
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload_raw, 'customer') FROM shopify_webhook_events WHERE id = ?",
            Boolean.class, f.get("event"))).isTrue();
    }

    // ── fixtures (seeded as postgres) ──────────────────────────────────────────

    private Tenant tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        String shop = "gdpr-" + id.toString().substring(0, 8) + ".myshopify.com";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, access_token_encrypted, access_token_expires_at) " +
                    "VALUES (?, ?, 'shopify', ?, 'connected', ?, now() + interval '876000 hours')",
            store, id, shop, encryption.encrypt("shpat_gdpr"));
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            owner, id, "owner-" + owner + "@test.local", passwordEncoder.encode("pass123"));
        return new Tenant(id, store, owner, shop);
    }

    private Order order(Tenant t, String number) throws Exception {
        String phone = "010" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        String gid = "gid://shopify/Order/" + ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        Order o = new Order(null, number, phone, gid);
        UUID id = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, address, pii_source, raw) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), ?, ?, " +
            "    jsonb_build_object('firstLine', ?::text, 'city', 'Cairo'), 'bosta', ?::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), gid, number, NAME, phone, STREET, json(shopifyPayload(o)));
        return new Order(id, number, phone, gid);
    }

    /** One row in every store of customer PII for this order; returns their ids. */
    private Map<String, Object> stores(Tenant t, Order o) throws Exception {
        String bosta = json(bostaRaw(o.phone()));
        UUID request = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, pickup_city_id, pickup_city_name, " +
            "    pickup_district_id, pickup_district_name, pickup_address_source, custom_first_line, customer_email, customer_note) " +
            "VALUES (?, ?, 'requested', ?, 'C1', 'Alexandria', 'D1', 'Smouha', 'custom', ?, ?, 'call first') RETURNING id",
            UUID.class, t.id(), o.id(), reference(), STREET, EMAIL);
        UUID shipment = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', ?::jsonb) RETURNING id",
            UUID.class, t.id(), o.id(), tracking(), bosta);
        UUID exchange = jdbc.queryForObject(
            "INSERT INTO exchanges (tenant_id, tracking_number, raw, matched_order_id) VALUES (?, ?, ?::jsonb, ?) RETURNING id",
            UUID.class, t.id(), tracking(), bosta, o.id());
        Long unlinked = jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "    bosta_order_type, raw) VALUES (?, ?, 'unrelated-ref', 24, 'SEND', ?::jsonb) RETURNING id",
            Long.class, t.id(), tracking(), bosta);
        UUID event = jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, 'orders/updated', ?, ?, ?::jsonb) RETURNING id",
            UUID.class, t.id(), t.shop(), "wh-" + UUID.randomUUID(), json(shopifyPayload(o)));
        return Map.of("request", request, "shipment", shipment, "exchange", exchange, "unlinked", unlinked, "event", event);
    }

    private JsonNode shopifyPayload(Order o) {
        var n = mapper.createObjectNode();
        n.put("admin_graphql_api_id", o.gid()).put("name", o.number()).put("email", EMAIL).put("phone", "+20" + o.phone().substring(1))
         .put("financial_status", "paid").put("current_total_price", "100.00").put("created_at", Instant.now().toString());
        n.putObject("customer").put("first_name", "Mona").put("last_name", "Customer").put("email", EMAIL);
        n.putObject("shipping_address").put("name", NAME).put("address1", STREET).put("phone", o.phone());
        n.putObject("billing_address").put("name", NAME);
        n.putObject("client_details").put("browser_ip", "10.0.0.1");
        n.putArray("line_items");
        return n;
    }

    private Map<String, Object> bostaRaw(String phone) {
        return Map.of(
            "type", Map.of("code", 10, "value", "Send"),
            "receiver", Map.of("fullName", NAME, "phone", "+20" + phone.substring(1)),
            "notes", "Ring twice — " + NAME,
            "dropOffAddress", Map.of("firstLine", STREET, "buildingNumber", "7", "city", Map.of("name", "Cairo"),
                                     "zone", Map.of("name", "Nasr City")));
    }

    private String json(Object o) throws Exception { return mapper.writeValueAsString(o); }

    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String numeric(String gid) { return gid.substring("gid://shopify/Order/".length()); }

    private static String tracking() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    private String login(UUID userId) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<TokenResponse> resp = rest.postForEntity("http://localhost:" + port + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }
}
