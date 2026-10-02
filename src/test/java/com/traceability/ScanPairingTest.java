package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.ApiException;
import com.traceability.identity.model.PinRequest;
import com.traceability.inventory.ScanPairException;
import com.traceability.inventory.ScanPairingService;
import com.traceability.inventory.ScanRelayHub;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S6 — phone as scanner: pairing (create / claim once / expiry / every revocation path), the
 * relay (seq-idempotent scans, delivery over the real SSE stream, stale events expired and never
 * delivered, outcome round-trip), the per-pairing rate limit, the security config (only
 * /api/v1/scan-pair/** is public), and the whole service on an app_user connection (V127's
 * column grants suffice; tenant isolation with a same-tenant positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanPairingTest {

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
        r.add("shopify.app-url",            () -> "https://app.tracedtech.com");
    }

    static final String IPHONE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @Autowired PasswordEncoder  encoder;
    @MockBean  JobScheduler     jobScheduler;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    // ── fixtures ─────────────────────────────────────────────────────────────

    record Station(PackFixtures f, UUID worker, UUID session, String token) {}

    private Station station(String name) {
        PackFixtures f = new PackFixtures(jdbc, name);
        UUID worker = f.user("Ahmed", "worker");
        UUID session = openSession(f.tenant, worker);
        return new Station(f, worker, session, jwt.issueAccessToken(worker, f.tenant, "worker"));
    }

    private UUID openSession(UUID tenant, UUID user) {
        return jdbc.queryForObject(
            "INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, 'waybill_scan') RETURNING id",
            UUID.class, tenant, user);
    }

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders phone(String secret) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set(HttpHeaders.USER_AGENT, IPHONE_UA);
        if (secret != null) h.set("X-Device-Secret", secret);
        return h;
    }

    /** java.net.http, not TestRestTemplate: HttpURLConnection can't read a 401 answer to a POST. */
    private ResponseEntity<String> call(HttpMethod m, String path, Object body, HttpHeaders h) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
            h.forEach((k, vs) -> { if (!k.equalsIgnoreCase("Content-Length")) vs.forEach(v -> b.header(k, v)); });
            if (body != null && !h.containsKey(HttpHeaders.CONTENT_TYPE)) b.header("Content-Type", "application/json");
            b.method(m.name(), body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(r.statusCode()).body(r.body() == null || r.body().isEmpty() ? null : r.body());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode body(ResponseEntity<String> r) throws Exception { return json.readTree(r.getBody()); }

    /** Tablet: create a pairing; returns the pair code from the QR URL. */
    private String pair(Station s) throws Exception {
        ResponseEntity<String> r = call(HttpMethod.POST, "/api/v1/pack-sessions/" + s.session + "/pairings", null, bearer(s.token));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        String url = body(r).get("pairUrl").asText();
        assertThat(url).startsWith("https://app.tracedtech.com/scan/");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** Phone: claim the pair code; returns the device secret. */
    private String claim(String pairCode) throws Exception {
        ResponseEntity<String> r = call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", pairCode), phone(null));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        return body(r).get("deviceSecret").asText();
    }

    private ResponseEntity<String> phoneScan(String secret, long seq, String code) {
        return call(HttpMethod.POST, "/api/v1/scan-pair/scan", Map.of("seq", seq, "code", code), phone(secret));
    }

    private void assertEnded(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(r).get("code").asText()).isEqualTo("PAIRING_ENDED");
    }

    private String revokedReason(UUID session) {
        return jdbc.queryForObject(
            "SELECT revoked_reason FROM scan_pairings WHERE pack_session_id = ? ORDER BY created_at DESC LIMIT 1",
            String.class, session);
    }

    /** A relay stream (SSE) the test reads event by event. */
    final class Stream implements AutoCloseable {
        final LinkedBlockingQueue<String[]> events = new LinkedBlockingQueue<>();
        final CompletableFuture<HttpResponse<Void>> response;
        final java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);

        Stream(Station s) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/v1/pack-sessions/" + s.session + "/relay-stream"))
                .header("Authorization", "Bearer " + s.token).header("Accept", "text/event-stream").GET().build();
            String[] name = { "message" };
            StringBuilder data = new StringBuilder();
            response = http.sendAsync(req, HttpResponse.BodyHandlers.fromLineSubscriber(new java.util.concurrent.Flow.Subscriber<String>() {
                public void onSubscribe(java.util.concurrent.Flow.Subscription sub) { sub.request(Long.MAX_VALUE); }
                public void onNext(String line) {
                    if (line.startsWith("event:")) name[0] = line.substring(6).trim();
                    else if (line.startsWith("data:")) data.append(line.substring(5));
                    else if (line.isEmpty() && data.length() > 0) {
                        events.add(new String[] { name[0], data.toString() });
                        name[0] = "message";
                        data.setLength(0);
                    }
                }
                public void onError(Throwable t) { ended.countDown(); }
                public void onComplete() { ended.countDown(); }
            }));
        }

        String[] next(String name, long ms) throws InterruptedException {
            long until = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < until) {
                String[] e = events.poll(Math.max(1, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (e != null && e[0].equals(name)) return e;
            }
            return null;
        }

        @Override public void close() { response.cancel(true); }
    }

    // ── pairing lifecycle ────────────────────────────────────────────────────

    @Test
    void pair_claimOnce_status_andThePairCodeNeverWorksAgain() throws Exception {
        Station s = station("Pair");
        String code = pair(s);
        assertThat(code.length()).as("≥128-bit").isGreaterThanOrEqualTo(22);
        assertThat(code).as("URL-safe").matches("[A-Za-z0-9_-]+");
        assertThat(jdbc.queryForObject("SELECT pair_code_hash FROM scan_pairings WHERE pack_session_id = ?", String.class, s.session))
            .as("only the hash is stored").isNotEqualTo(code).hasSize(64);

        JsonNode waiting = body(call(HttpMethod.GET, "/api/v1/pack-sessions/" + s.session + "/pairings/current", null, bearer(s.token)));
        assertThat(waiting.get("status").asText()).isEqualTo("waiting");

        ResponseEntity<String> claimed = call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", code), phone(null));
        assertThat(claimed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode c = body(claimed);
        String secret = c.get("deviceSecret").asText();
        assertThat(secret.length()).isGreaterThanOrEqualTo(43);
        assertThat(c.at("/context/workerName").asText()).isEqualTo("Ahmed");
        assertThat(c.at("/context/state").asText()).isEqualTo("connected");

        assertEnded(call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", code), phone(null)));   // claim twice

        JsonNode connected = body(call(HttpMethod.GET, "/api/v1/pack-sessions/" + s.session + "/pairings/current", null, bearer(s.token)));
        assertThat(connected.get("status").asText()).isEqualTo("connected");
        assertThat(connected.get("deviceLabel").asText()).isEqualTo("iPhone · Safari");

        assertThat(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(secret)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(secret + "x")));
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(null)));
    }

    @Test
    void expiredPairCode_and12hExpiry_endThePairing() throws Exception {
        Station s = station("Expiry");
        String code = pair(s);
        jdbc.update("UPDATE scan_pairings SET pair_code_expires_at = now() - interval '1 second' WHERE pack_session_id = ?", s.session);
        assertEnded(call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", code), phone(null)));
        assertThat(body(call(HttpMethod.GET, "/api/v1/pack-sessions/" + s.session + "/pairings/current", null, bearer(s.token)))
            .get("status").asText()).isEqualTo("expired");

        String secret = claim(pair(s));
        jdbc.update("UPDATE scan_pairings SET expires_at = now() - interval '1 second' WHERE pack_session_id = ? AND revoked_at IS NULL", s.session);
        assertEnded(phoneScan(secret, 1, "P000001"));
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(secret)));
    }

    @Test
    void revocation_unpair_newPairing_sessionEnd() throws Exception {
        Station s = station("Revoke");
        String first = claim(pair(s));
        String second = claim(pair(s));                         // a new pairing replaces the first
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(first)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scan_pairings WHERE pack_session_id = ? AND revoked_reason = 'replaced'",
            Integer.class, s.session)).isEqualTo(1);

        assertThat(call(HttpMethod.DELETE, "/api/v1/pack-sessions/" + s.session + "/pairings/current", null, bearer(s.token))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(second)));
        assertThat(revokedReason(s.session)).isEqualTo("unpaired");
        assertThat(body(call(HttpMethod.GET, "/api/v1/pack-sessions/" + s.session + "/pairings/current", null, bearer(s.token)))
            .get("status").asText()).isEqualTo("none");

        String third = claim(pair(s));
        assertThat(call(HttpMethod.POST, "/api/v1/pack-sessions/" + s.session + "/end", null, bearer(s.token))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertEnded(phoneScan(third, 1, "P000001"));
        assertThat(revokedReason(s.session)).isEqualTo("session_ended");
    }

    @Test
    void workerSwitch_byPin_revokesTheOutgoingWorkersPairing_andTheirOwnSignOutDoesToo() throws Exception {
        Station s = station("Switch");
        String secret = claim(pair(s));
        UUID next = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                    "VALUES (?, ?, 'Mona', ?, 'x', 'worker', ?, true)",
            next, s.f.tenant, "mona-" + next + "@test.com", encoder.encode("5821"));

        ResponseEntity<String> sw = call(HttpMethod.POST, "/api/v1/auth/pin", new PinRequest(next.toString(), "5821"), bearer(s.token));
        assertThat(sw.getStatusCode()).as(sw.getBody()).isEqualTo(HttpStatus.OK);
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(secret)));
        assertThat(revokedReason(s.session)).isEqualTo("worker_switched");

        // A wrong PIN doesn't unpair anyone.
        String again = claim(pair(s));
        ResponseEntity<String> bad = call(HttpMethod.POST, "/api/v1/auth/pin", new PinRequest(next.toString(), "0000"), bearer(s.token));
        assertThat(bad.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(again)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The tablet's sign-out (StationProvider.signOutWorker) calls DELETE /pairings/mine.
        assertThat(call(HttpMethod.DELETE, "/api/v1/pack-sessions/pairings/mine", null, bearer(s.token))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(again)));
    }

    @Test
    void onlyTheSessionOwner_canPairOrStream() throws Exception {
        Station s = station("Owner");
        UUID other = s.f.user("Mona", "manager");
        String otherToken = jwt.issueAccessToken(other, s.f.tenant, "manager");
        assertThat(call(HttpMethod.POST, "/api/v1/pack-sessions/" + s.session + "/pairings", null, bearer(otherToken))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/pack-sessions/" + s.session + "/relay-stream", null, bearer(otherToken))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Station b = station("Owner other tenant");
        assertThat(call(HttpMethod.POST, "/api/v1/pack-sessions/" + s.session + "/pairings", null, bearer(b.token))
            .getStatusCode()).as("another tenant's session: RLS → 404").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── relay ────────────────────────────────────────────────────────────────

    @Test
    void scan_isIdempotentOnSeq() throws Exception {
        Station s = station("Seq");
        String secret = claim(pair(s));
        String a = body(phoneScan(secret, 7, "P000001")).get("eventId").asText();
        String b = body(phoneScan(secret, 7, "P000001")).get("eventId").asText();
        assertThat(b).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scan_relay_events e JOIN scan_pairings p ON p.id = e.pairing_id " +
            "WHERE p.pack_session_id = ?", Integer.class, s.session)).isEqualTo(1);
        assertThat(phoneScan(secret, 8, "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void delivery_overTheStream_outcomeRoundTrip_andStaleEventsAreExpiredNeverDelivered() throws Exception {
        Station s = station("Relay");
        String secret = claim(pair(s));

        // A scan made while no tablet listened, now 6 s old: expired on connect, never delivered.
        String stale = body(phoneScan(secret, 1, "P-STALE")).get("eventId").asText();
        jdbc.update("UPDATE scan_relay_events SET created_at = now() - interval '6 seconds' WHERE id = ?::uuid", stale);
        // One 2 s old: still delivered on connect.
        String fresh = body(phoneScan(secret, 2, "P-FRESH")).get("eventId").asText();

        try (Stream stream = new Stream(s)) {
            String[] status = stream.next("pairing", 5_000);
            assertThat(status).isNotNull();
            assertThat(json.readTree(status[1]).get("status").asText()).isEqualTo("connected");

            String[] first = stream.next("scan", 5_000);
            assertThat(first).isNotNull();
            assertThat(json.readTree(first[1]).get("code").asText()).isEqualTo("P-FRESH");
            assertThat(json.readTree(first[1]).get("id").asText()).isEqualTo(fresh);

            String live = body(phoneScan(secret, 3, "D-07-74821903")).get("eventId").asText();
            String[] second = stream.next("scan", 5_000);
            assertThat(second).isNotNull();
            assertThat(json.readTree(second[1]).get("id").asText()).isEqualTo(live);
            assertThat(stream.next("scan", 700)).as("the stale one never arrives").isNull();

            assertThat(body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + live, null, phone(secret))).get("status").asText())
                .isEqualTo("delivered");
            assertThat(call(HttpMethod.POST, "/api/v1/pack-sessions/" + s.session + "/relay-events/" + live + "/outcome",
                Map.of("result", "accepted", "message", "Order #1047 opened\n"), bearer(s.token)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
            JsonNode outcome = body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + live, null, phone(secret)));
            assertThat(outcome.get("status").asText()).isEqualTo("accepted");
            assertThat(outcome.get("message").asText()).isEqualTo("Order #1047 opened");

            // Idempotent: a second verdict doesn't change the first.
            call(HttpMethod.POST, "/api/v1/pack-sessions/" + s.session + "/relay-events/" + live + "/outcome",
                Map.of("result", "rejected", "message", "x"), bearer(s.token));
            assertThat(body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + live, null, phone(secret))).get("status").asText())
                .isEqualTo("accepted");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM scan_relay_events WHERE id = ?::uuid", String.class, stale))
            .isEqualTo("expired");
        assertThat(body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + stale, null, phone(secret))).get("status").asText())
            .isEqualTo("expired");
        assertThat(call(HttpMethod.POST, "/api/v1/pack-sessions/" + s.session + "/relay-events/" + UUID.randomUUID() + "/outcome",
            Map.of("result", "accepted"), bearer(s.token)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void oneStreamPerSession_aSecondOneClosesTheFirst_andOnlyItGetsScans() throws Exception {
        Station s = station("Two tabs");
        String secret = claim(pair(s));
        try (Stream first = new Stream(s)) {
            assertThat(first.next("pairing", 5_000)).isNotNull();
            try (Stream second = new Stream(s)) {
                assertThat(second.next("pairing", 5_000)).isNotNull();
                assertThat(first.ended.await(5, TimeUnit.SECONDS)).as("the first stream is closed").isTrue();
                String id = body(phoneScan(secret, 1, "P000001")).get("eventId").asText();
                String[] got = second.next("scan", 5_000);
                assertThat(got).isNotNull();
                assertThat(json.readTree(got[1]).get("id").asText()).isEqualTo(id);
                assertThat(first.next("scan", 500)).isNull();
            }
        }
    }

    @Test
    void phoneScanWithNoTabletListening_expiresForThePhone_afterTheWindow() throws Exception {
        Station s = station("Unheard");
        String secret = claim(pair(s));
        String id = body(phoneScan(secret, 1, "P000001")).get("eventId").asText();
        assertThat(body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + id, null, phone(secret))).get("status").asText())
            .isEqualTo("pending");
        jdbc.update("UPDATE scan_relay_events SET created_at = now() - interval '6 seconds' WHERE id = ?::uuid", id);
        assertThat(body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + id, null, phone(secret))).get("status").asText())
            .isEqualTo("expired");
    }

    @Test
    void rateLimit_perPairing_tenScansPerSecond() throws Exception {
        Station s = station("Rate");
        String secret = claim(pair(s));
        List<HttpStatusCode> codes = new ArrayList<>();
        for (int i = 0; i < 14; i++) codes.add(phoneScan(secret, 100 + i, "P" + i).getStatusCode());
        assertThat(codes).contains(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(codes.stream().filter(HttpStatus.OK::equals).count()).isLessThanOrEqualTo(10);
    }

    // ── security config ──────────────────────────────────────────────────────

    @Test
    void securityConfig_onlyScanPairIsPublic() throws Exception {
        HttpHeaders none = new HttpHeaders();
        none.setContentType(MediaType.APPLICATION_JSON);
        // Public: reaches the controller (the app's own JSON 401, not the empty security 401).
        assertEnded(call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", "nope"), none));
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, none));
        // Everything else still needs a login — the tablet side included.
        UUID any = UUID.randomUUID();
        for (String[] e : List.of(
                new String[] { "POST", "/api/v1/pack-sessions/" + any + "/pairings" },
                new String[] { "GET", "/api/v1/pack-sessions/" + any + "/pairings/current" },
                new String[] { "GET", "/api/v1/pack-sessions/" + any + "/relay-stream" },
                new String[] { "POST", "/api/v1/pack-sessions/" + any + "/relay-events/" + any + "/outcome" },
                new String[] { "GET", "/api/v1/pack-sessions/summary" },
                new String[] { "GET", "/api/v1/fulfill/queue" })) {
            ResponseEntity<String> r = call(HttpMethod.valueOf(e[0]), e[1], e[0].equals("POST") ? Map.of() : null, none);
            assertThat(r.getStatusCode()).as(e[0] + " " + e[1]).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody()).as("the security entry point, not a controller").isNull();
        }
    }

    // ── app_user: grants suffice, tenants isolated ───────────────────────────

    /** What each relay stream would be sent (app_user tests drive the stream path without HTTP). */
    static final class RecordingHub extends ScanRelayHub {
        final List<Object[]> sent = Collections.synchronizedList(new ArrayList<>());
        @Override public boolean send(Subscriber sub, String name, Object data) {
            sent.add(new Object[] { sub.sessionId, name, data });
            return true;
        }
        List<String> codesSentTo(UUID session) {
            return sent.stream().filter(e -> e[0].equals(session) && "scan".equals(e[1]))
                .map(e -> ((ScanPairingService.RelayEvent) e[2]).code()).toList();
        }
    }

    /**
     * Relay-stream isolation (RlsCoverageTest EXEMPT reason for /pack-sessions/{id}/relay-stream
     * names this test). As app_user: another tenant's session id is refused (404 — RLS hides it,
     * with the caller's own id or the owner's), and no event of another tenant's pairing is ever
     * delivered on a stream — not even on a stream forged for the other tenant's session id, where
     * RLS alone has to stop it. Same-tenant positive control: each tenant's own event is delivered
     * on its own stream. Over HTTP, another tenant's token on the stream is a 404.
     */
    @Test
    void relayStream_isolation_asAppUser_otherTenantsSessionRefused_otherTenantsEventsNeverDelivered() throws Exception {
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        RecordingHub hub = new RecordingHub();
        ScanPairingService svc = new ScanPairingService(new JdbcTemplate(ds), new DataSourceTransactionManager(ds),
            hub, "https://app.tracedtech.com");
        Station a = station("Stream iso A"), b = station("Stream iso B");
        UUID eventA = phoneScanAsAppUser(svc, a, "A-PIECE-1");
        UUID eventB = phoneScanAsAppUser(svc, b, "B-PIECE-1");
        try {
            // Another tenant's session id: refused, with the caller's own user id or the owner's.
            for (UUID asUser : List.of(b.worker, a.worker)) {
                assertThatThrownBy(() -> TenantContext.runAs(b.f.tenant, () -> svc.requireStreamable(a.session, asUser)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.errorCode()).isEqualTo("SESSION_NOT_FOUND");
                    });
            }
            assertThat(call(HttpMethod.GET, "/api/v1/pack-sessions/" + a.session + "/relay-stream", null, bearer(b.token))
                .getStatusCode()).as("over HTTP").isEqualTo(HttpStatus.NOT_FOUND);

            // A stream forged for A's session id under tenant B: RLS finds none of A's events.
            hub.subscribe(a.session, b.f.tenant);
            TenantContext.runAs(b.f.tenant, () -> svc.deliver(a.session));
            assertThat(hub.sent).as("nothing crosses to tenant B").isEmpty();
            assertThat(jdbc.queryForObject("SELECT status FROM scan_relay_events WHERE id = ?", String.class, eventA))
                .as("A's event untouched by tenant B").isEqualTo("pending");

            // B's own stream: B's event only — never A's.
            hub.subscribe(b.session, b.f.tenant);
            TenantContext.runAs(b.f.tenant, () -> svc.deliver(b.session));
            assertThat(hub.codesSentTo(b.session)).as("same-tenant positive control (B)").containsExactly("B-PIECE-1");

            // A's real stream (tenant A): A's event only — the positive control for A.
            hub.subscribe(a.session, a.f.tenant);
            TenantContext.runAs(a.f.tenant, () -> svc.deliver(a.session));
            assertThat(hub.codesSentTo(a.session)).as("same-tenant positive control (A)").containsExactly("A-PIECE-1");

            assertThat(hub.sent).extracting(e -> ((ScanPairingService.RelayEvent) e[2]).id())
                .containsExactlyInAnyOrder(eventA, eventB);
        } finally {
            hub.shutdown();
        }
    }

    /** As app_user: pair the station's session, claim it, and send one phone scan; the event id. */
    private UUID phoneScanAsAppUser(ScanPairingService svc, Station s, String code) {
        ScanPairingService.PairingCreated created = TenantContext.runAs(s.f.tenant, () -> svc.create(s.session, s.worker));
        String pairCode = created.pairUrl().substring(created.pairUrl().lastIndexOf('/') + 1);
        ScanPairingService.Resolved byCode = svc.resolve("pair_code", pairCode);
        ScanPairingService.Claimed claimed = TenantContext.runAs(s.f.tenant, () -> svc.claim(byCode, "Android · Chrome"));
        ScanPairingService.Resolved bySecret = svc.resolve("device_secret", claimed.deviceSecret());
        return TenantContext.runAs(s.f.tenant, () -> svc.scan(bySecret, 1L, code));
    }


    @Test
    void wholeFlowAsAppUser_grantsSuffice_tenantIsolated_withSameTenantPositiveControl() {
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        ScanRelayHub hub = new ScanRelayHub();
        ScanPairingService svc = new ScanPairingService(new JdbcTemplate(ds), new DataSourceTransactionManager(ds),
            hub, "https://app.tracedtech.com");
        Station a = station("AppUser A"), b = station("AppUser B");

        ScanPairingService.PairingCreated created = TenantContext.runAs(a.f.tenant, () -> svc.create(a.session, a.worker));
        String code = created.pairUrl().substring(created.pairUrl().lastIndexOf('/') + 1);

        ScanPairingService.Resolved byCode = svc.resolve("pair_code", code);          // no tenant set
        assertThat(byCode).isNotNull();
        assertThat(byCode.tenantId()).isEqualTo(a.f.tenant);
        ScanPairingService.Claimed claimed = TenantContext.runAs(a.f.tenant, () -> svc.claim(byCode, "iPhone · Safari"));
        ScanPairingService.Resolved bySecret = svc.resolve("device_secret", claimed.deviceSecret());
        assertThat(bySecret).isNotNull();

        UUID event = TenantContext.runAs(a.f.tenant, () -> svc.scan(bySecret, 1L, "P000001"));
        assertThat(TenantContext.runAs(a.f.tenant, () -> svc.scan(bySecret, 1L, "P000001"))).isEqualTo(event);
        assertThat(TenantContext.runAs(a.f.tenant, () -> svc.eventStatus(bySecret, event)).status()).isEqualTo("pending");
        TenantContext.runAs(a.f.tenant, () -> svc.outcome(a.session, a.worker, event, "accepted", "Cargo pants 2/2"));
        assertThat(TenantContext.runAs(a.f.tenant, () -> svc.eventStatus(bySecret, event)).message()).isEqualTo("Cargo pants 2/2");
        assertThat(TenantContext.runAs(a.f.tenant, () -> svc.phoneStatus(bySecret)).workerName()).isEqualTo("Ahmed");
        assertThat(TenantContext.runAs(a.f.tenant, () -> svc.current(a.session, a.worker)).status())
            .as("same-tenant positive control").isEqualTo("connected");

        // Tenant B can't see or touch A's pairing / events.
        assertThatThrownBy(() -> TenantContext.runAs(b.f.tenant, () -> svc.current(a.session, a.worker)))
            .hasMessageContaining("not found");
        assertThatThrownBy(() -> TenantContext.runAs(b.f.tenant, () -> svc.eventStatus(bySecret, event)))
            .isInstanceOf(ScanPairException.class);
        assertThatThrownBy(() -> TenantContext.runAs(b.f.tenant, () -> svc.outcome(a.session, a.worker, event, "rejected", "x")))
            .hasMessageContaining("not found");

        TenantContext.runAs(a.f.tenant, () -> svc.unpair(a.session, a.worker));
        assertThat(svc.resolve("device_secret", claimed.deviceSecret())).isNull();
        hub.shutdown();
    }
}
