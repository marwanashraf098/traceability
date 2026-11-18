package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.MarketShippingCoverage;
import com.traceability.integrations.shopify.ShopifyDeliveryProfileGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.Level;
import com.traceability.integrations.shopify.ShopifyMoveSourceGateway.ShopifyLocation;
import com.traceability.integrations.shopify.ShopifyShippingModelGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.receiving.ShopifyInventoryFile;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Receiving move M1 (approved by Marawan 2026-11-12; M1 go 2026-11-18) — the WHOLE app on app_user, over the real HTTP
 * path. Shopify is mocked at the gateways (no Shopify write exists in M1).
 *
 *   s1 the "Moving from" list: own active locations only — Traced Main, an app-managed (fulfillment-service) and an
 *      inactive location left out
 *   g1 move gate, market-driven, VERIFIED Ready by the fresh read → 201; the source is on the session, Y frozen
 *   g2 move gate refuses the owner's "I've added it" (owner_confirmed) → 409 MOVE_SHIPPING_NOT_READY + link, nothing created
 *   g3 move gate refuses not_covered → 409
 *   g4 move gate refuses a STALE Ready: stored 'covered' + the fresh read says not_covered (or fails) → 409 — the fresh
 *      read was made
 *   g5 move gate, legacy: live membership → 201; not a member → 409 activate_needed
 *   r1 refused: needs-reauth store, inactive Traced Main, unlinked tenant, a non-main warehouse, Traced Main or an
 *      app-managed location as the source
 *   r2 a return-linked line → 409 MOVE_RETURN_LINE; an ordinary line is added, its source_choice 'keep'
 *   p1 preview: q summed per variant, default keep; "Set all to 0" only when asked; one line back to keep → the
 *      variant keeps; levels read at the SOURCE gid, never Traced Main's
 *   p2 progress: Y frozen at the first session from a source — not re-read for the second, X = 0 (nothing finalized)
 *   f1 finalize of a move session → 409 MOVE_FINALIZE_NOT_AVAILABLE, no piece; an ordinary session still finalizes
 *   c1 isolation (test c): app_user with no tenant set sees 0 rows of the new tables / move receipts; (test d) the
 *      same tenant sees its own; another tenant sees none
 *   x1–x6 cross-tenant over HTTP, one per endpoint (move-sources, move-sessions, move/levels, move/preview, the line
 *      source-choice PUT, the bulk source-choice POST): tenant B with tenant A's ids gets 404 (move-sessions: the
 *      existing 409 MOVE_MAIN_WAREHOUSE_ONLY / MOVE_SOURCE_NOT_ELIGIBLE — B can't name A's warehouse or location), A's
 *      rows are unchanged (the writes: A's source_choice stays 'keep'); each with a same-tenant positive control
 *   i1 the Excel import into a move session adds lines with source_choice 'keep' and makes no Shopify write
 *
 * Revert-checked (2026-11-18): the gate on stored readiness only (verified := readiness == ready) → g2 stays green,
 * g4 red; dropping the gate call in createSession → g2 / g3 / g4 red; ReceivingService.finalize without the move check
 * → f1 red; addLine without the move check → r2 red.
 * Cross-tenant revert-checked (2026-11-18), two layers — the explicit tenant_id predicate AND RLS on receipts /
 * receipt_lines: the session read unscoped (moveSession without tenant_id) → x3–x6 stay green while RLS holds, red
 * (x3, x4, x6) with RLS off on those tables; the line-choice write unscoped (no session check, UPDATE without
 * tenant_id) → x5 green while RLS holds, red with RLS off; RLS off alone (the Java predicates still there) → green.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceivingMoveAppUserTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyLocationGateway locations;
    @MockBean ShopifyDeliveryProfileGateway profiles;
    @MockBean ShopifyShippingModelGateway models;
    @MockBean ShopifyMoveSourceGateway moveSources;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaV2Client bostaV2;

    JdbcTemplate pg;
    static final AtomicLong IDS = new AtomicLong(61_000_000_000L);
    static final String SHOP_FLOOR = "gid://shopify/Location/7001";
    static final String APP_WH = "gid://shopify/Location/7002";
    static final String OLD_SHOP = "gid://shopify/Location/7003";

    @BeforeAll
    void setUp() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, locations, profiles, models, moveSources, jobScheduler, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(locations.readOnlineFulfillment(any(), eq("tok"), any()))
            .thenReturn(Optional.of(new ShopifyLocationGateway.OnlineFulfillment(true, true)));
        when(models.read(any(), eq("tok"))).thenReturn(Optional.of(ShopifyShippingModelGateway.Model.MARKET_DRIVEN));
        covered(MarketShippingCoverage.Verdict.COVERED);
        when(moveSources.countProductsWithStock(any(), eq("tok"), any())).thenReturn(38);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    void covered(MarketShippingCoverage.Verdict v) {
        when(models.readMarketCoverage(any(), eq("tok"), any())).thenReturn(Optional.of(new MarketShippingCoverage.Result(
            v, v == MarketShippingCoverage.Verdict.COVERED ? "Egypt — Standard" : null, List.of(), "test")));
    }

    class Shop {
        final UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner, main, other, variantA, variantB;
        final String mainGid = "gid://shopify/Location/" + IDS.incrementAndGet();
        final String varGidA, varGidB, shopDomain;

        Shop(String tag) { this(tag, true); }

        Shop(String tag, boolean linked) {
            shopDomain = "move-" + tag + "-" + IDS.incrementAndGet() + ".myshopify.com";
            pg.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Move " + tag);
            pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, connection_type, access_token_scopes) " +
                "VALUES (?, ?, 'shopify', ?, 'connected', 'oauth', " +
                "'read_orders,read_products,write_inventory,read_locations,write_locations,write_shipping,read_markets')",
                store, tenant, shopDomain);
            owner = pg.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, 'owner', ?, 'x', 'owner'::user_role, true) RETURNING id",
                UUID.class, tenant, "owner-" + UUID.randomUUID() + "@test.com");
            main = linked
                ? pg.queryForObject("INSERT INTO locations (tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status, " +
                    "  shopify_shipping_model, shopify_online_fulfillment_status) " +
                    "VALUES (?, 'Main', true, ?, 'linked', 'market_driven', 'on') RETURNING id", UUID.class, tenant, mainGid)
                : pg.queryForObject("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main', true) RETURNING id",
                    UUID.class, tenant);
            other = pg.queryForObject("INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Back room', false) " +
                "RETURNING id", UUID.class, tenant);
            UUID product = pg.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
                "VALUES (?, ?, ?, 'Polo', 'active') RETURNING id", UUID.class, tenant, store,
                "gid://shopify/Product/" + IDS.incrementAndGet());
            varGidA = "gid://shopify/ProductVariant/" + IDS.incrementAndGet();
            varGidB = "gid://shopify/ProductVariant/" + IDS.incrementAndGet();
            variantA = pg.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
                "VALUES (?, ?, ?, ?, 'Navy / M') RETURNING id", UUID.class, tenant, product, varGidA, "P-" + IDS.incrementAndGet());
            variantB = pg.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
                "VALUES (?, ?, ?, ?, 'Navy / L') RETURNING id", UUID.class, tenant, product, varGidB, "P-" + IDS.incrementAndGet());
        }

        String token() { return jwt.issueAccessToken(owner, tenant, "owner"); }

        void sourcesAre(ShopifyLocation... locs) {
            when(moveSources.listLocations(eq(shopDomain), eq("tok"))).thenReturn(List.of(locs));
        }

        void standardSources() {
            sourcesAre(new ShopifyLocation(mainGid, "Traced Main Warehouse", true, false),
                new ShopifyLocation(SHOP_FLOOR, "Shop floor", true, false),
                new ShopifyLocation(APP_WH, "Snow City Warehouse", true, true),
                new ShopifyLocation(OLD_SHOP, "Old shop", false, false));
        }

        long receipts() {
            return pg.queryForObject("SELECT COUNT(*) FROM receipts WHERE tenant_id = ?", Long.class, tenant);
        }
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> call(HttpMethod method, String token, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, h), Map.class);
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> create(Shop s, UUID location, String source) {
        Map<String, Object> body = new HashMap<>();
        body.put("locationId", location.toString());
        body.put("reference", "PO-1");
        body.put("sourceLocationId", source);
        return call(HttpMethod.POST, s.token(), "/api/v1/receiving/move-sessions", body);
    }

    UUID created(Shop s) {
        @SuppressWarnings("rawtypes") ResponseEntity<Map> r = create(s, s.main, SHOP_FLOOR);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        return UUID.fromString((String) r.getBody().get("sessionId"));
    }

    UUID addLine(Shop s, UUID session, UUID variant, int q) {
        @SuppressWarnings("rawtypes") ResponseEntity<Map> r = call(HttpMethod.POST, s.token(),
            "/api/v1/receiving/sessions/" + session + "/lines", Map.of("variantId", variant.toString(), "quantity", q));
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        return UUID.fromString((String) r.getBody().get("lineId"));
    }

    @SuppressWarnings("rawtypes")
    static void refused(ResponseEntity<Map> r, String code) {
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(409);
        assertThat(r.getBody()).containsEntry("code", code);
        assertThat((String) r.getBody().get("message_en")).isNotBlank();
        assertThat((String) r.getBody().get("message_ar")).isNotBlank();
    }

    // ── s1 ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void s1_sources_ownActiveOnly() {
        Shop s = new Shop("s1");
        s.standardSources();
        ResponseEntity<List> r = rest.exchange("/api/v1/receiving/move-sources", HttpMethod.GET,
            new HttpEntity<>(null, bearer(s)), List.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).hasSize(1);
        assertThat(r.getBody().get(0)).isEqualTo(Map.of("id", SHOP_FLOOR, "name", "Shop floor"));
    }

    HttpHeaders bearer(Shop s) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(s.token());
        return h;
    }

    // ── the move gate ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void g1_marketDriven_verifiedReady_creates_sourceOnSession_yFrozen() {
        Shop s = new Shop("g1");
        s.standardSources();
        UUID session = created(s);
        Map<String, Object> row = pg.queryForMap("SELECT move_source_location_gid, move_source_location_name, mode, location_id " +
            "FROM receipts WHERE id = ?", session);
        assertThat(row).containsEntry("move_source_location_gid", SHOP_FLOOR).containsEntry("move_source_location_name", "Shop floor")
            .containsEntry("mode", "print_stick").containsEntry("location_id", s.main);
        assertThat(pg.queryForObject("SELECT total_products FROM receiving_move_progress WHERE tenant_id = ? AND source_location_gid = ?",
            Integer.class, s.tenant, SHOP_FLOOR)).isEqualTo(38);
        verify(models).readMarketCoverage(eq(s.shopDomain), eq("tok"), eq(s.mainGid));
        verify(locations).readOnlineFulfillment(eq(s.shopDomain), eq("tok"), eq(s.mainGid));
        @SuppressWarnings("rawtypes") ResponseEntity<Map> got = call(HttpMethod.GET, s.token(), "/api/v1/receiving/sessions/" + session, null);
        assertThat(got.getBody().get("moveSource")).isEqualTo(Map.of("id", SHOP_FLOOR, "name", "Shop floor"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void g2_ownerConfirmed_neverOpensTheGate() {
        Shop s = new Shop("g2");
        s.standardSources();
        pg.update("UPDATE locations SET shopify_shipping_owner_confirmed_at = now(), shopify_shipping_owner_confirmed_by = ? " +
            "WHERE id = ?", s.owner, s.main);
        covered(MarketShippingCoverage.Verdict.NO_VERDICT);
        ResponseEntity<Map> r = create(s, s.main, SHOP_FLOOR);
        refused(r, "MOVE_SHIPPING_NOT_READY");
        Map<String, Object> details = (Map<String, Object>) r.getBody().get("details");
        assertThat(details).containsEntry("readiness", "owner_confirmed").containsEntry("shippingModel", "market_driven");
        assertThat((String) details.get("locationUrl")).isEqualTo("https://admin.shopify.com/store/"
            + s.shopDomain.replace(".myshopify.com", "") + "/settings/locations/" + s.mainGid.replaceAll("\\D", ""));
        assertThat((String) r.getBody().get("message_en")).contains("Make sure Traced Main Warehouse is added to your shipping rates in Shopify");
        assertThat((String) r.getBody().get("message_ar")).contains("أسعار الشحن");
        assertThat(s.receipts()).isZero();
        verify(moveSources, never()).countProductsWithStock(any(), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void g3_notCovered_refused() {
        Shop s = new Shop("g3");
        s.standardSources();
        covered(MarketShippingCoverage.Verdict.NOT_COVERED);
        ResponseEntity<Map> r = create(s, s.main, SHOP_FLOOR);
        refused(r, "MOVE_SHIPPING_NOT_READY");
        assertThat((Map<String, Object>) r.getBody().get("details")).containsEntry("readiness", "add_to_shipping_rates");
        assertThat(s.receipts()).isZero();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void g4_staleReady_freshReadUsed() {
        Shop s = new Shop("g4");
        s.standardSources();
        // Stored: the hourly read said covered → Ready.
        pg.update("UPDATE locations SET shopify_market_shipping_status = 'covered', shopify_market_shipping_checked_at = now() " +
            "WHERE id = ?", s.main);
        assertThat(pg.queryForObject("SELECT " + com.traceability.inventory.TracedShippingReadiness.SQL +
            " FROM locations l WHERE l.id = ?", String.class, s.main)).isEqualTo("ready");
        // (1) The fresh read now says not covered.
        covered(MarketShippingCoverage.Verdict.NOT_COVERED);
        refused(create(s, s.main, SHOP_FLOOR), "MOVE_SHIPPING_NOT_READY");
        verify(models).readMarketCoverage(eq(s.shopDomain), eq("tok"), eq(s.mainGid));
        // (2) Stored covered again, and the fresh read FAILS: a Ready this read couldn't confirm isn't Ready.
        pg.update("UPDATE locations SET shopify_market_shipping_status = 'covered' WHERE id = ?", s.main);
        when(models.readMarketCoverage(any(), eq("tok"), any())).thenReturn(Optional.empty());
        ResponseEntity<Map> r = create(s, s.main, SHOP_FLOOR);
        refused(r, "MOVE_SHIPPING_NOT_READY");
        assertThat((Map<String, Object>) r.getBody().get("details")).containsEntry("readiness", "unknown");
        assertThat(s.receipts()).isZero();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void g5_legacy_liveMembership() {
        Shop s = new Shop("g5");
        s.standardSources();
        pg.update("UPDATE locations SET shopify_shipping_model = 'legacy' WHERE id = ?", s.main);
        when(models.read(any(), eq("tok"))).thenReturn(Optional.of(ShopifyShippingModelGateway.Model.LEGACY));
        when(profiles.findDefaultProfileLocationGroup(any(), eq("tok"))).thenReturn(Optional.of(
            new ShopifyDeliveryProfileGateway.LocationGroupInfo("gid://shopify/DeliveryProfile/1", "g", Set.of("gid://shopify/Location/1"))));
        ResponseEntity<Map> r = create(s, s.main, SHOP_FLOOR);
        refused(r, "MOVE_SHIPPING_NOT_READY");
        assertThat((Map<String, Object>) r.getBody().get("details")).containsEntry("readiness", "activate_needed");
        assertThat((String) r.getBody().get("message_en")).contains("Add Traced Main Warehouse to your shipping profile in Shopify");

        when(profiles.findDefaultProfileLocationGroup(any(), eq("tok"))).thenReturn(Optional.of(
            new ShopifyDeliveryProfileGateway.LocationGroupInfo("gid://shopify/DeliveryProfile/1", "g", Set.of(s.mainGid))));
        created(s);
        verify(models, never()).readMarketCoverage(any(), any(), any());
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void r1_refusals() {
        Shop reauth = new Shop("r1a");
        reauth.standardSources();
        pg.update("UPDATE stores SET status = 'needs_reauth' WHERE id = ?", reauth.store);
        ResponseEntity<Map> r = create(reauth, reauth.main, SHOP_FLOOR);
        refused(r, "MOVE_SHOPIFY_NOT_READY");
        assertThat((Map<String, Object>) r.getBody().get("details")).containsEntry("problem", "NEEDS_REAUTH");

        Shop inactive = new Shop("r1b");
        inactive.standardSources();
        pg.update("UPDATE locations SET shopify_location_state = 'inactive' WHERE id = ?", inactive.main);
        r = create(inactive, inactive.main, SHOP_FLOOR);
        refused(r, "MOVE_SHOPIFY_NOT_READY");
        assertThat((Map<String, Object>) r.getBody().get("details")).containsEntry("problem", "LOCATION_INACTIVE");

        Shop unlinked = new Shop("r1c", false);
        unlinked.standardSources();
        r = create(unlinked, unlinked.main, SHOP_FLOOR);
        refused(r, "MOVE_SHOPIFY_NOT_READY");
        assertThat((Map<String, Object>) r.getBody().get("details")).containsEntry("problem", "LOCATION_NOT_LINKED");
        assertThat(call(HttpMethod.GET, unlinked.token(), "/api/v1/receiving/move-sources", null).getStatusCode().value()).isEqualTo(409);

        Shop s = new Shop("r1d");
        s.standardSources();
        refused(create(s, s.other, SHOP_FLOOR), "MOVE_MAIN_WAREHOUSE_ONLY");
        refused(create(s, s.main, s.mainGid), "MOVE_SOURCE_NOT_ELIGIBLE");
        refused(create(s, s.main, APP_WH), "MOVE_SOURCE_NOT_ELIGIBLE");
        refused(create(s, s.main, OLD_SHOP), "MOVE_SOURCE_NOT_ELIGIBLE");

        for (Shop x : List.of(reauth, inactive, unlinked, s)) assertThat(x.receipts()).isZero();
        verifyNoInteractions(models);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void m1_countOnlyAndFactory_refusedUntilTested_printStickAllowed() {
        Shop s = new Shop("m1m");
        s.standardSources();
        for (String mode : List.of("count_only", "factory")) {
            Map<String, Object> body = new HashMap<>();
            body.put("locationId", s.main.toString());
            body.put("reference", "PO-1");
            body.put("sourceLocationId", SHOP_FLOOR);
            body.put("mode", mode);
            refused(call(HttpMethod.POST, s.token(), "/api/v1/receiving/move-sessions", body), "MOVE_MODE_NOT_AVAILABLE");
        }
        assertThat(s.receipts()).isZero();
        verifyNoInteractions(models, moveSources);

        Map<String, Object> body = new HashMap<>();
        body.put("locationId", s.main.toString());
        body.put("reference", "PO-1");
        body.put("sourceLocationId", SHOP_FLOOR);
        body.put("mode", "print_stick");
        ResponseEntity<Map> ok = call(HttpMethod.POST, s.token(), "/api/v1/receiving/move-sessions", body);
        assertThat(ok.getStatusCode().value()).as("%s", ok.getBody()).isEqualTo(201);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void r2_returnLineRefused_ordinaryLineKeep() {
        Shop s = new Shop("r2");
        s.standardSources();
        UUID session = created(s);
        ResponseEntity<Map> r = call(HttpMethod.POST, s.token(), "/api/v1/receiving/sessions/" + session + "/lines",
            Map.of("variantId", s.variantA.toString(), "quantity", 1, "returnSource", "shipment:" + UUID.randomUUID()));
        refused(r, "MOVE_RETURN_LINE");
        UUID line = addLine(s, session, s.variantA, 3);
        assertThat(pg.queryForObject("SELECT source_choice FROM receipt_lines WHERE id = ?", String.class, line)).isEqualTo("keep");
    }

    // ── preview + choice ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void p1_preview_defaultKeep_setAllToZero_onlyWhenAsked() {
        Shop s = new Shop("p1");
        s.standardSources();
        UUID session = created(s);
        UUID l1 = addLine(s, session, s.variantA, 4);
        addLine(s, session, s.variantA, 6);
        addLine(s, session, s.variantB, 6);
        when(moveSources.fetchLocationLevels(eq(s.shopDomain), eq("tok"), eq(SHOP_FLOOR), anyList())).thenReturn(Map.of(
            s.varGidA, new Level(s.varGidA, true, 12, 3, 15),
            s.varGidB, new Level(s.varGidB, true, 4, 0, 4)));
        String preview = "/api/v1/receiving/sessions/" + session + "/move/preview";

        Map body = call(HttpMethod.GET, s.token(), preview, null).getBody();
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("quantity", 10).containsEntry("choice", "keep").containsEntry("case", "keep_rest")
            .containsEntry("available", 12).containsEntry("committed", 3)
            .containsEntry("sourceDelta", -10).containsEntry("sourceAfter", 2).containsEntry("totalChange", 0).containsEntry("mainDelta", 10);
        assertThat(lines.get(1)).containsEntry("case", "more_than_shopify").containsEntry("sourceDelta", -4).containsEntry("totalChange", 2);
        assertThat((Map<String, Object>) body.get("totals")).containsEntry("mainDelta", 16).containsEntry("sourceDelta", -14)
            .containsEntry("totalChange", 2);

        // "Set all to 0" — explicit.
        assertThat(call(HttpMethod.POST, s.token(), "/api/v1/receiving/sessions/" + session + "/move/source-choice",
            Map.of("choice", "zero")).getBody()).containsEntry("linesUpdated", 3);
        lines = (List<Map<String, Object>>) call(HttpMethod.GET, s.token(), preview, null).getBody().get("lines");
        assertThat(lines.get(0)).containsEntry("choice", "zero").containsEntry("case", "set_to_zero")
            .containsEntry("sourceDelta", -12).containsEntry("sourceAfter", 0).containsEntry("totalChange", -2);

        // One line back to keep → the whole variant keeps (every line of it).
        assertThat(call(HttpMethod.PUT, s.token(), "/api/v1/receiving/sessions/" + session + "/lines/" + l1 + "/source-choice",
            Map.of("choice", "keep")).getStatusCode().value()).isEqualTo(204);
        lines = (List<Map<String, Object>>) call(HttpMethod.GET, s.token(), preview, null).getBody().get("lines");
        assertThat(lines.get(0)).containsEntry("choice", "keep").containsEntry("sourceDelta", -10);
        assertThat(lines.get(1)).containsEntry("choice", "zero");

        // A new line starts 'keep' → its variant keeps until asked again.
        addLine(s, session, s.variantB, 1);
        lines = (List<Map<String, Object>>) call(HttpMethod.GET, s.token(), preview, null).getBody().get("lines");
        assertThat(lines.get(1)).containsEntry("choice", "keep");

        assertThat(call(HttpMethod.POST, s.token(), "/api/v1/receiving/sessions/" + session + "/move/source-choice",
            Map.of("choice", "nonsense")).getStatusCode().value()).isEqualTo(400);

        verify(moveSources, atLeastOnce()).fetchLocationLevels(any(), any(), eq(SHOP_FLOOR), anyList());
        verify(moveSources, never()).fetchLocationLevels(any(), any(), eq(s.mainGid), anyList());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void p2_progress_yFrozenAtFirstSession() {
        Shop s = new Shop("p2");
        s.standardSources();
        UUID first = created(s);
        when(moveSources.countProductsWithStock(any(), eq("tok"), any())).thenReturn(50);
        UUID second = created(s);
        verify(moveSources, times(1)).countProductsWithStock(eq(s.shopDomain), eq("tok"), eq(SHOP_FLOOR));
        Map body = call(HttpMethod.GET, s.token(), "/api/v1/receiving/sessions/" + second + "/move/progress", null).getBody();
        assertThat(body).containsEntry("total", 38).containsEntry("moved", 0);
        assertThat(pg.queryForObject("SELECT frozen_by_receipt_id FROM receiving_move_progress WHERE tenant_id = ?",
            UUID.class, s.tenant)).isEqualTo(first);
    }

    // ── finalize ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void f1_finalizeRefused_untilM2() {
        Shop s = new Shop("f1");
        s.standardSources();
        UUID session = created(s);
        addLine(s, session, s.variantA, 2);
        refused(call(HttpMethod.POST, s.token(), "/api/v1/receiving/sessions/" + session + "/finalize", null),
            "MOVE_FINALIZE_NOT_AVAILABLE");
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM pieces WHERE receipt_id = ?", Long.class, session)).isZero();
        assertThat(pg.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, session)).isEqualTo("open");
        // An ordinary session is unchanged.
        ResponseEntity<Map> plain = call(HttpMethod.POST, s.token(), "/api/v1/receiving/sessions",
            Map.of("locationId", s.main.toString(), "reference", "PO-2"));
        UUID p = UUID.fromString((String) plain.getBody().get("sessionId"));
        addLine(s, p, s.variantA, 2);
        assertThat(call(HttpMethod.POST, s.token(), "/api/v1/receiving/sessions/" + p + "/finalize", null).getBody())
            .containsEntry("piecesCreated", 2);
        assertThat(call(HttpMethod.GET, s.token(), "/api/v1/receiving/sessions/" + p, null).getBody()).doesNotContainKey("moveSource");
    }

    // ── isolation ────────────────────────────────────────────────────────────────────────────────────────────────

    long countAsAppUser(String sql, UUID tenantOrNull) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            c.setAutoCommit(false);
            if (tenantOrNull != null) {
                try (PreparedStatement st = c.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
                    st.setString(1, tenantOrNull.toString());
                    st.execute();
                }
            }
            try (PreparedStatement st = c.prepareStatement(sql); ResultSet rs = st.executeQuery()) {
                rs.next();
                long n = rs.getLong(1);
                c.rollback();
                return n;
            }
        }
    }

    @Test
    void c1_isolation_noGuc_zero_sameTenantSeesOwn_otherTenantNone() throws Exception {
        Shop a = new Shop("c1a");
        Shop b = new Shop("c1b");
        a.standardSources();
        UUID session = created(a);
        // M2-shaped rows, written as postgres (nothing writes them in M1).
        UUID sync = pg.queryForObject("INSERT INTO receiving_move_syncs (tenant_id, receipt_id, source_location_gid, status) " +
            "VALUES (?, ?, ?, 'failed_ambiguous') RETURNING id", UUID.class, a.tenant, session, SHOP_FLOOR);
        pg.update("INSERT INTO receiving_move_attempts (tenant_id, sync_id, attempt_no, idempotency_key, before_levels, sent_changes) " +
            "VALUES (?, ?, 1, 'k', '{}'::jsonb, '[]'::jsonb)", a.tenant, sync);

        List<String> counts = List.of(
            "SELECT COUNT(*) FROM receiving_move_progress",
            "SELECT COUNT(*) FROM receiving_move_syncs",
            "SELECT COUNT(*) FROM receiving_move_attempts",
            "SELECT COUNT(*) FROM receipts WHERE move_source_location_gid IS NOT NULL");
        for (String q : counts) {
            assertThat(countAsAppUser(q, null)).as("no GUC: %s", q).isZero();
            assertThat(countAsAppUser(q, a.tenant)).as("tenant A: %s", q).isEqualTo(1);
            assertThat(countAsAppUser(q, b.tenant)).as("tenant B: %s", q).isZero();
        }
        // B can't read A's move session over HTTP either.
        assertThat(call(HttpMethod.GET, b.token(), "/api/v1/receiving/sessions/" + session + "/move/progress", null)
            .getStatusCode().value()).isEqualTo(404);
    }

    // ── cross-tenant over HTTP, one per endpoint ─────────────────────────────────────────────────────────────────

    /** A's move-related rows, as postgres — must be identical before and after B's attempt. */
    List<Map<String, Object>> snapshot(Shop a) {
        List<Map<String, Object>> out = new ArrayList<>(pg.queryForList(
            "SELECT id, status, move_source_location_gid, move_source_location_name FROM receipts WHERE tenant_id = ? ORDER BY id",
            a.tenant));
        out.addAll(pg.queryForList("SELECT id, variant_id, quantity, source_choice FROM receipt_lines WHERE tenant_id = ? ORDER BY id",
            a.tenant));
        out.addAll(pg.queryForList("SELECT source_location_gid, total_products, frozen_at, frozen_by_receipt_id " +
            "FROM receiving_move_progress WHERE tenant_id = ? ORDER BY source_location_gid", a.tenant));
        return out;
    }

    String choiceOf(UUID line) {
        return pg.queryForObject("SELECT source_choice FROM receipt_lines WHERE id = ?", String.class, line);
    }

    static final String B_FLOOR = "gid://shopify/Location/7101";

    /** Two tenants; A has a move session with two lines (both 'keep'), B its own move session from its own location. */
    record Pair(Shop a, Shop b, UUID aSession, UUID aLine1, UUID aLine2, UUID bSession) {}

    Pair pair(String tag) {
        Shop a = new Shop(tag + "a");
        Shop b = new Shop(tag + "b");
        a.standardSources();
        b.sourcesAre(new ShopifyLocation(b.mainGid, "Traced Main Warehouse", true, false),
            new ShopifyLocation(B_FLOOR, "B floor", true, false));
        UUID aSession = created(a);
        UUID l1 = addLine(a, aSession, a.variantA, 4);
        UUID l2 = addLine(a, aSession, a.variantB, 2);
        @SuppressWarnings("rawtypes") ResponseEntity<Map> r = create(b, b.main, B_FLOOR);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        UUID bSession = UUID.fromString((String) r.getBody().get("sessionId"));
        when(moveSources.fetchLocationLevels(any(), eq("tok"), any(), anyList())).thenReturn(Map.of(
            a.varGidA, new Level(a.varGidA, true, 10, 0, 10), a.varGidB, new Level(a.varGidB, true, 1, 0, 1)));
        clearInvocations(moveSources);
        return new Pair(a, b, aSession, l1, l2, bSession);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void x1_moveSources_eachTenantSeesOnlyItsOwnStoresLocations() {
        Pair p = pair("x1");
        List<Map<String, Object>> before = snapshot(p.a());
        ResponseEntity<List> b = rest.exchange("/api/v1/receiving/move-sources", HttpMethod.GET,
            new HttpEntity<>(null, bearer(p.b())), List.class);
        assertThat(b.getStatusCode().value()).isEqualTo(200);
        assertThat(b.getBody()).containsExactly(Map.of("id", B_FLOOR, "name", "B floor"));
        verify(moveSources).listLocations(eq(p.b().shopDomain), eq("tok"));
        verify(moveSources, never()).listLocations(eq(p.a().shopDomain), any());
        assertThat(snapshot(p.a())).isEqualTo(before);
        // Same-tenant control.
        ResponseEntity<List> a = rest.exchange("/api/v1/receiving/move-sources", HttpMethod.GET,
            new HttpEntity<>(null, bearer(p.a())), List.class);
        assertThat(a.getBody()).containsExactly(Map.of("id", SHOP_FLOOR, "name", "Shop floor"));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void x2_moveSessions_bCantUseAsWarehouseOrSource_nothingOfAChanges() {
        Pair p = pair("x2");
        List<Map<String, Object>> before = snapshot(p.a());
        long bReceipts = p.b().receipts();
        refused(create(p.b(), p.a().main, B_FLOOR), "MOVE_MAIN_WAREHOUSE_ONLY");     // A's main warehouse
        refused(create(p.b(), p.b().main, SHOP_FLOOR), "MOVE_SOURCE_NOT_ELIGIBLE");  // A's Shopify location
        assertThat(p.b().receipts()).isEqualTo(bReceipts);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM receiving_move_progress WHERE tenant_id = ? AND source_location_gid = ?",
            Long.class, p.b().tenant, SHOP_FLOOR)).isZero();
        assertThat(snapshot(p.a())).isEqualTo(before);
        // Same-tenant control.
        ResponseEntity<Map> ok = create(p.a(), p.a().main, SHOP_FLOOR);
        assertThat(ok.getStatusCode().value()).as("%s", ok.getBody()).isEqualTo(201);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void x3_levels_bWithAsSession_404() {
        Pair p = pair("x3");
        List<Map<String, Object>> before = snapshot(p.a());
        String path = "/api/v1/receiving/sessions/" + p.aSession() + "/move/levels";
        assertThat(call(HttpMethod.GET, p.b().token(), path, null).getStatusCode().value()).isEqualTo(404);
        verify(moveSources, never()).fetchLocationLevels(any(), any(), any(), anyList());
        assertThat(snapshot(p.a())).isEqualTo(before);
        // Same-tenant control.
        ResponseEntity<Map> a = call(HttpMethod.GET, p.a().token(), path, null);
        assertThat(a.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) a.getBody().get("lines")).hasSize(2);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void x4_preview_bWithAsSession_404() {
        Pair p = pair("x4");
        List<Map<String, Object>> before = snapshot(p.a());
        String path = "/api/v1/receiving/sessions/" + p.aSession() + "/move/preview";
        assertThat(call(HttpMethod.GET, p.b().token(), path, null).getStatusCode().value()).isEqualTo(404);
        verify(moveSources, never()).fetchLocationLevels(any(), any(), any(), anyList());
        assertThat(snapshot(p.a())).isEqualTo(before);
        // Same-tenant control.
        ResponseEntity<Map> a = call(HttpMethod.GET, p.a().token(), path, null);
        assertThat(a.getStatusCode().value()).isEqualTo(200);
        assertThat(a.getBody()).containsKey("totals");
    }

    @Test
    void x5_lineChoice_bWithAsSessionOrLine_404_aStaysKeep() {
        Pair p = pair("x5");
        List<Map<String, Object>> before = snapshot(p.a());
        Map<String, String> zero = Map.of("choice", "zero");
        // A's session + A's line.
        assertThat(call(HttpMethod.PUT, p.b().token(), "/api/v1/receiving/sessions/" + p.aSession() + "/lines/" + p.aLine1()
            + "/source-choice", zero).getStatusCode().value()).isEqualTo(404);
        // B's own session + A's line.
        assertThat(call(HttpMethod.PUT, p.b().token(), "/api/v1/receiving/sessions/" + p.bSession() + "/lines/" + p.aLine1()
            + "/source-choice", zero).getStatusCode().value()).isEqualTo(404);
        assertThat(choiceOf(p.aLine1())).isEqualTo("keep");
        assertThat(choiceOf(p.aLine2())).isEqualTo("keep");
        assertThat(snapshot(p.a())).isEqualTo(before);
        // Same-tenant control.
        assertThat(call(HttpMethod.PUT, p.a().token(), "/api/v1/receiving/sessions/" + p.aSession() + "/lines/" + p.aLine1()
            + "/source-choice", zero).getStatusCode().value()).isEqualTo(204);
        assertThat(choiceOf(p.aLine1())).isEqualTo("zero");
        assertThat(choiceOf(p.aLine2())).isEqualTo("keep");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void x6_bulkChoice_bWithAsSession_404_aStaysKeep() {
        Pair p = pair("x6");
        List<Map<String, Object>> before = snapshot(p.a());
        String path = "/api/v1/receiving/sessions/" + p.aSession() + "/move/source-choice";
        assertThat(call(HttpMethod.POST, p.b().token(), path, Map.of("choice", "zero")).getStatusCode().value()).isEqualTo(404);
        assertThat(choiceOf(p.aLine1())).isEqualTo("keep");
        assertThat(choiceOf(p.aLine2())).isEqualTo("keep");
        assertThat(snapshot(p.a())).isEqualTo(before);
        // Same-tenant control.
        ResponseEntity<Map> a = call(HttpMethod.POST, p.a().token(), path, Map.of("choice", "zero"));
        assertThat(a.getBody()).containsEntry("linesUpdated", 2);
        assertThat(choiceOf(p.aLine1())).isEqualTo("zero");
        assertThat(choiceOf(p.aLine2())).isEqualTo("zero");
    }

    // ── Excel import into a move session ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void i1_excelImport_intoMoveSession_linesKeep_noShopifyWrite() {
        Shop s = new Shop("i1");
        s.standardSources();
        UUID session = created(s);
        clearInvocations(shopifyGateway);

        // A Shopify inventory export, one location, "On hand (new)" filled — matched by SKU.
        List<String> header = ShopifyInventoryFile.SHOPIFY_HEADERS;
        List<List<String>> t = new ArrayList<>();
        t.add(header);
        for (Object[] v : new Object[][] { {s.variantA, "Navy / M", "5"}, {s.variantB, "Navy / L", "2"} }) {
            List<String> row = new ArrayList<>(Collections.nCopies(header.size(), ""));
            row.set(header.indexOf("Handle"), "polo");
            row.set(header.indexOf("Title"), "Polo");
            row.set(header.indexOf("Option1 Name"), "Title");
            row.set(header.indexOf("Option1 Value"), (String) v[1]);
            row.set(header.indexOf("SKU"), pg.queryForObject("SELECT sku FROM variants WHERE id = ?", String.class, v[0]));
            row.set(header.indexOf("Location"), "Main");
            row.set(header.indexOf("On hand (current)"), "0");
            row.set(header.indexOf("On hand (new)"), (String) v[2]);
            t.add(row);
        }
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        byte[] file = ShopifyInventoryFile.writeCsv(t);
        form.add("file", new ByteArrayResource(file) { @Override public String getFilename() { return "move.csv"; } });
        HttpHeaders h = bearer(s);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> preview = rest.exchange("/api/v1/receiving/sessions/" + session + "/import/preview",
            HttpMethod.POST, new HttpEntity<>(form, h), Map.class);
        assertThat(preview.getStatusCode().value()).as("%s", preview.getBody()).isEqualTo(200);
        List<Map<String, Object>> matched = (List<Map<String, Object>>) preview.getBody().get("matched");
        assertThat(matched).hasSize(2);

        List<Map<String, Object>> lines = matched.stream()
            .map(m -> Map.<String, Object>of("variantId", m.get("variantId"), "quantity", m.get("quantity"))).toList();
        ResponseEntity<Map> applied = call(HttpMethod.POST, s.token(), "/api/v1/receiving/sessions/" + session + "/import/apply",
            Map.of("lines", lines));
        assertThat(applied.getStatusCode().value()).as("%s", applied.getBody()).isEqualTo(200);
        assertThat(applied.getBody()).containsEntry("linesAdded", 2).containsEntry("units", 7);

        assertThat(pg.queryForList("SELECT variant_id, quantity, source_choice FROM receipt_lines WHERE receipt_id = ?", session))
            .extracting(r -> List.of(r.get("variant_id"), r.get("quantity"), r.get("source_choice")))
            .containsExactlyInAnyOrder(List.of(s.variantA, 5, "keep"), List.of(s.variantB, 2, "keep"));
        // No Shopify write: nothing on the Shopify gateway, no claim row, no move sync; the session stays open.
        verifyNoInteractions(shopifyGateway);
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", Long.class, s.tenant)).isZero();
        assertThat(pg.queryForObject("SELECT COUNT(*) FROM receiving_move_syncs WHERE tenant_id = ?", Long.class, s.tenant)).isZero();
        assertThat(pg.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, session)).isEqualTo("open");
    }

    @Test
    void c2_resolutionColumns_C1() {
        Shop a = new Shop("c2");
        a.standardSources();
        UUID session = created(a);
        UUID sync = pg.queryForObject("INSERT INTO receiving_move_syncs (tenant_id, receipt_id, source_location_gid, status) " +
            "VALUES (?, ?, ?, 'exhausted') RETURNING id", UUID.class, a.tenant, session, SHOP_FLOOR);
        // Resolution is whole (at + by + resolution) and only on failed_ambiguous / exhausted.
        Assertions.assertThrows(Exception.class, () -> pg.update(
            "UPDATE receiving_move_syncs SET resolution = 'applied' WHERE id = ?", sync));
        pg.update("UPDATE receiving_move_syncs SET resolution = 'not_applied', resolved_at = now(), resolved_by = ? WHERE id = ?",
            a.owner, sync);
        Assertions.assertThrows(Exception.class, () -> pg.update(
            "UPDATE receiving_move_syncs SET status = 'pushed' WHERE id = ?", sync));
        // One claim per session.
        Assertions.assertThrows(Exception.class, () -> pg.update(
            "INSERT INTO receiving_move_syncs (tenant_id, receipt_id, source_location_gid, status) VALUES (?, ?, ?, 'queued')",
            a.tenant, session, SHOP_FLOOR));
    }
}
