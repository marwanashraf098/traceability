package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.shopify.ShopifyGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Onboarding O1 (V220) — the progress model. The app runs as app_user (RLS on, like production); every call
 * goes through real HTTP (JWT filter → controller → service transaction).
 *
 *   s1–s5   each setup step derived from its own rows (negative, then the row that makes it done)
 *   t1–t9   each task's "tried" signal, same shape; places present only while places are on
 *   c1      card: default 'full'; PATCH slim → slim; the legacy POST /onboarding/dismiss → hidden
 *   w1      whitelist: unknown key / bad value / empty body → 400 and NOTHING written (prefs + audit unchanged),
 *           also when a valid key rides along with a bad one
 *   r1      roles: a worker gets 403 on GET /progress and PATCH /prefs; a manager may do both
 *   u1      POST /me/ui-state: appends, idempotent, shows on GET /me; unknown tour / page / field → 400, nothing written
 *   u2      station mode: after POST /auth/pin the row is the switched-in worker's, never the owner's
 *   i1      app_user isolation on user_ui_state: own tenant visible (positive control), other tenant's row
 *           invisible, no GUC → nothing, cross-tenant insert refused, no DELETE, no UPDATE of user_id
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingProgressTest {

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
    }

    @LocalServerPort int port;
    @Autowired JwtService      jwt;
    @Autowired PasswordEncoder encoder;
    @MockBean  ShopifyGateway  shopify;
    @MockBean  BostaGateway    bosta;
    @MockBean  JobScheduler    jobScheduler;

    final ObjectMapper mapper = new ObjectMapper();
    final HttpClient   http   = HttpClient.newHttpClient();
    JdbcTemplate owner;   // postgres — fixtures and assertions only

    record Tenant(UUID id, UUID location, UUID owner, UUID manager, UUID worker) {}

    @BeforeAll
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
    }

    // ── Setup steps ──────────────────────────────────────────────────────────

    @Test @Order(1)
    void s1_shopify_fromTheStickyStamp() throws Exception {
        Tenant t = tenant("s1");
        assertStep(t, "shopify", false);
        UUID s = store(t, "connected");                       // the V186 trigger stamps shopify_connected_at
        assertStep(t, "shopify", true);
        owner.update("UPDATE stores SET status = 'disconnected'::store_status WHERE id = ?", s);
        assertStep(t, "shopify", true);                        // sticky: a later disconnect never un-does it
    }

    @Test @Order(2)
    void s2_address_needsAllThreeParts() throws Exception {
        Tenant t = tenant("s2");
        owner.update("UPDATE locations SET address_province_code = 'C', address_city = 'Nasr City' WHERE id = ?", t.location());
        assertStep(t, "address", false);
        owner.update("UPDATE locations SET address_street = '12 Abbas El Akkad' WHERE id = ?", t.location());
        assertStep(t, "address", true);
    }

    @Test @Order(3)
    void s3_firstReceiving_finalizedAndNotReversed() throws Exception {
        Tenant t = tenant("s3");
        receipt(t, "reversed", "print_stick");
        receipt(t, "open", "print_stick");
        assertStep(t, "first_receiving", false);
        receipt(t, "finalized", "count_only");
        assertStep(t, "first_receiving", true);
    }

    @Test @Order(4)
    void s4_courier_bostaOrOtherCouriersOrSimulated_andSkipped() throws Exception {
        Tenant bostaT = tenant("s4b");
        owner.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                     "VALUES (?, 'bosta', 'x', ?, 'disconnected')", bostaT.id(), "wh-" + bostaT.id());
        assertStep(bostaT, "courier", false);
        owner.update("UPDATE courier_accounts SET status = 'active' WHERE tenant_id = ?", bostaT.id());
        assertStep(bostaT, "courier", true);

        Tenant generic = tenant("s4g");
        assertStep(generic, "courier", false);
        owner.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", generic.id());
        assertStep(generic, "courier", true);

        Tenant sim = tenant("s4s");
        owner.update("INSERT INTO tenant_courier_simulation (tenant_id, note) VALUES (?, 'review')", sim.id());
        assertStep(sim, "courier", true);

        // Skipped: reported only while the step is not done.
        Tenant skip = tenant("s4k");
        assertThat(patchPrefs(skip, "owner", Map.of("skipped", List.of("courier"))).statusCode()).isEqualTo(200);
        Map<String, Object> st = stepOf(progress(skip, "owner"), "courier");
        assertThat(st.get("done")).isEqualTo(false);
        assertThat(st.get("skipped")).isEqualTo(true);
        owner.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", skip.id());
        st = stepOf(progress(skip, "owner"), "courier");
        assertThat(st.get("done")).isEqualTo(true);
        assertThat(st.get("skipped")).isEqualTo(false);
        // A non-skippable step is never reported skipped.
        assertThat(stepOf(progress(skip, "owner"), "shopify").get("skippable")).isEqualTo(false);
    }

    @Test @Order(5)
    void s5_testLabel_fromPrefOrReprint() throws Exception {
        Tenant pref = tenant("s5p");
        assertStep(pref, "test_label", false);
        assertThat(patchPrefs(pref, "owner", Map.of("testLabelPrinted", true)).statusCode()).isEqualTo(200);
        assertStep(pref, "test_label", true);

        Tenant reprint = tenant("s5r");
        UUID r = receipt(reprint, "finalized", "print_stick");
        assertStep(reprint, "test_label", false);   // a finalized receipt alone is not a print at read time
        owner.update("INSERT INTO label_reprints (tenant_id, receipt_id, reprinted_by, piece_count) VALUES (?, ?, ?, 3)",
            reprint.id(), r, reprint.owner());
        assertStep(reprint, "test_label", true);
    }

    // ── Tasks ────────────────────────────────────────────────────────────────

    @Test @Order(10)
    void t1_receive_sameAsFirstReceiving() throws Exception {
        Tenant t = tenant("t1");
        receipt(t, "reversed", "print_stick");
        assertTask(t, "receive", false);
        receipt(t, "finalized", "print_stick");
        assertTask(t, "receive", true);
    }

    @Test @Order(11)
    void t2_pack_fromAPackPieceEvent() throws Exception {
        Tenant t = tenant("t2");
        String p = piece(t);
        event(t, p, "received");
        assertTask(t, "pack", false);
        event(t, p, "pack");
        assertTask(t, "pack", true);
    }

    @Test @Order(11)
    void t2b_pack_lookupExchangeDispatchAloneDoesNotCount() throws Exception {
        Tenant t = tenant("t2b");
        String p = piece(t);
        event(t, p, "received");
        owner.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, metadata) " +
                     "VALUES (?, ?, 'pack', ?, '{\"event\":\"lookup_exchange_dispatch\"}'::jsonb)", t.id(), p, t.owner());
        assertTask(t, "pack", false);
        event(t, p, "pack");
        assertTask(t, "pack", true);
    }

    @Test @Order(12)
    void t3_pickup_closedSessionOnly() throws Exception {
        Tenant t = tenant("t3");
        owner.update("INSERT INTO pickups (tenant_id, session_status) VALUES (?, 'open')", t.id());
        assertTask(t, "pickup", false);
        owner.update("INSERT INTO pickups (tenant_id, session_status) VALUES (?, 'closed')", t.id());
        assertTask(t, "pickup", true);
    }

    @Test @Order(13)
    void t4_returns_closedSessionOrReturnReceivedEvent() throws Exception {
        Tenant session = tenant("t4s");
        owner.update("INSERT INTO return_sessions (tenant_id, status, opened_by) VALUES (?, 'abandoned', ?)",
            session.id(), session.owner());
        assertTask(session, "returns", false);
        owner.update("INSERT INTO return_sessions (tenant_id, status, opened_by, closed_by, closed_at) " +
                     "VALUES (?, 'closed', ?, ?, now())", session.id(), session.owner(), session.owner());
        assertTask(session, "returns", true);

        Tenant ev = tenant("t4e");
        String p = piece(ev);
        assertTask(ev, "returns", false);
        event(ev, p, "return_received");
        assertTask(ev, "returns", true);
    }

    @Test @Order(14)
    void t5_transfer_notCancelled() throws Exception {
        Tenant t = tenant("t5");
        UUID dest = owner.queryForObject("SELECT id FROM locations WHERE tenant_id = ? AND NOT is_fulfillment",
            UUID.class, t.id());
        owner.update("INSERT INTO transfers (tenant_id, transfer_type, destination_location_id, status, created_by, cancelled_at) " +
                     "VALUES (?, 'showroom', ?, 'cancelled', ?, now())", t.id(), dest, t.owner());
        assertTask(t, "transfer", false);
        owner.update("INSERT INTO transfers (tenant_id, transfer_type, destination_location_id, status, created_by) " +
                     "VALUES (?, 'showroom', ?, 'preparing', ?)", t.id(), dest, t.owner());
        assertTask(t, "transfer", true);
    }

    @Test @Order(15)
    void t6_stockTake_notCancelled() throws Exception {
        Tenant t = tenant("t6");
        owner.update("INSERT INTO stock_take_sessions (tenant_id, scope_type, location_id, opened_by, status) " +
                     "VALUES (?, 'all', ?, ?, 'cancelled')", t.id(), t.location(), t.owner());
        assertTask(t, "stock_take", false);
        owner.update("INSERT INTO stock_take_sessions (tenant_id, scope_type, location_id, opened_by) " +
                     "VALUES (?, 'all', ?, ?)", t.id(), t.location(), t.owner());
        assertTask(t, "stock_take", true);
    }

    @Test @Order(16)
    void t7_portal_enabled() throws Exception {
        Tenant t = tenant("t7");
        assertTask(t, "portal", false);
        owner.update("UPDATE tenants SET portal_enabled = true WHERE id = ?", t.id());
        assertTask(t, "portal", true);
    }

    @Test @Order(17)
    void t8_team_moreThanOneActiveUser() throws Exception {
        Tenant t = tenant("t8");
        owner.update("UPDATE users SET active = false WHERE tenant_id = ? AND id <> ?", t.id(), t.owner());
        assertTask(t, "team", false);
        owner.update("UPDATE users SET active = true WHERE id = ?", t.worker());
        // The worker can't call /progress — the owner's token reads it.
        assertTask(t, "team", true);
    }

    @Test @Order(18)
    void t9_places_onlyWhenPlacesOn() throws Exception {
        Tenant t = tenant("t9");
        assertThat(taskKeys(progress(t, "owner"))).doesNotContain("places");
        owner.update("UPDATE tenants SET places_enabled = true WHERE id = ?", t.id());
        assertTask(t, "places", false);
        UUID zone = UUID.randomUUID();
        owner.update("INSERT INTO bins (id, tenant_id, location_id, kind, code, scan_code) VALUES (?, ?, ?, 'zone', 'Z1', 'Q100001')",
            zone, t.id(), t.location());
        assertTask(t, "places", false);                       // a zone alone isn't a shelf or box
        owner.update("INSERT INTO bins (tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
                     "VALUES (?, ?, 'box', ?, 'zone', 'B1', 'Q100002')", t.id(), t.location(), zone);
        assertTask(t, "places", true);
    }

    // ── Card + whitelist + roles ─────────────────────────────────────────────

    @Test @Order(20)
    void c1_card_defaultFull_patch_andLegacyDismiss() throws Exception {
        Tenant t = tenant("c1");
        assertThat(progress(t, "owner").get("card")).isEqualTo("full");
        HttpResponse<String> r = patchPrefs(t, "manager", Map.of("card", "slim"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(mapper.readValue(r.body(), Map.class).get("card")).isEqualTo("slim");
        assertThat(progress(t, "owner").get("card")).isEqualTo("slim");
        assertThat(send("POST", "/api/v1/onboarding/dismiss", token(t, "owner"), null).statusCode()).isEqualTo(204);
        assertThat(progress(t, "owner").get("card")).isEqualTo("hidden");
        // "Show again" goes through the same writer.
        patchPrefs(t, "owner", Map.of("card", "full"));
        assertThat(progress(t, "owner").get("card")).isEqualTo("full");
    }

    @Test @Order(21)
    void w1_whitelist_rejectsAndWritesNothing() throws Exception {
        Tenant t = tenant("w1");
        patchPrefs(t, "owner", Map.of("card", "slim", "labelingPace", 150));
        String before = prefs(t);
        int audits = audits(t);

        List<Object> bad = new ArrayList<>(List.of(
            Map.of("color", "blue"),                                   // unknown key
            Map.of("card", "tiny"),                                    // bad value
            Map.of("card", 3),
            Map.of("skipped", List.of("courier", "shopify")),          // shopify isn't skippable
            Map.of("skipped", "courier"),                              // not an array
            Map.of("pickPackChosen", "yes"),
            Map.of("testLabelPrinted", 1),
            Map.of("labelingPace", 0),
            Map.of("labelingPace", 10_001),
            Map.of("labelingPace", 12.5),
            Map.of(),                                                  // empty body
            new LinkedHashMap<>(Map.of("card", "hidden", "bogus", true)))); // a valid key riding with a bad one
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("card", null);
        bad.add(nullValue);
        for (Object body : bad) {
            HttpResponse<String> r = send("PATCH", "/api/v1/onboarding/prefs", token(t, "owner"), body);
            assertThat(r.statusCode()).as("body %s → %s", body, r.body()).isEqualTo(400);
        }
        assertThat(prefs(t)).as("nothing written").isEqualTo(before);
        assertThat(audits(t)).as("no audit row").isEqualTo(audits);

        // Positive control: a valid patch writes, merges and is audited.
        HttpResponse<String> ok = patchPrefs(t, "owner",
            Map.of("skipped", List.of("test_label", "test_label"), "pickPackChosen", true));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = mapper.readValue(prefs(t), Map.class);
        assertThat(stored).containsEntry("card", "slim").containsEntry("labelingPace", 150)
            .containsEntry("pickPackChosen", true).containsEntry("skipped", List.of("test_label"));
        assertThat(audits(t)).isEqualTo(audits + 1);
    }

    @Test @Order(22)
    void r1_roles_workerForbidden_managerAllowed() throws Exception {
        Tenant t = tenant("r1");
        assertThat(send("GET", "/api/v1/onboarding/progress", token(t, "worker"), null).statusCode()).isEqualTo(403);
        assertThat(send("PATCH", "/api/v1/onboarding/prefs", token(t, "worker"), Map.of("card", "hidden")).statusCode())
            .isEqualTo(403);
        assertThat(prefs(t)).isEqualTo("{}");
        assertThat(send("GET", "/api/v1/onboarding/progress", token(t, "manager"), null).statusCode()).isEqualTo(200);
    }

    // ── Per-user UI state ────────────────────────────────────────────────────

    @Test @Order(30)
    void u1_uiState_appendIdempotent_whitelisted() throws Exception {
        Tenant t = tenant("u1");
        String tok = token(t, "manager");
        assertThat(uiState(tok)).containsEntry("toursDone", List.of()).containsEntry("pagesSeen", List.of());

        assertThat(send("POST", "/api/v1/me/ui-state", tok, Map.of("tourDone", "pickups")).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/me/ui-state", tok, Map.of("tourDone", "pickups", "pageSeen", "receiving"))
            .statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/v1/me/ui-state", tok, Map.of("pageSeen", "stock_take")).statusCode()).isEqualTo(200);
        assertThat(uiState(tok)).containsEntry("toursDone", List.of("pickups"))
            .containsEntry("pagesSeen", List.of("receiving", "stock_take"));

        String before = rowOf(t.manager());
        for (Object body : List.of(Map.of("tourDone", "dashboard"), Map.of("pageSeen", "/receiving"),
                                   Map.of("tourDone", 1), Map.of("pageSeen", "orders", "extra", "x"), Map.of())) {
            HttpResponse<String> r = send("POST", "/api/v1/me/ui-state", tok, body);
            assertThat(r.statusCode()).as("body %s → %s", body, r.body()).isEqualTo(400);
        }
        assertThat(rowOf(t.manager())).as("nothing written").isEqualTo(before);
        // Other users of the tenant are untouched.
        assertThat(uiState(token(t, "owner"))).containsEntry("toursDone", List.of());
    }

    @Test @Order(31)
    void u2_stationMode_writesUnderTheSwitchedInWorker() throws Exception {
        Tenant t = tenant("u2");
        owner.update("UPDATE users SET pin_code = ? WHERE id = ?", encoder.encode("4321"), t.worker());
        String ownerTok = token(t, "owner");
        HttpResponse<String> pin = send("POST", "/api/v1/auth/pin", ownerTok,
            Map.of("userId", t.worker().toString(), "pin", "4321"));
        assertThat(pin.statusCode()).as(pin.body()).isEqualTo(200);
        String workerTok = (String) mapper.readValue(pin.body(), Map.class).get("accessToken");

        assertThat(send("POST", "/api/v1/me/ui-state", workerTok, Map.of("tourDone", "scan_returns")).statusCode())
            .isEqualTo(200);
        assertThat(owner.queryForList("SELECT user_id FROM user_ui_state WHERE tenant_id = ?", UUID.class, t.id()))
            .containsExactly(t.worker());
        assertThat(uiState(workerTok)).containsEntry("toursDone", List.of("scan_returns"));
        assertThat(uiState(ownerTok)).containsEntry("toursDone", List.of());
    }

    @Test @Order(32)
    void i1_appUserIsolation_onUserUiState() throws Exception {
        Tenant a = tenant("i1a"), b = tenant("i1b");
        owner.update("INSERT INTO user_ui_state (tenant_id, user_id, tours_done) VALUES (?, ?, '{pickups}')", a.id(), a.owner());
        owner.update("INSERT INTO user_ui_state (tenant_id, user_id, tours_done) VALUES (?, ?, '{transfers}')", b.id(), b.owner());

        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_user", "testpw")) {
            c.setAutoCommit(false);
            // No tenant set → nothing.
            assertThat(count(c, "SELECT count(*) FROM user_ui_state")).isZero();
            c.rollback();

            setTenant(c, a.id());
            // Positive control: A's own row, same tenant, visible.
            assertThat(count(c, "SELECT count(*) FROM user_ui_state WHERE user_id = '" + a.owner() + "'")).isEqualTo(1);
            assertThat(count(c, "SELECT count(*) FROM user_ui_state")).as("only A's row").isEqualTo(1);
            assertThat(count(c, "SELECT count(*) FROM user_ui_state WHERE tenant_id = '" + b.id() + "'")).isZero();
            // Own-tenant insert works (positive control for the WITH CHECK below).
            exec(c, "INSERT INTO user_ui_state (tenant_id, user_id) VALUES ('" + a.id() + "', '" + a.manager() + "')");
            c.commit();

            setTenant(c, a.id());
            assertThatThrownBy(() -> exec(c, "INSERT INTO user_ui_state (tenant_id, user_id) VALUES ('"
                    + b.id() + "', '" + b.manager() + "')"))
                .as("another tenant's row is refused").isInstanceOf(SQLException.class);
            c.rollback();

            setTenant(c, a.id());
            assertThatThrownBy(() -> exec(c, "DELETE FROM user_ui_state WHERE user_id = '" + a.owner() + "'"))
                .as("no DELETE grant").isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            c.rollback();

            setTenant(c, a.id());
            assertThatThrownBy(() -> exec(c, "UPDATE user_ui_state SET user_id = '" + a.manager() + "' WHERE user_id = '"
                    + a.owner() + "'"))
                .as("user_id is not updatable").isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            c.rollback();

            setTenant(c, a.id());
            assertThat(exec(c, "UPDATE user_ui_state SET pages_seen = '{orders}' WHERE tenant_id = '" + b.id() + "'"))
                .as("B's row can't be updated from A").isZero();
            c.rollback();
        }
        assertThat(owner.queryForObject("SELECT tours_done[1] FROM user_ui_state WHERE user_id = ?", String.class, b.owner()))
            .isEqualTo("transfers");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertStep(Tenant t, String key, boolean done) throws Exception {
        assertThat(stepOf(progress(t, "owner"), key).get("done")).as("step %s", key).isEqualTo(done);
    }

    @SuppressWarnings("unchecked")
    private void assertTask(Tenant t, String key, boolean tried) throws Exception {
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) progress(t, "owner").get("tasks");
        Map<String, Object> task = tasks.stream().filter(x -> key.equals(x.get("key"))).findFirst().orElseThrow();
        assertThat(task.get("tried")).as("task %s", key).isEqualTo(tried);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepOf(Map<String, Object> progress, String key) {
        return ((List<Map<String, Object>>) progress.get("steps")).stream()
            .filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> taskKeys(Map<String, Object> progress) {
        return ((List<Map<String, Object>>) progress.get("tasks")).stream().map(x -> x.get("key")).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> progress(Tenant t, String role) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/onboarding/progress", token(t, role), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return mapper.readValue(r.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> uiState(String token) throws Exception {
        HttpResponse<String> r = send("GET", "/api/v1/me", token, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return (Map<String, Object>) mapper.readValue(r.body(), Map.class).get("uiState");
    }

    private HttpResponse<String> patchPrefs(Tenant t, String role, Map<String, Object> body) throws Exception {
        return send("PATCH", "/api/v1/onboarding/prefs", token(t, role), body);
    }

    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String prefs(Tenant t) {
        return owner.queryForObject("SELECT onboarding_prefs::text FROM tenants WHERE id = ?", String.class, t.id());
    }

    private int audits(Tenant t) {
        return owner.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'onboarding_prefs_set'",
            Integer.class, t.id());
    }

    private String rowOf(UUID user) {
        List<String> rows = owner.queryForList(
            "SELECT tours_done::text || '|' || pages_seen::text || '|' || updated_at::text FROM user_ui_state WHERE user_id = ?",
            String.class, user);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String token(Tenant t, String role) {
        UUID user = switch (role) { case "owner" -> t.owner(); case "manager" -> t.manager(); default -> t.worker(); };
        return jwt.issueAccessToken(user, t.id(), role);
    }

    private Tenant tenant(String tag) {
        UUID id = UUID.randomUUID(), loc = UUID.randomUUID();
        owner.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "Progress " + tag);
        owner.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                     "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", loc, id);
        owner.update("INSERT INTO locations (tenant_id, name, type) VALUES (?, 'Shop', 'warehouse')", id);
        return new Tenant(id, loc, user(id, "owner", tag), user(id, "manager", tag), user(id, "worker", tag));
    }

    private UUID user(UUID tenant, String role, String tag) {
        UUID u = UUID.randomUUID();
        owner.update("INSERT INTO users (id, tenant_id, name, email, password_hash, pin_code, role, active) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?::user_role, true)",
            u, tenant, role + " " + tag, role + "-" + tag + "-" + u + "@test.local",
            "worker".equals(role) ? null : "h", "worker".equals(role) ? "p" : null, role);
        return u;
    }

    private UUID store(Tenant t, String status) {
        UUID s = UUID.randomUUID();
        owner.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, ?::store_status)",
            s, t.id(), "progress-" + s + ".myshopify.com", status);
        return s;
    }

    private UUID receipt(Tenant t, String status, String mode) {
        UUID r = UUID.randomUUID();
        owner.update("INSERT INTO receipts (id, tenant_id, location_id, status, mode, finalized_at) VALUES (?, ?, ?, ?, ?, " +
                     "CASE WHEN ? = 'open' THEN NULL ELSE now() END)", r, t.id(), t.location(), status, mode, status);
        return r;
    }

    private String piece(Tenant t) {
        UUID store = store(t, "disconnected"), product = UUID.randomUUID(), variant = UUID.randomUUID();
        owner.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'T', 'active')",
            product, t.id(), store, "P-" + product);
        owner.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, 'V')",
            variant, t.id(), product, "V-" + variant);
        String id = "OP" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
        owner.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                     "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?)", id, t.id(), variant, "BC-" + id, id, t.location());
        return id;
    }

    private void event(Tenant t, String piece, String type) {
        owner.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id) VALUES (?, ?, ?, ?)",
            t.id(), piece, type, t.owner());
    }

    private static void setTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.current_tenant', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int exec(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }
}
