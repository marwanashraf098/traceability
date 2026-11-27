package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.MainReconcileService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar U1d — Keep showing online ('leave') only while Main reconcile WRITES for the tenant's store
 * (MainReconcileService.writesFor: enabled, the store in the rollout, report-only off, a Part 1 cutoff, in write-shops and
 * not in write-exclude). Otherwise a Keep bazar's sales would never come off Shopify.
 * <ul>
 *   <li>f — the flag: GET /bazar-events/online-stock and the bazar detail carry keep_available, true for a write store,
 *       false for an excluded / report-only / disabled one or a tenant with no connected store.</li>
 *   <li>e — an excluded store: 409 BAZAR_KEEP_UNAVAILABLE on create with Keep, on changing to Keep, and on Mark sent of a
 *       location already on Keep; Remove works; a Keep bazar sent before the store was excluded is not touched.</li>
 *   <li>r — report-only: the same 409 on set.</li>
 *   <li>n — a write store: Keep is created, changed and sent as before.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarKeepGuardTest {

    static final String WRITE_SHOP = "keep-write.myshopify.com", EXCLUDED_SHOP = "keep-excluded.myshopify.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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
        // The prod shape: writes for every store except the excluded one.
        r.add("traced.main-reconcile.enabled", () -> "true");
        r.add("traced.main-reconcile.report-only", () -> "false");
        r.add("traced.main-reconcile.write-shops", () -> "all");
        r.add("traced.main-reconcile.write-exclude", () -> EXCLUDED_SHOP);
        r.add("traced.main-reconcile.part1-since", () -> "2026-11-04T00:00:00Z");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired PasswordEncoder  passwordEncoder;
    @Autowired MainReconcileService reconcile;

    UUID writeTenant, excludedTenant, noStoreTenant, writeMain, excludedMain;
    String writeOwner, excludedOwner, noStoreOwner;
    int seq = 0, pieceSeq = 0;

    @BeforeAll
    void setup() {
        writeTenant = UUID.randomUUID();
        excludedTenant = UUID.randomUUID();
        noStoreTenant = UUID.randomUUID();
        writeMain = tenant(writeTenant, "Keep Write", WRITE_SHOP);
        excludedMain = tenant(excludedTenant, "Keep Excluded", EXCLUDED_SHOP);
        tenant(noStoreTenant, "Keep No Store", null);
        writeOwner = login(user(writeTenant));
        excludedOwner = login(user(excludedTenant));
        noStoreOwner = login(user(noStoreTenant));
    }

    @AfterEach
    void restoreConfig() {
        reconcile.setEnabled(true);
        reconcile.setReportOnly(false);
        reconcile.setWriteExclude(EXCLUDED_SHOP);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private UUID tenant(UUID id, String name, String shop) {
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, ?, true)", id, name);
        UUID main = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, id);
        if (shop != null) {
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
                UUID.randomUUID(), id, shop);
        }
        return main;
    }

    private String user(UUID t) {
        String email = "owner-" + UUID.randomUUID() + "@u1d.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, 'owner'::user_role, true)",
            UUID.randomUUID(), t, "U1d owner", email, passwordEncoder.encode("pass123"));
        return email;
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> r = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().accessToken();
    }

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), Map.class);
    }

    private String ev(String path) { return "/api/v1/bazar-events/" + path; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> create(String token, String mode) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "U1d Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "U1d Hall " + seq));
        if (mode != null) body.put("shopifySyncMode", mode);
        return call(token, HttpMethod.POST, "/api/v1/bazar-events", body);
    }

    @SuppressWarnings("rawtypes")
    private String draft(String token, String mode) {
        ResponseEntity<Map> r = create(token, mode);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    /** A labelled piece of a fresh variant at the tenant's Main, scanned out onto the bazar's send. */
    @SuppressWarnings("rawtypes")
    private void scanOnePiece(String token, UUID tenant, UUID main, String e) {
        UUID store = jdbc.queryForObject("SELECT id FROM stores WHERE tenant_id = ?", UUID.class, tenant);
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, 'Keep shirt', 'active', '{}'::jsonb)", product, tenant, store, "P-" + product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price, raw) " +
            "VALUES (?, ?, ?, ?, 'M', 500, '{}'::jsonb)", variant, tenant, product, "V-" + variant);
        String id = UlidGenerator.generate(), code = String.format("K%07d", 7300000 + ++pieceSeq);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available', ?)", id, tenant, variant, "PC-" + id, code, main);
        ResponseEntity<Map> r = call(token, HttpMethod.POST, ev(e + "/scan-out"), Map.of("barcode", code));
        assertThat(r.getBody().get("success")).as(String.valueOf(r.getBody())).isEqualTo(true);
    }

    @SuppressWarnings("rawtypes")
    private static void assertKeepUnavailable(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_KEEP_UNAVAILABLE");
        assertThat(String.valueOf(r.getBody().get("message_en"))).contains("isn't available for your store yet")
            .contains("Remove from online stock");
        assertThat(String.valueOf(r.getBody().get("message_ar"))).contains("غير متاح لمتجرك بعد");
    }

    private String locationMode(String e) {
        return jdbc.queryForObject("SELECT l.shopify_sync_mode FROM bazar_events b JOIN locations l ON l.id = b.location_id " +
            "WHERE b.id = ?::uuid", String.class, e);
    }

    private String transferStatus(String e) {
        return jdbc.queryForObject("SELECT t.status FROM bazar_events b JOIN transfers t ON t.id = b.transfer_id " +
            "WHERE b.id = ?::uuid", String.class, e);
    }

    private Object keepFlag(String token) {
        return call(token, HttpMethod.GET, "/api/v1/bazar-events/online-stock", null).getBody().get("keep_available");
    }

    // ── f: the flag ─────────────────────────────────────────────────────────

    @Test
    void f1_flag_writeStoreTrue_excludedFalse_noStoreFalse_reportOnlyAndDisabledFalse() {
        assertThat(keepFlag(writeOwner)).isEqualTo(true);
        assertThat(keepFlag(excludedOwner)).isEqualTo(false);
        assertThat(keepFlag(noStoreOwner)).isEqualTo(false);
        String w = draft(writeOwner, null), x = draft(excludedOwner, null);
        assertThat(call(writeOwner, HttpMethod.GET, ev(w), null).getBody().get("keep_available")).isEqualTo(true);
        assertThat(call(excludedOwner, HttpMethod.GET, ev(x), null).getBody().get("keep_available")).isEqualTo(false);
        assertThat(create(excludedOwner, null).getBody().get("keep_available")).as("the New bazar answer").isEqualTo(false);

        reconcile.setReportOnly(true);
        assertThat(keepFlag(writeOwner)).as("report-only").isEqualTo(false);
        reconcile.setReportOnly(false);
        reconcile.setEnabled(false);
        assertThat(keepFlag(writeOwner)).as("reconcile switched off").isEqualTo(false);
        reconcile.setEnabled(true);
        assertThat(keepFlag(writeOwner)).isEqualTo(true);
    }

    // ── e: an excluded store ────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void e1_excluded_keepRefusedOnCreateAndSet_removeWorks() {
        assertKeepUnavailable(create(excludedOwner, "leave"));
        String e = draft(excludedOwner, null);
        assertThat(locationMode(e)).isEqualTo("remove");
        assertKeepUnavailable(call(excludedOwner, HttpMethod.PUT, ev(e + "/online-stock"), Map.of("mode", "leave")));
        assertThat(locationMode(e)).as("nothing changed").isEqualTo("remove");
        ResponseEntity<Map> remove = call(excludedOwner, HttpMethod.PUT, ev(e + "/online-stock"), Map.of("mode", "remove"));
        assertThat(remove.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void e2_excluded_markSentOnAKeepLocation_refused_switchToRemove_thenSends() {
        String e = draft(excludedOwner, null);
        scanOnePiece(excludedOwner, excludedTenant, excludedMain, e);
        // The location was put on Keep while Keep was possible (or by Settings) — Mark sent refuses it now.
        jdbc.update("UPDATE locations SET shopify_sync_mode = 'leave' WHERE id = (SELECT location_id FROM bazar_events WHERE id = ?::uuid)", e);
        assertKeepUnavailable(call(excludedOwner, HttpMethod.POST, ev(e + "/mark-sent"), Map.of()));
        assertThat(transferStatus(e)).as("not sent").isEqualTo("preparing");

        assertThat(call(excludedOwner, HttpMethod.PUT, ev(e + "/online-stock"), Map.of("mode", "remove")).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> sent = call(excludedOwner, HttpMethod.POST, ev(e + "/mark-sent"), Map.of());
        assertThat(sent.getStatusCode()).as(String.valueOf(sent.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().get("online_stock")).isEqualTo("remove");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void e3_aKeepBazarSentBeforeTheStoreWasExcluded_isNotTouched() {
        reconcile.setWriteExclude("");                       // the store could still use Keep then
        String e = draft(excludedOwner, "leave");
        scanOnePiece(excludedOwner, excludedTenant, excludedMain, e);
        assertThat(call(excludedOwner, HttpMethod.POST, ev(e + "/mark-sent"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        reconcile.setWriteExclude(EXCLUDED_SHOP);            // now excluded
        ResponseEntity<Map> detail = call(excludedOwner, HttpMethod.GET, ev(e), null);
        assertThat(detail.getBody()).containsEntry("online_stock", "leave").containsEntry("keep_available", false);
        assertThat(call(excludedOwner, HttpMethod.POST, ev(e + "/start"), Map.of()).getStatusCode())
            .as("a sent Keep bazar still starts").isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT t.shopify_sync_mode FROM bazar_events b JOIN transfers t ON t.id = b.transfer_id " +
            "WHERE b.id = ?::uuid", String.class, e)).isEqualTo("leave");
    }

    // ── r: report-only ──────────────────────────────────────────────────────

    @Test
    void r1_reportOnly_keepRefusedOnSetAndCreate() {
        String e = draft(writeOwner, null);
        reconcile.setReportOnly(true);
        assertKeepUnavailable(call(writeOwner, HttpMethod.PUT, ev(e + "/online-stock"), Map.of("mode", "leave")));
        assertKeepUnavailable(create(writeOwner, "leave"));
        assertThat(locationMode(e)).isEqualTo("remove");
    }

    // ── n: a write store, unchanged ─────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void n1_writeStore_keepCreatedChangedAndSent_asBefore() {
        String e = draft(writeOwner, "leave");
        assertThat(locationMode(e)).isEqualTo("leave");
        assertThat(call(writeOwner, HttpMethod.PUT, ev(e + "/online-stock"), Map.of("mode", "remove")).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(call(writeOwner, HttpMethod.PUT, ev(e + "/online-stock"), Map.of("mode", "leave")).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        scanOnePiece(writeOwner, writeTenant, writeMain, e);
        ResponseEntity<Map> sent = call(writeOwner, HttpMethod.POST, ev(e + "/mark-sent"), Map.of());
        assertThat(sent.getStatusCode()).as(String.valueOf(sent.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().get("online_stock")).isEqualTo("leave");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_shopify_syncs s JOIN bazar_events b ON b.transfer_id = s.transfer_id " +
            "WHERE b.id = ?::uuid", Integer.class, e)).as("Keep: no −N claim").isZero();
    }
}
