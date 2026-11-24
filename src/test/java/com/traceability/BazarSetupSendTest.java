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
 * Bazar B9c — the guided setup puts its lowered batch on the bazar's send (POST /bazar-setup/send-batch →
 * BazarService.sendReceipts → TransferService.scanOutReceiptPiecesForBazar → the existing claimAndSend).
 * BAZAR_SIGNUP_ENABLED=true, whole app as app_user, real HTTP.
 *   s1 exactly the batch's pieces (labelled + count-only) go on THIS bazar's send — never older stock of the same size;
 *      price rows for every size; nothing to Shopify before Mark sent; status onSend / leftAtMain / transferStatus
 *   s2 the same request id replays (nothing more); a new id sends only what's left (a piece sent by count stays put)
 *   s3 before Done → 409 BAZAR_SETUP_LOWER_FIRST, no batch → 409 BAZAR_SETUP_NO_LIST; nothing moves
 *   s4 Mark sent goes through claimSend: Remove → its transfer_shopify_syncs row, Keep → none; after it the send is
 *      closed (409 BAZAR_SEND_CLOSED), after Start 409 BAZAR_EVENT_NOT_DRAFT
 *   s5 gate: normal signup / bazar off → 404; manager and worker 403; another tenant moves nothing
 *   g2 claimAndSend has exactly three callers; the setup moves nothing itself
 * (BazarSetupSendFlagOffTest: the setting off → 404, nothing moves.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSetupSendTest {

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

    // ── s1 ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void s1_sendsExactlyTheBatchsPieces_notOlderStockOfTheSameSize_countOnlyToo_pricesForEverySize() throws Exception {
        Shop s = shop("s1");
        // An older batch of the SAME sizes, lowered and left at Main (never sent): older pieces a send by count would pick.
        UUID oldEvent = event(s, "s1-old", null);
        receive(s, oldEvent, "req-s1-old", List.of(line(s.shirtS(), 2, true), line(s.scarf(), 2, false)), HttpStatus.OK);
        lowerDone(s);
        UUID event = event(s, "s1", null);
        Map<String, Object> st = (Map<String, Object>) receive(s, event, "req-s1",
            List.of(line(s.shirtS(), 3, true), line(s.scarf(), 2, false)), HttpStatus.OK).get("batch");
        UUID labelled = UUID.fromString((String) st.get("labelledReceiptId"));
        UUID countOnly = UUID.fromString((String) st.get("countOnlyReceiptId"));
        lowerDone(s);
        UUID transfer = transferOf(event);

        Map<String, Object> r = json(sendBatch(s.token(), "s1-a"), HttpStatus.OK);
        assertThat(r).containsEntry("sent", 5).containsEntry("left", 0).containsEntry("replayed", false);
        // Exactly the batch's pieces, labelled and count-only, on THIS bazar's send.
        assertThat(count("SELECT count(*) FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id " +
            "WHERE tp.transfer_id = ? AND p.receipt_id IN (?, ?) AND p.status = 'out_on_transfer'", transfer, labelled, countOnly))
            .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM transfer_pieces WHERE transfer_id = ?", transfer)).isEqualTo(5);
        // The older batch stays at Main, untouched.
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ? AND receipt_id NOT IN (?, ?) AND status = 'available' " +
            "AND current_location_id = ?", s.tenant(), labelled, countOnly, s.main())).isEqualTo(4);
        // Through the same claim-and-send as a scan: the ledger event, the line totals.
        assertThat(count("SELECT count(*) FROM piece_events WHERE event_type = 'transferred_out' " +
            "AND metadata->>'transfer_id' = ? AND metadata->>'by' = 'receipt'", transfer.toString())).isEqualTo(5);
        assertThat(count("SELECT COALESCE(sum(qty_out), 0)::int FROM transfer_lines WHERE transfer_id = ?", transfer)).isEqualTo(5);
        // Every size sent has its price row (Prices lists them).
        assertThat(count("SELECT count(*) FROM bazar_event_prices WHERE event_id = ? AND variant_id IN (?, ?)",
            event, s.shirtS(), s.scarf())).isEqualTo(2);
        List<Map<String, Object>> prices = mapper.readValue(
            send(HttpMethod.GET, "/api/v1/bazar-events/" + event + "/prices", s.token(), null).getBody(), List.class);
        assertThat(prices).extracting(p -> p.get("variant_id")).containsExactlyInAnyOrder(s.shirtS().toString(), s.scarf().toString());
        // Nothing reaches Shopify before Mark sent.
        assertThat(count("SELECT count(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", transfer)).isZero();
        // The status says where the batch is (the wizard resumes from it).
        Map<String, Object> b = (Map<String, Object>) json(send(HttpMethod.GET, "/api/v1/bazar-setup", s.token(), null),
            HttpStatus.OK).get("batch");
        assertThat(b).containsEntry("onSend", 5).containsEntry("leftAtMain", 0).containsEntry("transferStatus", "preparing");
    }

    // ── s2 ───────────────────────────────────────────────────────────────────

    @Test
    void s2_sameRequestId_replays_nothingMore_aNewIdSendsOnlyWhatsLeft() throws Exception {
        Shop s = shop("s2");
        UUID event = event(s, "s2", null);
        receive(s, event, "req-s2", List.of(line(s.shirtM(), 2, true), line(s.scarf(), 1, false)), HttpStatus.OK);
        lowerDone(s);
        UUID transfer = transferOf(event);
        // One piece already went on the send by count (Get ready) before the wizard's Next.
        assertThat(json(send(HttpMethod.POST, "/api/v1/bazar-events/" + event + "/scan-out-by-count", s.token(),
            Map.of("variant_id", s.scarf(), "quantity", 1, "client_request_id", "s2-count")), HttpStatus.OK).get("success")).isEqualTo(true);

        assertThat(json(sendBatch(s.token(), "s2-a"), HttpStatus.OK)).containsEntry("sent", 2).containsEntry("left", 0);
        assertThat(json(sendBatch(s.token(), "s2-a"), HttpStatus.OK)).containsEntry("sent", 2).containsEntry("replayed", true);
        assertThat(json(sendBatch(s.token(), "s2-b"), HttpStatus.OK)).containsEntry("sent", 0).containsEntry("left", 0);
        assertThat(count("SELECT count(*) FROM transfer_pieces WHERE transfer_id = ?", transfer)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM piece_events WHERE event_type = 'transferred_out' AND metadata->>'transfer_id' = ?",
            transfer.toString())).isEqualTo(3);
    }

    // ── s3 ───────────────────────────────────────────────────────────────────

    @Test
    void s3_beforeDone_409LowerFirst_noBatch_409NoList_nothingMoved() throws Exception {
        Shop s = shop("s3");
        ResponseEntity<String> none = sendBatch(s.token(), "s3-0");
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(none.getBody()).contains("BAZAR_SETUP_NO_LIST");
        UUID event = event(s, "s3", null);
        receive(s, event, "req-s3", List.of(line(s.shirtS(), 2, true)), HttpStatus.OK);
        ResponseEntity<String> early = sendBatch(s.token(), "s3-a");
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody()).contains("BAZAR_SETUP_LOWER_FIRST");
        assertThat(count("SELECT count(*) FROM transfer_pieces WHERE transfer_id = ?", transferOf(event))).isZero();
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ? AND status = 'available'", s.tenant())).isEqualTo(2);
    }

    // ── s4 ───────────────────────────────────────────────────────────────────

    @Test
    void s4_markSent_goesThroughClaimSend_removeWritesItsRow_keepWritesNone_thenStart() throws Exception {
        for (String mode : List.of("remove", "leave")) {
            Shop s = shop("s4-" + mode);
            UUID event = event(s, "s4-" + mode, mode);
            receive(s, event, "req-s4-" + mode, List.of(line(s.shirtS(), 2, true), line(s.scarf(), 1, false)), HttpStatus.OK);
            lowerDone(s);
            json(sendBatch(s.token(), "s4-" + mode), HttpStatus.OK);
            UUID transfer = transferOf(event);
            json(send(HttpMethod.POST, "/api/v1/bazar-events/" + event + "/mark-sent", s.token(), Map.of()), HttpStatus.OK);
            assertThat(count("SELECT count(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", transfer))
                .as("claimSend row, mode %s", mode).isEqualTo("remove".equals(mode) ? 1 : 0);
            // The send is closed: the wizard can't add to it any more.
            ResponseEntity<String> closed = sendBatch(s.token(), "s4-late-" + mode);
            assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(closed.getBody()).contains("BAZAR_SEND_CLOSED");
            json(send(HttpMethod.POST, "/api/v1/bazar-events/" + event + "/start", s.token(), Map.of()), HttpStatus.OK);
            ResponseEntity<String> live = sendBatch(s.token(), "s4-live-" + mode);
            assertThat(live.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(live.getBody()).contains("BAZAR_EVENT_NOT_DRAFT");
        }
    }

    // ── s5 ───────────────────────────────────────────────────────────────────

    @Test
    void s5_gate_normalSignup404_bazarOff404_managerAndWorker403_otherTenantMovesNothing() throws Exception {
        String normalMail = mail("s5n");
        String normal = signup(normalMail, null);
        connect(tenantOf(normalMail));
        assertThat(sendBatch(normal, "s5-n").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Shop s = shop("s5");
        UUID event = event(s, "s5", null);
        receive(s, event, "req-s5", List.of(line(s.shirtS(), 2, true)), HttpStatus.OK);
        lowerDone(s);
        owner.update("UPDATE tenants SET bazar_enabled = false WHERE id = ?", s.tenant());
        assertThat(sendBatch(s.token(), "s5-off").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        owner.update("UPDATE tenants SET bazar_enabled = true WHERE id = ?", s.tenant());
        assertThat(sendBatch(addUserAndLogin(s.tenant(), "manager", mail("s5m")), "s5-m").getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(sendBatch(addUserAndLogin(s.tenant(), "worker", mail("s5w")), "s5-w").getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        // Another bazar signup: no batch of its own → 409, and the first tenant's pieces never move.
        Shop other = shop("s5-other");
        ResponseEntity<String> r = sendBatch(other.token(), "s5-o");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).contains("BAZAR_SETUP_NO_LIST");
        assertThat(count("SELECT count(*) FROM transfer_pieces WHERE tenant_id = ?", s.tenant())).isZero();
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ? AND status = 'available'", s.tenant())).isEqualTo(2);
    }

    // ── g2 ───────────────────────────────────────────────────────────────────

    /** claimAndSend — the one send-out writer — has exactly three callers: scan, send by count, the B9c receipt send. */
    @Test
    void g2_claimAndSend_hasExactlyThreeCallers() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/traceability/inventory/TransferService.java"))
            .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        int calls = 0;
        for (int i = src.indexOf("claimAndSend("); i >= 0; i = src.indexOf("claimAndSend(", i + 1)) calls++;
        assertThat(calls).as("1 definition + 3 calls").isEqualTo(4);
        int receiptSend = src.indexOf("public ReceiptSendResult scanOutReceiptPiecesForBazar(");
        int next = src.indexOf("private int receiptPiecesLeft(", receiptSend);
        assertThat(src.substring(receiptSend, next)).contains("claimAndSend(").doesNotContain("ledger.").doesNotContain("shopify");
        // The setup itself moves nothing — it asks BazarService, the one caller of the bazar transfer entry points.
        String setup = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/traceability/bazar/BazarSetupService.java"));
        assertThat(setup).contains("bazar.sendReceipts(").doesNotContain("transfers.").doesNotContain("ForBazar(");
    }

    private ResponseEntity<String> sendBatch(String token, String requestId) {
        return send(HttpMethod.POST, "/api/v1/bazar-setup/send-batch", token, Map.of("requestId", requestId));
    }

    private void lowerDone(Shop s) throws Exception {
        json(send(HttpMethod.POST, "/api/v1/bazar-setup/lower-done", s.token(), null), HttpStatus.OK);
    }

    private UUID transferOf(UUID event) {
        return owner.queryForObject("SELECT transfer_id FROM bazar_events WHERE id = ?", UUID.class, event);
    }

    private UUID event(Shop s, String tag, String mode) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Zamalek " + tag + " " + seq.incrementAndGet());
        body.put("startsOn", "2026-12-01");
        body.put("endsOn", "2026-12-03");
        body.put("locationName", "Zamalek " + tag + " " + seq.incrementAndGet());
        body.put("paymentMethods", List.of("cash"));
        body.put("sellerRoles", List.of("owner"));
        if (mode != null) body.put("shopifySyncMode", mode);
        Map<String, Object> r = json(send(HttpMethod.POST, "/api/v1/bazar-events", s.token(), body), HttpStatus.CREATED);
        return UUID.fromString((String) r.get("id"));
    }

    private Shop shop(String tag) throws Exception {
        String mail = mail(tag);
        String token = signup(mail, "bazar");
        UUID tenant = tenantOf(mail);
        assertThat(owner.queryForObject("SELECT bazar_enabled FROM tenants WHERE id = ?", Boolean.class, tenant)).isTrue();
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
