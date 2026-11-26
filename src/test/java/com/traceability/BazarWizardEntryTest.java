package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway.Page;
import com.traceability.integrations.shopify.ShopifyVariantStockGateway.VariantStock;
import com.traceability.inventory.LabelService;
import com.traceability.notifications.EmailGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Bazar wizard entry (2026-11-26). Whole app as app_user, real HTTP, BAZAR_SIGNUP_ENABLED on.
 * <ul>
 *   <li>a1 gate = bazar alone: a normal signup (no intent) with bazar on → owner 200 (mainAvailable / blocked /
 *       suggestStart), manager reads only, worker 403, bazar off 404, another tenant sees nothing of it.</li>
 *   <li>a2 /me: bazarSetup + shopifyConnected for owner / manager of a bazar store (false before a store connects);
 *       never for a worker or with bazar off.</li>
 *   <li>a3 Main stock: products' atMain and status' mainAvailable count available pieces at Traced Main.</li>
 *   <li>a4 block: Main linked, Shopify not counting there, pieces exist → status blocked, receive 409
 *       BAZAR_SETUP_MAIN_NOT_COUNTING, nothing written; Main not linked → never blocked.</li>
 *   <li>b1 "What will you use Traced for?": /me 'ask' → PUT once → stored with intentFrom, audited; again → 409;
 *       an unknown value → 400.</li>
 *   <li>b2 owner only (manager / worker 403); another tenant's prefs untouched.</li>
 *   <li>b3 refused once setup is finished (409 SETUP_FINISHED) and when signup already set an intent (no 'ask').</li>
 *   <li>b4 routing: bazar → no address step, intro 'bazar'; orders / both → address step; bazar_enabled never
 *       written by the answer (V244 default).</li>
 *   <li>b5 public signup config: no token, bazarSignup true (BazarSetupFlagOffTest has the false side).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarWizardEntryTest {

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
        r.add("traced.catalog-backfill.enabled", () -> "false");
        r.add("traced.bazar.signup-enabled", () -> "true");   // BAZAR_SIGNUP_ENABLED=true
    }

    static final String PASSWORD = "pass1234";
    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @LocalServerPort int port;

    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyLocationGateway shopifyLocations;
    @MockBean ShopifyTokenProvider tokens;
    @MockBean ShopifyVariantStockGateway stock;
    @MockBean BostaGateway bosta;
    @MockBean BostaV2Client bostaV2;
    @MockBean EmailGateway email;
    @MockBean JobScheduler jobs;

    final ObjectMapper mapper = new ObjectMapper();
    final AtomicInteger seq = new AtomicInteger();
    JdbcTemplate owner;
    RestTemplate http;

    /** One bazar signup, connected and with a small catalog. */
    record Shop(UUID tenant, String token, UUID main, String mainGid, UUID store, UUID shirt, UUID shirtS, UUID shirtM,
                UUID scarf) {}

    @BeforeAll
    void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        http = new RestTemplate(new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()));
        http.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
    }

    @BeforeEach
    void mocks() {
        Mockito.reset(shopify, tokens, stock, jobs);
        when(tokens.getValidToken(any())).thenReturn("tok");
        when(shopify.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopify.inactiveMainLevels(any(), any(), any(), anyList())).thenReturn(Set.of());
        when(stock.topVariantsByStock(any(), any(), any(), any(), anyInt())).thenReturn(new Page(List.of(), false, null));
    }

    // ── a1 ───────────────────────────────────────────────────────────────────

    @Test
    void a1_gate_bazarAlone_ownerManagerWorker_bazarOff_otherTenant() throws Exception {
        Shop s = plain("a1");
        Map<String, Object> st = json(send(HttpMethod.GET, "/api/v1/bazar-setup", s.token(), null), HttpStatus.OK);
        assertThat(st).containsEntry("on", true).containsEntry("canSetUp", true).containsEntry("batch", null)
            .containsEntry("mainAvailable", 0).containsEntry("blocked", null).containsEntry("suggestStart", false);
        assertThat(send(HttpMethod.GET, "/api/v1/bazar-setup/products", s.token(), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        String manager = addUserAndLogin(s.tenant(), "manager", mail("a1m"));
        assertThat(json(send(HttpMethod.GET, "/api/v1/bazar-setup", manager, null), HttpStatus.OK)).containsEntry("canSetUp", false);
        assertThat(send(HttpMethod.GET, "/api/v1/bazar-setup/products", manager, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.POST, "/api/v1/bazar-setup/receive", manager, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String worker = addUserAndLogin(s.tenant(), "worker", mail("a1w"));
        for (String[] e : endpoints()) {
            assertThat(send(HttpMethod.valueOf(e[0]), e[1], worker, body(e)).getStatusCode()).as("worker %s", e[1])
                .isEqualTo(HttpStatus.FORBIDDEN);
        }

        // Received for A — another tenant (bazar on too) sees no batch and its receive can't use A's bazar.
        UUID event = event(s, "a1");
        receive(s, event, "req-a1", List.of(line(s.shirtS(), 2, true)), HttpStatus.OK);
        Shop other = plain("a1-other");
        assertThat(json(send(HttpMethod.GET, "/api/v1/bazar-setup", other.token(), null), HttpStatus.OK))
            .containsEntry("batch", null).containsEntry("mainAvailable", 0);
        receive(other, event, "req-a1-o", List.of(line(other.shirtS(), 1, true)), HttpStatus.NOT_FOUND);
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ?", other.tenant())).isZero();

        owner.update("UPDATE tenants SET bazar_enabled = false WHERE id = ?", s.tenant());
        for (String[] e : endpoints()) {
            assertThat(send(HttpMethod.valueOf(e[0]), e[1], s.token(), body(e)).getStatusCode()).as("bazar off %s", e[1])
                .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── a2 ───────────────────────────────────────────────────────────────────

    @Test
    void a2_me_bazarSetupAndShopifyConnected_ownerManagerOnly() throws Exception {
        String m = mail("a2");
        String token = signup(m, null);
        UUID tenant = tenantOf(m);
        owner.update("UPDATE tenants SET bazar_enabled = true WHERE id = ?", tenant);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", token, null), HttpStatus.OK))
            .containsEntry("bazarSetup", true).containsEntry("shopifyConnected", false);
        UUID store = connect(tenant);
        String manager = addUserAndLogin(tenant, "manager", mail("a2m"));
        String worker = addUserAndLogin(tenant, "worker", mail("a2w"));
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", token, null), HttpStatus.OK))
            .containsEntry("bazarSetup", true).containsEntry("shopifyConnected", true);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", manager, null), HttpStatus.OK))
            .containsEntry("bazarSetup", true).containsEntry("shopifyConnected", true);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", worker, null), HttpStatus.OK))
            .doesNotContainKey("bazarSetup").doesNotContainKey("shopifyConnected");
        owner.update("UPDATE stores SET status = 'disconnected' WHERE id = ?", store);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", token, null), HttpStatus.OK)).containsEntry("shopifyConnected", false);
        owner.update("UPDATE tenants SET bazar_enabled = false WHERE id = ?", tenant);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", token, null), HttpStatus.OK))
            .doesNotContainKey("bazarSetup").doesNotContainKey("shopifyConnected");
    }

    // ── a3 ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void a3_mainStock_atMainPerSize_mainAvailable() throws Exception {
        Shop s = plain("a3");
        UUID event = event(s, "a3");
        receive(s, event, "req-a3", List.of(line(s.shirtS(), 3, true), line(s.scarf(), 1, false)), HttpStatus.OK);
        await("SELECT count(*) FROM pieces WHERE tenant_id = ? AND status = 'available'", 4, s.tenant());
        Map<String, Object> out = json(send(HttpMethod.GET, "/api/v1/bazar-setup/products", s.token(), null), HttpStatus.OK);
        Map<String, Object> atMain = new HashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) out.get("products")) {
            for (Map<String, Object> v : (List<Map<String, Object>>) p.get("variants")) atMain.put((String) v.get("id"), v.get("atMain"));
        }
        assertThat(atMain).containsEntry(s.shirtS().toString(), 3).containsEntry(s.shirtM().toString(), 0)
            .containsEntry(s.scarf().toString(), 1);
        // A piece that left Main (another location) or isn't available doesn't count.
        UUID elsewhere = UUID.randomUUID();
        owner.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Branch')", elsewhere, s.tenant());
        owner.update("UPDATE pieces SET current_location_id = ? WHERE id = (SELECT id FROM pieces WHERE tenant_id = ? " +
            "AND variant_id = ? ORDER BY id LIMIT 1)", elsewhere, s.tenant(), s.shirtS());
        assertThat(json(send(HttpMethod.GET, "/api/v1/bazar-setup", s.token(), null), HttpStatus.OK))
            .containsEntry("mainAvailable", 3);
        Map<String, Object> after = json(send(HttpMethod.GET, "/api/v1/bazar-setup/products", s.token(), null), HttpStatus.OK);
        Map<String, Object> atMainAfter = new HashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) after.get("products")) {
            for (Map<String, Object> v : (List<Map<String, Object>>) p.get("variants")) atMainAfter.put((String) v.get("id"), v.get("atMain"));
        }
        assertThat(atMainAfter).containsEntry(s.shirtS().toString(), 2);
    }

    // ── a4 ───────────────────────────────────────────────────────────────────

    @Test
    void a4_block_mainLinkedNotCountingWithPieces_receive409_nothingWritten_notLinkedNeverBlocked() throws Exception {
        Shop s = plain("a4");
        UUID event = event(s, "a4");
        receive(s, event, "req-a4", List.of(line(s.shirtS(), 2, true)), HttpStatus.OK);
        json(send(HttpMethod.POST, "/api/v1/bazar-setup/lower-done", s.token(), null), HttpStatus.OK);
        // Shopify never confirmed the count at Traced Main: nothing applied there (no seed, no applied receiving).
        await("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'receiving_session' " +
            "AND status <> 'pending'", 1, s.tenant());
        owner.update("UPDATE shopify_inventory_adjustments SET status = 'failed' WHERE tenant_id = ?", s.tenant());
        assertThat(json(send(HttpMethod.GET, "/api/v1/bazar-setup", s.token(), null), HttpStatus.OK))
            .containsEntry("blocked", "main_not_counting");
        int receipts = count("SELECT count(*) FROM receipts WHERE tenant_id = ?", s.tenant());
        String prefs = owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, s.tenant());
        ResponseEntity<String> r = send(HttpMethod.POST, "/api/v1/bazar-setup/receive", s.token(),
            Map.of("eventId", event, "requestId", "req-a4-2", "lines", List.of(line(s.shirtM(), 1, true))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("BAZAR_SETUP_MAIN_NOT_COUNTING").contains("Finish setting up Traced Main first");
        assertThat(count("SELECT count(*) FROM receipts WHERE tenant_id = ?", s.tenant())).isEqualTo(receipts);
        assertThat(owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, s.tenant()))
            .isEqualTo(prefs);

        // Counting again (an applied receiving at Main) → open.
        owner.update("UPDATE shopify_inventory_adjustments SET status = 'applied' WHERE tenant_id = ?", s.tenant());
        assertThat(json(send(HttpMethod.GET, "/api/v1/bazar-setup", s.token(), null), HttpStatus.OK)).containsEntry("blocked", null);

        // Main not linked in Shopify → never blocked, even with pieces and nothing counted.
        owner.update("UPDATE shopify_inventory_adjustments SET status = 'failed' WHERE tenant_id = ?", s.tenant());
        owner.update("UPDATE locations SET shopify_sync_status = 'unsynced', shopify_location_id = NULL WHERE id = ?", s.main());
        assertThat(json(send(HttpMethod.GET, "/api/v1/bazar-setup", s.token(), null), HttpStatus.OK)).containsEntry("blocked", null);
    }

    // ── b1 ───────────────────────────────────────────────────────────────────

    @Test
    void b1_purpose_setOnce_audited_secondCall409_badValue400() throws Exception {
        String m = mail("b1");
        String token = signup(m, null);
        UUID tenant = tenantOf(m);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", token, null), HttpStatus.OK))
            .containsEntry("setupStep", "shopify").containsEntry("setupPurpose", "ask");
        assertThat(send(HttpMethod.PUT, "/api/v1/setup/purpose", token, Map.of("purpose", "everything")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(prefs(tenant)).isEqualTo(Map.of());
        assertThat(json(send(HttpMethod.PUT, "/api/v1/setup/purpose", token, Map.of("purpose", "both")), HttpStatus.OK))
            .containsEntry("purpose", "both").containsEntry("setupStep", "shopify");
        assertThat(prefs(tenant)).isEqualTo(Map.of("intent", "both", "intentFrom", "setup"));
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup_purpose_set'", tenant)).isEqualTo(1);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", token, null), HttpStatus.OK)).containsEntry("setupPurpose", "both");
        ResponseEntity<String> again = send(HttpMethod.PUT, "/api/v1/setup/purpose", token, Map.of("purpose", "orders"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("SETUP_PURPOSE_ALREADY_SET");
        assertThat(prefs(tenant)).containsEntry("intent", "both");
        // Still never a PATCH key.
        assertThat(send(HttpMethod.PATCH, "/api/v1/onboarding/prefs", token, Map.of("intent", "orders")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.PATCH, "/api/v1/onboarding/prefs", token, Map.of("intentFrom", "setup")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── b2 ───────────────────────────────────────────────────────────────────

    @Test
    void b2_purpose_ownerOnly_otherTenantUntouched() throws Exception {
        String m = mail("b2");
        signup(m, null);
        UUID tenant = tenantOf(m);
        String manager = addUserAndLogin(tenant, "manager", mail("b2m"));
        String worker = addUserAndLogin(tenant, "worker", mail("b2w"));
        assertThat(send(HttpMethod.PUT, "/api/v1/setup/purpose", manager, Map.of("purpose", "bazar")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.PUT, "/api/v1/setup/purpose", worker, Map.of("purpose", "bazar")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(prefs(tenant)).isEqualTo(Map.of());
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", worker, null), HttpStatus.OK)).doesNotContainKey("setupPurpose");

        String m2 = mail("b2o");
        String other = signup(m2, null);
        json(send(HttpMethod.PUT, "/api/v1/setup/purpose", other, Map.of("purpose", "orders")), HttpStatus.OK);
        assertThat(prefs(tenantOf(m2))).containsEntry("intent", "orders");
        assertThat(prefs(tenant)).isEqualTo(Map.of());
    }

    // ── b3 ───────────────────────────────────────────────────────────────────

    @Test
    void b3_purpose_refusedAfterSetupFinished_andWhenSignupSetIntent() throws Exception {
        String m = mail("b3");
        String token = signup(m, null);
        UUID tenant = tenantOf(m);
        owner.update("UPDATE tenants SET setup_completed_at = now() WHERE id = ?", tenant);
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", token, null), HttpStatus.OK)).doesNotContainKey("setupPurpose");
        ResponseEntity<String> r = send(HttpMethod.PUT, "/api/v1/setup/purpose", token, Map.of("purpose", "bazar"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("SETUP_FINISHED");
        assertThat(prefs(tenant)).isEqualTo(Map.of());

        // ?for=bazar / the App Store form already stored an intent: no question, and the answer is refused.
        String bm = mail("b3b");
        String bazar = signup(bm, "bazar");
        assertThat(json(send(HttpMethod.GET, "/api/v1/me", bazar, null), HttpStatus.OK)).doesNotContainKey("setupPurpose");
        ResponseEntity<String> b = send(HttpMethod.PUT, "/api/v1/setup/purpose", bazar, Map.of("purpose", "orders"));
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(b.getBody()).contains("SETUP_PURPOSE_ALREADY_SET");
        assertThat(prefs(tenantOf(bm))).isEqualTo(Map.of("intent", "bazar"));
    }

    // ── b4 ───────────────────────────────────────────────────────────────────

    @Test
    void b4_purpose_routing_bazarSkipsAddress_ordersAndBothTakeIt() throws Exception {
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String p : List.of("orders", "bazar", "both")) {
            String m = mail("b4" + p);
            String token = signup(m, null);
            UUID tenant = tenantOf(m);
            owner.update("UPDATE tenants SET bazar_enabled = false WHERE id = ?", tenant);
            json(send(HttpMethod.PUT, "/api/v1/setup/purpose", token, Map.of("purpose", p)), HttpStatus.OK);
            connect(tenant);
            owner.update("UPDATE tenants SET shopify_connected_at = now() WHERE id = ?", tenant);
            Map<String, Object> me = json(send(HttpMethod.GET, "/api/v1/me", token, null), HttpStatus.OK);
            if (p.equals("bazar")) {
                assertThat(me).as(p).containsEntry("setupStep", null).containsEntry("setupIntro", "bazar")
                    .containsEntry("setupPurpose", "bazar");
            } else {
                assertThat(me).as(p).containsEntry("setupStep", "address").containsEntry("setupPurpose", p);
            }
            // The answer never switches bazar (on by default since V244): left exactly as it was, no bazar audit row.
            assertThat(owner.queryForObject("SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenant))
                .as(p).isFalse();
            assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_enabled_at_signup'",
                tenant)).as(p).isZero();
            tokens.put(p, token);
        }
    }

    // ── b5 ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void b5_publicSignupConfig_noToken_flagOn() {
        ResponseEntity<Map> r = http.getForEntity(base() + "/api/v1/public/signup-config", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo(Map.of("bazarSignup", true));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A store that did NOT sign up for a bazar (no intent), then bazar on — the wizard-entry case. */
    private Shop plain(String tag) throws Exception {
        return shop(tag, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> prefs(UUID tenant) throws Exception {
        return mapper.readValue(owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, tenant), Map.class);
    }

    private Shop shop(String tag, String intent) throws Exception {
        String mail = mail(tag);
        String token = signup(mail, intent);
        UUID tenant = tenantOf(mail);
        owner.update("UPDATE tenants SET bazar_enabled = true WHERE id = ?", tenant);
        UUID store = connect(tenant);
        UUID main = owner.queryForObject("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment", UUID.class, tenant);
        String mainGid = "gid://shopify/Location/" + (70_000_000L + seq.incrementAndGet());
        owner.update("UPDATE locations SET shopify_location_id = ?, shopify_sync_status = 'linked' WHERE id = ?", mainGid, main);
        UUID shirt = product(tenant, store, "Linen shirt");
        UUID scarf = product(tenant, store, "Knit scarf");
        UUID shirtS = variant(tenant, shirt, "S", "SHIRT-S-" + tag);
        UUID shirtM = variant(tenant, shirt, "M", "SHIRT-M-" + tag);
        // Shopify's size order (S before M) — alphabetical would put M first.
        owner.update("UPDATE variants SET raw = jsonb_build_object('position', 1) WHERE id = ?", shirtS);
        owner.update("UPDATE variants SET raw = jsonb_build_object('position', 2) WHERE id = ?", shirtM);
        UUID scarfV = variant(tenant, scarf, "Default Title", "SCARF-" + tag);
        return new Shop(tenant, token, main, mainGid, store, shirt, shirtS, shirtM, scarfV);
    }

    private UUID product(UUID tenant, UUID store, String title) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            id, tenant, store, "gid://shopify/Product/" + id, title);
        return id;
    }

    private UUID variant(UUID tenant, UUID product, String title, String sku) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00)", id, tenant, product, gid(id), title, sku, "gid://shopify/InventoryItem/" + id);
        return id;
    }

    private static String gid(UUID variant) { return "gid://shopify/ProductVariant/" + variant; }

    private UUID event(Shop s, String tag) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Zamalek " + tag + " " + seq.incrementAndGet());
        body.put("startsOn", "2026-12-01");
        body.put("endsOn", "2026-12-03");
        body.put("locationName", "Zamalek " + tag + " " + seq.incrementAndGet());
        body.put("paymentMethods", List.of("cash"));
        body.put("sellerRoles", List.of("owner"));
        Map<String, Object> r = json(send(HttpMethod.POST, "/api/v1/bazar-events", s.token(), body), HttpStatus.CREATED);
        return UUID.fromString((String) r.get("id"));
    }

    private Map<String, Object> receive(Shop s, UUID event, String requestId, List<Map<String, Object>> lines,
                                        HttpStatus expect) throws Exception {
        return json(send(HttpMethod.POST, "/api/v1/bazar-setup/receive", s.token(),
            Map.of("eventId", event, "requestId", requestId, "lines", lines)), expect);
    }

    private static Map<String, Object> line(UUID variant, int qty, boolean labels) {
        return Map.of("variantId", variant, "quantity", qty, "labels", labels);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> otherByVariant(Map<String, Object> out) {
        Map<String, Object> m = new HashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) out.get("products")) {
            for (Map<String, Object> v : (List<Map<String, Object>>) p.get("variants")) m.put((String) v.get("id"), v.get("otherLocations"));
        }
        return m;
    }

    private static List<String[]> endpoints() {
        return List.of(new String[]{"GET", "/api/v1/bazar-setup"}, new String[]{"GET", "/api/v1/bazar-setup/products"},
            new String[]{"POST", "/api/v1/bazar-setup/receive"}, new String[]{"GET", "/api/v1/bazar-setup/lower"},
            new String[]{"GET", "/api/v1/bazar-setup/lower.csv"}, new String[]{"POST", "/api/v1/bazar-setup/lower-done"});
    }

    private static Object body(String[] e) {
        return "POST".equals(e[0]) ? Map.of("requestId", "x", "lines", List.of()) : null;
    }

    private String base() { return "http://localhost:" + port; }

    private static String mail(String tag) { return "b9b-" + tag + "-" + System.nanoTime() + "@b9b.test"; }

    @SuppressWarnings("unchecked")
    private String signup(String mail, String intent) {
        Map<String, Object> f = new HashMap<>();
        f.put("tenantName", "Nour Studio");
        f.put("name", "Nour Adel");
        f.put("email", mail);
        f.put("phone", "+201012345678");
        f.put("password", PASSWORD);
        f.put("consent", true);
        if (intent != null) f.put("intent", intent);
        ResponseEntity<Map> r = http.postForEntity(base() + "/api/v1/auth/signup", new HttpEntity<>(f, jsonHeaders()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("accessToken");
    }

    private UUID tenantOf(String mail) {
        return owner.queryForObject("SELECT tenant_id FROM users WHERE email = ?", UUID.class, mail);
    }

    private UUID connect(UUID tenant) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())",
            id, tenant, "b9b-" + seq.incrementAndGet() + "-" + System.nanoTime() + ".myshopify.com", SCOPES);
        return id;
    }

    @SuppressWarnings("unchecked")
    private String login(String mail) {
        ResponseEntity<Map> r = http.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", mail, "password", PASSWORD), jsonHeaders()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    private String addUserAndLogin(UUID tenant, String role, String mail) {
        String hash = owner.queryForObject("SELECT password_hash FROM users WHERE tenant_id = ? AND role = 'owner'", String.class, tenant);
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (gen_random_uuid(), ?, 'M', ?, ?, ?::user_role)",
            tenant, mail, hash, role);
        return login(mail);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(ResponseEntity<String> r, HttpStatus... expect) throws Exception {
        assertThat(expect).as(r.getBody()).contains((HttpStatus) HttpStatus.valueOf(r.getStatusCode().value()));
        return mapper.readValue(r.getBody(), Map.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, Object body) {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        return http.exchange(base() + path, method, new HttpEntity<>(body, h), String.class);
    }

    private static HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private int count(String sql, Object... args) { return owner.queryForObject(sql, Integer.class, args); }

    private void await(String sql, int n, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(sql, args) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + ": " + sql);
            Thread.sleep(25);
        }
    }
}
