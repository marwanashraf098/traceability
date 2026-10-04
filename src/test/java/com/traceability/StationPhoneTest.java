package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.ApiException;
import com.traceability.identity.JwtService;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Q1 — phone as scanner, per station: pairing a phone with a TABLET and a worker (create / claim
 * once / status with the open scanning screen), every revocation trigger (unpair, replaced on the
 * same tablet, replaced by the same worker, PIN worker switch, the tablet's sign-out, station
 * lock, full logout, worker deactivated, 12 h) — and that ending a pack session does NOT revoke;
 * the stream (one per tablet, a second replaces the first, only the pairing's worker may open
 * it), outcome round-trip, the target label; S6's per-session endpoints are gone; and the stream
 * / whole flow on an app_user connection (tenant isolation with a same-tenant positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationPhoneTest {

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
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @Autowired PasswordEncoder  encoder;
    @MockBean  JobScheduler     jobScheduler;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    // ── fixtures ─────────────────────────────────────────────────────────────

    record Station(PackFixtures f, UUID worker, String device, String token) {
        Station on(String otherDevice) { return new Station(f, worker, otherDevice, token); }
    }

    private Station station(String name) {
        PackFixtures f = new PackFixtures(jdbc, name);
        UUID worker = f.user("Ahmed", "worker");
        return new Station(f, worker, device(), jwt.issueAccessToken(worker, f.tenant, "worker"));
    }

    /** Another worker at the same tablet / tenant. */
    private Station colleague(Station s, String name) {
        UUID u = s.f.user(name, "worker");
        return new Station(s.f, u, s.device, jwt.issueAccessToken(u, s.f.tenant, "worker"));
    }

    private static String device() { return "tab" + UUID.randomUUID().toString().replace("-", ""); }

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

    /** Tablet: create a pairing for this tablet; returns the pair code from the QR URL. */
    private String pair(Station s) throws Exception {
        ResponseEntity<String> r = call(HttpMethod.POST, "/api/v1/station/pairings", Map.of("deviceId", s.device), bearer(s.token));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        String url = body(r).get("pairUrl").asText();
        assertThat(url).startsWith("https://app.tracedtech.com/scan/");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private String claim(String pairCode) throws Exception {
        ResponseEntity<String> r = call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", pairCode), phone(null));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        return body(r).get("deviceSecret").asText();
    }

    private JsonNode current(Station s) throws Exception {
        ResponseEntity<String> r = call(HttpMethod.GET, "/api/v1/station/pairings/current?deviceId=" + s.device, null, bearer(s.token));
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        return body(r);
    }

    private ResponseEntity<String> phoneScan(String secret, long seq, String code) {
        return call(HttpMethod.POST, "/api/v1/scan-pair/scan", Map.of("seq", seq, "code", code), phone(secret));
    }

    private ResponseEntity<String> phoneStatus(String secret) {
        return call(HttpMethod.GET, "/api/v1/scan-pair/status", null, phone(secret));
    }

    private void assertEnded(ResponseEntity<String> r) throws Exception {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body(r).get("code").asText()).isEqualTo("PAIRING_ENDED");
    }

    private void assertAlive(String secret) {
        assertThat(phoneStatus(secret).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String lastReason(UUID worker) {
        return jdbc.queryForObject(
            "SELECT revoked_reason FROM scan_pairings WHERE station_user_id = ? ORDER BY created_at DESC LIMIT 1",
            String.class, worker);
    }

    /** A relay stream (SSE) the test reads event by event. */
    final class Stream implements AutoCloseable {
        final LinkedBlockingQueue<String[]> events = new LinkedBlockingQueue<>();
        final CompletableFuture<HttpResponse<Void>> response;
        final java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);

        Stream(Station s) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/v1/station/relay-stream?deviceId=" + s.device))
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
    void pair_claimOnce_status_targetInThePhoneHeader_noPackSessionNeeded() throws Exception {
        Station s = station("Pair");
        assertThat(current(s).get("status").asText()).isEqualTo("none");
        String code = pair(s);
        assertThat(code.length()).as("≥128-bit").isGreaterThanOrEqualTo(22);
        assertThat(code).as("URL-safe").matches("[A-Za-z0-9_-]+");
        assertThat(jdbc.queryForObject("SELECT pair_code_hash FROM scan_pairings WHERE station_device_id = ?", String.class, s.device))
            .as("only the hash is stored").isNotEqualTo(code).hasSize(64);
        assertThat(jdbc.queryForObject("SELECT pack_session_id FROM scan_pairings WHERE station_device_id = ?",
            UUID.class, s.device)).as("no pack session").isNull();
        assertThat(current(s).get("status").asText()).isEqualTo("waiting");

        ResponseEntity<String> claimed = call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", code), phone(null));
        JsonNode c = body(claimed);
        String secret = c.get("deviceSecret").asText();
        assertThat(secret.length()).isGreaterThanOrEqualTo(43);
        assertThat(c.at("/context/state").asText()).isEqualTo("connected");
        assertThat(c.at("/context/workerName").asText()).isEqualTo("Ahmed");
        assertAlive(secret);
        assertEnded(phoneStatus(secret + "x"));
        assertEnded(phoneStatus(null));
        assertThat(c.at("/context/target").isNull()).as("no scanning screen named yet").isTrue();
        assertEnded(call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", code), phone(null)));   // twice

        JsonNode connected = current(s);
        assertThat(connected.get("status").asText()).isEqualTo("connected");
        assertThat(connected.get("deviceLabel").asText()).isEqualTo("iPhone · Safari");

        // The tablet names the open scanning screen; the phone header shows it.
        assertThat(call(HttpMethod.PUT, "/api/v1/station/pairings/current/target?deviceId=" + s.device,
            Map.of("label", "Pick & Pack · #1047\n"), bearer(s.token)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode st = body(phoneStatus(secret));
        assertThat(st.get("target").asText()).isEqualTo("Pick & Pack · #1047");
        assertThat(st.get("workerName").asText()).isEqualTo("Ahmed");
        assertThat(st.has("order")).as("S6's open-order field is gone").isFalse();

        // Cleared when no screen is open.
        call(HttpMethod.PUT, "/api/v1/station/pairings/current/target?deviceId=" + s.device, Map.of(), bearer(s.token));
        assertThat(body(phoneStatus(secret)).get("target").isNull()).isTrue();

        // Another worker's target write is a no-op on this pairing.
        Station mona = colleague(s, "Mona");
        call(HttpMethod.PUT, "/api/v1/station/pairings/current/target?deviceId=" + s.device,
            Map.of("label", "Hijacked"), bearer(mona.token));
        assertThat(body(phoneStatus(secret)).get("target").isNull()).isTrue();
    }

    /**
     * The claim is one conditional UPDATE (… WHERE claimed_at IS NULL …): two phones claiming the
     * same code at the same instant — exactly one gets a device secret, the other 401. 10 rounds.
     */
    @Test
    void concurrentClaimsOfOneCode_exactlyOneWins() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                Station s = station("Race " + round);
                String code = pair(s);
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<ResponseEntity<String>> claimIt = () -> {
                    go.await();
                    return call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", code), phone(null));
                };
                java.util.concurrent.Future<ResponseEntity<String>> a = pool.submit(claimIt);
                java.util.concurrent.Future<ResponseEntity<String>> b = pool.submit(claimIt);
                go.countDown();
                List<ResponseEntity<String>> both = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));

                List<ResponseEntity<String>> won = both.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).toList();
                List<ResponseEntity<String>> lost = both.stream().filter(r -> r.getStatusCode() == HttpStatus.UNAUTHORIZED).toList();
                assertThat(won).as("round " + round + ": exactly one claim wins").hasSize(1);
                assertThat(lost).as("round " + round + ": the other gets 401").hasSize(1);
                assertThat(body(lost.get(0)).get("code").asText()).isEqualTo("PAIRING_ENDED");

                String secret = body(won.get(0)).get("deviceSecret").asText();
                assertThat(jdbc.queryForObject("SELECT device_secret_hash FROM scan_pairings WHERE station_device_id = ? " +
                    "AND revoked_at IS NULL", String.class, s.device)).as("the stored hash is the winner's").isEqualTo(sha256Hex(secret));
                assertAlive(secret);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String sha256Hex(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void badOrMissingDeviceId_is400() throws Exception {
        Station s = station("Bad device");
        for (String path : List.of("/api/v1/station/pairings/current", "/api/v1/station/pairings/current?deviceId=short",
                                   "/api/v1/station/relay-stream?deviceId=has%20space%20but%20long%20enough")) {
            ResponseEntity<String> r = call(HttpMethod.GET, path, null, bearer(s.token));
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body(r).get("code").asText()).isEqualTo("BAD_DEVICE");
        }
        assertThat(call(HttpMethod.POST, "/api/v1/station/pairings", Map.of("deviceId", "x"), bearer(s.token))
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anotherWorkerAtTheSameTablet_seesNone_cantStream_cantUnpairIt_butANewPairingReplacesIt() throws Exception {
        Station s = station("Shared tablet");
        String secret = claim(pair(s));
        Station mona = colleague(s, "Mona");
        assertThat(current(mona).get("status").asText()).as("not hers").isEqualTo("none");
        ResponseEntity<String> stream = call(HttpMethod.GET, "/api/v1/station/relay-stream?deviceId=" + s.device, null, bearer(mona.token));
        assertThat(stream.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(stream).get("code").asText()).isEqualTo("PAIRING_NOT_YOURS");
        call(HttpMethod.DELETE, "/api/v1/station/pairings/current?deviceId=" + s.device, null, bearer(mona.token));
        assertAlive(secret);

        // Mona pairs her own phone at this tablet → Ahmed's is replaced (one per tablet).
        String hers = claim(pair(mona));
        assertEnded(phoneStatus(secret));
        assertThat(lastReason(s.worker)).isEqualTo("replaced");
        assertAlive(hers);
    }

    @Test
    void expiredPairCode_and12hExpiry_endThePairing() throws Exception {
        Station s = station("Expiry");
        String code = pair(s);
        jdbc.update("UPDATE scan_pairings SET pair_code_expires_at = now() - interval '1 second' WHERE station_device_id = ?", s.device);
        assertEnded(call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", code), phone(null)));
        assertThat(current(s).get("status").asText()).isEqualTo("expired");

        String secret = claim(pair(s));
        jdbc.update("UPDATE scan_pairings SET expires_at = now() - interval '1 second' WHERE station_device_id = ? AND revoked_at IS NULL", s.device);
        assertEnded(phoneScan(secret, 1, "P000001"));
        assertEnded(phoneStatus(secret));
    }

    // ── revocation: every trigger ────────────────────────────────────────────

    @Test
    void revoke_unpair() throws Exception {
        Station s = station("Unpair");
        String secret = claim(pair(s));
        assertThat(call(HttpMethod.DELETE, "/api/v1/station/pairings/current?deviceId=" + s.device, null, bearer(s.token))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertEnded(phoneStatus(secret));
        assertThat(lastReason(s.worker)).isEqualTo("unpaired");
        assertThat(current(s).get("status").asText()).isEqualTo("none");
    }

    @Test
    void revoke_replaced_newPairingOnTheSameTablet_andByTheSameWorkerOnAnotherTablet() throws Exception {
        Station s = station("Replace");
        String first = claim(pair(s));
        String second = claim(pair(s));                                  // same tablet, same worker
        assertEnded(phoneStatus(first));
        assertAlive(second);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scan_pairings WHERE station_user_id = ? AND revoked_reason = 'replaced'",
            Integer.class, s.worker)).isEqualTo(1);

        Station elsewhere = s.on(device());                              // same worker, another tablet
        String third = claim(pair(elsewhere));
        assertEnded(phoneStatus(second));
        assertAlive(third);
        assertThat(current(s).get("status").asText()).as("the first tablet has none now").isEqualTo("none");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scan_pairings WHERE station_user_id = ? AND revoked_at IS NULL",
            Integer.class, s.worker)).as("one live per worker").isEqualTo(1);
    }

    @Test
    void revoke_pinWorkerSwitch_andAWrongPinDoesNot() throws Exception {
        Station s = station("Switch");
        String secret = claim(pair(s));
        UUID next = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                    "VALUES (?, ?, 'Mona', ?, 'x', 'worker', ?, true)",
            next, s.f.tenant, "mona-" + next + "@test.com", encoder.encode("5821"));

        ResponseEntity<String> bad = call(HttpMethod.POST, "/api/v1/auth/pin", new PinRequest(next.toString(), "0000"), bearer(s.token));
        assertThat(bad.getStatusCode().is2xxSuccessful()).isFalse();
        assertAlive(secret);

        ResponseEntity<String> sw = call(HttpMethod.POST, "/api/v1/auth/pin", new PinRequest(next.toString(), "5821"), bearer(s.token));
        assertThat(sw.getStatusCode()).as(sw.getBody()).isEqualTo(HttpStatus.OK);
        assertEnded(phoneStatus(secret));
        assertThat(lastReason(s.worker)).isEqualTo("worker_switched");
    }

    @Test
    void revoke_tabletSignOut_andStationLock_viaPairingsMine() throws Exception {
        Station s = station("Mine");
        String secret = claim(pair(s));
        // StationProvider.signOutWorker → DELETE /pack-sessions/pairings/mine (default reason).
        assertThat(call(HttpMethod.DELETE, "/api/v1/pack-sessions/pairings/mine", null, bearer(s.token))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertEnded(phoneStatus(secret));
        assertThat(lastReason(s.worker)).isEqualTo("worker_switched");

        // StationGate (the station back at the PIN gate) → …?reason=station_locked.
        String again = claim(pair(s));
        assertThat(call(HttpMethod.DELETE, "/api/v1/pack-sessions/pairings/mine?reason=station_locked", null, bearer(s.token))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertEnded(phoneStatus(again));
        assertThat(lastReason(s.worker)).isEqualTo("station_locked");

        String third = claim(pair(s));
        ResponseEntity<String> bad = call(HttpMethod.DELETE, "/api/v1/pack-sessions/pairings/mine?reason=signed_out", null, bearer(s.token));
        assertThat(bad.getStatusCode()).as("only worker_switched / station_locked from the tablet").isEqualTo(HttpStatus.BAD_REQUEST);
        assertAlive(third);
    }

    @Test
    void revoke_fullLogout() throws Exception {
        Station s = station("Logout");
        String secret = claim(pair(s));
        assertThat(call(HttpMethod.POST, "/api/v1/auth/logout", null, bearer(s.token)).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertEnded(phoneStatus(secret));
        assertThat(lastReason(s.worker)).isEqualTo("signed_out");
    }

    @Test
    void revoke_workerDeactivated_hatchStopsResolving() throws Exception {
        Station s = station("Deactivated");
        String secret = claim(pair(s));
        jdbc.update("UPDATE users SET active = false WHERE id = ?", s.worker);
        assertEnded(phoneStatus(secret));
        assertEnded(phoneScan(secret, 1, "P1"));
    }

    @Test
    void packSessionEnd_doesNotRevoke() throws Exception {
        Station s = station("Session end");
        String secret = claim(pair(s));
        UUID session = jdbc.queryForObject(
            "INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, 'waybill_scan') RETURNING id",
            UUID.class, s.f.tenant, s.worker);
        assertThat(call(HttpMethod.POST, "/api/v1/pack-sessions/" + session + "/end", null, bearer(s.token))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertAlive(secret);
        assertThat(phoneScan(secret, 1, "P000001").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current(s).get("status").asText()).isEqualTo("connected");
    }

    // ── relay ────────────────────────────────────────────────────────────────

    @Test
    void scan_isIdempotentOnSeq() throws Exception {
        Station s = station("Seq");
        String secret = claim(pair(s));
        String a = body(phoneScan(secret, 7, "P000001")).get("eventId").asText();
        String b = body(phoneScan(secret, 7, "P000001")).get("eventId").asText();
        assertThat(b).as("a replayed seq returns the same event").isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scan_relay_events e JOIN scan_pairings p ON p.id = e.pairing_id " +
            "WHERE p.station_device_id = ?", Integer.class, s.device)).isEqualTo(1);
        assertThat(phoneScan(secret, 8, "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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

    @Test
    void delivery_overTheStream_outcomeRoundTrip_staleEventsExpired() throws Exception {
        Station s = station("Relay");
        String secret = claim(pair(s));
        String stale = body(phoneScan(secret, 1, "P-STALE")).get("eventId").asText();
        jdbc.update("UPDATE scan_relay_events SET created_at = now() - interval '6 seconds' WHERE id = ?::uuid", stale);
        String fresh = body(phoneScan(secret, 2, "P-FRESH")).get("eventId").asText();

        try (Stream stream = new Stream(s)) {
            String[] status = stream.next("pairing", 5_000);
            assertThat(status).isNotNull();
            assertThat(json.readTree(status[1]).get("status").asText()).isEqualTo("connected");
            String[] first = stream.next("scan", 5_000);
            assertThat(json.readTree(first[1]).get("id").asText()).isEqualTo(fresh);

            String live = body(phoneScan(secret, 3, "D-07-74821903")).get("eventId").asText();
            String[] second = stream.next("scan", 5_000);
            assertThat(json.readTree(second[1]).get("id").asText()).isEqualTo(live);
            assertThat(stream.next("scan", 700)).as("the stale one never arrives").isNull();

            assertThat(body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + live, null, phone(secret))).get("status").asText())
                .isEqualTo("delivered");
            assertThat(call(HttpMethod.POST, "/api/v1/station/relay-events/" + live + "/outcome",
                Map.of("result", "rejected", "message", "No scanning screen open on the tablet"), bearer(s.token))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            JsonNode outcome = body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + live, null, phone(secret)));
            assertThat(outcome.get("status").asText()).isEqualTo("rejected");
            assertThat(outcome.get("message").asText()).isEqualTo("No scanning screen open on the tablet");

            // Exactly one outcome: a second verdict doesn't change the first.
            call(HttpMethod.POST, "/api/v1/station/relay-events/" + live + "/outcome",
                Map.of("result", "accepted", "message", "x"), bearer(s.token));
            assertThat(body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + live, null, phone(secret))).get("status").asText())
                .isEqualTo("rejected");

            // Another worker can't answer this pairing's scans.
            Station mona = colleague(s, "Mona");
            assertThat(call(HttpMethod.POST, "/api/v1/station/relay-events/" + fresh + "/outcome",
                Map.of("result", "accepted"), bearer(mona.token)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM scan_relay_events WHERE id = ?::uuid", String.class, stale))
            .isEqualTo("expired");
        assertThat(body(call(HttpMethod.GET, "/api/v1/scan-pair/scan/" + stale, null, phone(secret))).get("status").asText())
            .isEqualTo("expired");
        assertThat(call(HttpMethod.POST, "/api/v1/station/relay-events/" + UUID.randomUUID() + "/outcome",
            Map.of("result", "accepted"), bearer(s.token)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void oneStreamPerTablet_aSecondTabReplacesTheFirst_andOnlyItGetsScans() throws Exception {
        Station s = station("Two tabs");
        String secret = claim(pair(s));
        try (Stream first = new Stream(s)) {
            assertThat(first.next("pairing", 5_000)).isNotNull();
            try (Stream second = new Stream(s)) {
                assertThat(second.next("pairing", 5_000)).isNotNull();
                assertThat(first.ended.await(5, TimeUnit.SECONDS)).as("the first stream is closed").isTrue();
                String id = body(phoneScan(secret, 1, "P000001")).get("eventId").asText();
                String[] got = second.next("scan", 5_000);
                assertThat(json.readTree(got[1]).get("id").asText()).isEqualTo(id);
                assertThat(first.next("scan", 500)).isNull();
            }
        }
    }

    @Test
    void revokingThePairing_tellsAndClosesItsStream() throws Exception {
        Station s = station("Revoke stream");
        claim(pair(s));
        try (Stream stream = new Stream(s)) {
            assertThat(stream.next("pairing", 5_000)).isNotNull();
            call(HttpMethod.DELETE, "/api/v1/station/pairings/current?deviceId=" + s.device, null, bearer(s.token));
            String[] ended = stream.next("pairing", 5_000);
            assertThat(ended).isNotNull();
            assertThat(json.readTree(ended[1]).get("status").asText()).isEqualTo("none");
            assertThat(json.readTree(ended[1]).get("reason").asText()).isEqualTo("unpaired");
            assertThat(stream.ended.await(5, TimeUnit.SECONDS)).isTrue();
        }
        ResponseEntity<String> again = call(HttpMethod.GET, "/api/v1/station/relay-stream?deviceId=" + s.device, null, bearer(s.token));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(again).get("code").asText()).isEqualTo("NO_PAIRING");
    }

    // ── S6's per-session endpoints are retired; the station ones need a login ─

    @Test
    void s6PerSessionEndpoints_areGone_andStationEndpointsNeedALogin() throws Exception {
        Station s = station("Retired");
        UUID session = jdbc.queryForObject(
            "INSERT INTO pack_sessions (tenant_id, user_id, mode) VALUES (?, ?, 'waybill_scan') RETURNING id",
            UUID.class, s.f.tenant, s.worker);
        for (String[] e : List.of(
                new String[] { "POST", "/api/v1/pack-sessions/" + session + "/pairings" },
                new String[] { "GET", "/api/v1/pack-sessions/" + session + "/pairings/current" },
                new String[] { "DELETE", "/api/v1/pack-sessions/" + session + "/pairings/current" },
                new String[] { "GET", "/api/v1/pack-sessions/" + session + "/relay-stream" },
                new String[] { "POST", "/api/v1/pack-sessions/" + session + "/relay-events/" + UUID.randomUUID() + "/outcome" })) {
            ResponseEntity<String> r = call(HttpMethod.valueOf(e[0]), e[1], e[0].equals("POST") ? Map.of() : null, bearer(s.token));
            // No handler (an unmapped /api path is answered by ApiExceptionHandler's catch-all — not 2xx).
            assertThat(r.getStatusCode().is2xxSuccessful()).as(e[0] + " " + e[1]).isFalse();
        }
        Set<String> patterns = new HashSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> patterns.addAll(info.getPatternValues()));
        assertThat(patterns).as("S6's per-session phone endpoints are retired").doesNotContain(
            "/api/v1/pack-sessions/{id}/pairings", "/api/v1/pack-sessions/{id}/pairings/current",
            "/api/v1/pack-sessions/{id}/relay-stream", "/api/v1/pack-sessions/{id}/relay-events/{eventId}/outcome");
        assertThat(patterns).contains(
            "/api/v1/station/pairings", "/api/v1/station/pairings/current", "/api/v1/station/pairings/current/target",
            "/api/v1/station/relay-stream", "/api/v1/station/relay-events/{eventId}/outcome",
            "/api/v1/pack-sessions/pairings/mine");
        HttpHeaders none = new HttpHeaders();
        none.setContentType(MediaType.APPLICATION_JSON);
        // Public: the phone's endpoints reach the controller (the app's own JSON 401, not the empty security 401).
        assertEnded(call(HttpMethod.POST, "/api/v1/scan-pair/claim", Map.of("pairCode", "nope"), none));
        assertEnded(call(HttpMethod.GET, "/api/v1/scan-pair/status", null, none));
        for (String[] e : List.of(
                new String[] { "POST", "/api/v1/station/pairings" },
                new String[] { "GET", "/api/v1/station/pairings/current?deviceId=" + s.device },
                new String[] { "DELETE", "/api/v1/station/pairings/current?deviceId=" + s.device },
                new String[] { "PUT", "/api/v1/station/pairings/current/target?deviceId=" + s.device },
                new String[] { "GET", "/api/v1/station/relay-stream?deviceId=" + s.device },
                new String[] { "POST", "/api/v1/station/relay-events/" + UUID.randomUUID() + "/outcome" })) {
            boolean withBody = e[0].equals("POST") || e[0].equals("PUT");
            ResponseEntity<String> r = call(HttpMethod.valueOf(e[0]), e[1], withBody ? Map.of() : null, none);
            assertThat(r.getStatusCode()).as(e[0] + " " + e[1]).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody()).as("the security entry point, not a controller").isNull();
        }
    }

    // ── app_user: grants suffice, tenants isolated ───────────────────────────

    /** What each relay stream would be sent (app_user tests drive the stream path without HTTP). */
    static final class RecordingHub extends ScanRelayHub {
        final List<Object[]> sent = Collections.synchronizedList(new ArrayList<>());
        @Override public boolean send(Subscriber sub, String name, Object data) {
            sent.add(new Object[] { sub.pairingId, name, data });
            return true;
        }
        List<String> codesSentTo(UUID pairing) {
            return sent.stream().filter(e -> e[0].equals(pairing) && "scan".equals(e[1]))
                .map(e -> ((ScanPairingService.RelayEvent) e[2]).code()).toList();
        }
    }

    private ScanPairingService appUserService(ScanRelayHub hub) {
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        return new ScanPairingService(new JdbcTemplate(ds), new DataSourceTransactionManager(ds), hub, "https://app.tracedtech.com");
    }

    /** As app_user: pair the station's tablet, claim it, send one phone scan; returns {pairingId, eventId}. */
    private UUID[] phoneScanAsAppUser(ScanPairingService svc, Station s, String code) {
        ScanPairingService.PairingCreated created = TenantContext.runAs(s.f.tenant, () -> svc.create(s.device, s.worker));
        String pairCode = created.pairUrl().substring(created.pairUrl().lastIndexOf('/') + 1);
        ScanPairingService.Resolved byCode = svc.resolve("pair_code", pairCode);
        ScanPairingService.Claimed claimed = TenantContext.runAs(s.f.tenant, () -> svc.claim(byCode, "Android · Chrome"));
        ScanPairingService.Resolved bySecret = svc.resolve("device_secret", claimed.deviceSecret());
        UUID event = TenantContext.runAs(s.f.tenant, () -> svc.scan(bySecret, 1L, code));
        return new UUID[] { created.pairingId(), event };
    }

    /**
     * Relay-stream isolation (RlsCoverageTest EXEMPT reason for /station/relay-stream names this
     * test). As app_user: another tenant's tablet id resolves to no pairing (409 NO_PAIRING — RLS
     * hides it, with the caller's own user id or the pairing's worker's), and no event of another
     * tenant's pairing is ever delivered on a stream — not even on a stream forged for the other
     * tenant's pairing id, where RLS alone has to stop it. Same-tenant positive control: each
     * tenant's own event is delivered on its own stream. Over HTTP, another tenant's token on the
     * tablet id is a 409 too.
     */
    @Test
    void relayStream_isolation_asAppUser_otherTenantsPairingRefused_otherTenantsEventsNeverDelivered() throws Exception {
        RecordingHub hub = new RecordingHub();
        ScanPairingService svc = appUserService(hub);
        Station a = station("Stream iso A"), b = station("Stream iso B");
        UUID[] pa = phoneScanAsAppUser(svc, a, "A-PIECE-1");
        UUID[] pb = phoneScanAsAppUser(svc, b, "B-PIECE-1");
        try {
            for (UUID asUser : List.of(b.worker, a.worker)) {
                assertThatThrownBy(() -> TenantContext.runAs(b.f.tenant, () -> svc.requireStreamable(a.device, asUser)))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.errorCode()).isEqualTo("NO_PAIRING");
                    });
            }
            assertThat(call(HttpMethod.GET, "/api/v1/station/relay-stream?deviceId=" + a.device, null, bearer(b.token))
                .getStatusCode()).as("over HTTP").isEqualTo(HttpStatus.CONFLICT);

            // A stream forged for A's pairing id under tenant B: RLS finds none of A's events.
            hub.subscribe(pa[0], b.f.tenant);
            TenantContext.runAs(b.f.tenant, () -> svc.deliver(pa[0]));
            assertThat(hub.sent).as("nothing crosses to tenant B").isEmpty();
            assertThat(jdbc.queryForObject("SELECT status FROM scan_relay_events WHERE id = ?", String.class, pa[1]))
                .as("A's event untouched by tenant B").isEqualTo("pending");

            hub.subscribe(pb[0], b.f.tenant);
            TenantContext.runAs(b.f.tenant, () -> svc.deliver(pb[0]));
            assertThat(hub.codesSentTo(pb[0])).as("same-tenant positive control (B)").containsExactly("B-PIECE-1");

            hub.subscribe(pa[0], a.f.tenant);
            TenantContext.runAs(a.f.tenant, () -> svc.deliver(pa[0]));
            assertThat(hub.codesSentTo(pa[0])).as("same-tenant positive control (A)").containsExactly("A-PIECE-1");

            // The real stream check, own tenant: the pairing's worker gets its pairing id.
            assertThat(TenantContext.runAs(a.f.tenant, () -> svc.requireStreamable(a.device, a.worker))).isEqualTo(pa[0]);
        } finally {
            hub.shutdown();
        }
    }

    @Test
    void wholeFlowAsAppUser_grantsSuffice_tenantIsolated_withSameTenantPositiveControl() {
        ScanRelayHub hub = new ScanRelayHub();
        ScanPairingService svc = appUserService(hub);
        Station a = station("AppUser A"), b = station("AppUser B");

        ScanPairingService.PairingCreated created = TenantContext.runAs(a.f.tenant, () -> svc.create(a.device, a.worker));
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
        TenantContext.runAs(a.f.tenant, () -> svc.outcome(a.worker, event, "accepted", "Cargo pants 2/2"));
        assertThat(TenantContext.runAs(a.f.tenant, () -> svc.eventStatus(bySecret, event)).message()).isEqualTo("Cargo pants 2/2");
        TenantContext.runAs(a.f.tenant, () -> svc.setTarget(a.device, a.worker, "Pick & Pack · #1047"));
        ScanPairingService.PhoneContext ctx = TenantContext.runAs(a.f.tenant, () -> svc.phoneStatus(bySecret));
        assertThat(ctx.workerName()).isEqualTo("Ahmed");
        assertThat(ctx.target()).isEqualTo("Pick & Pack · #1047");
        assertThat(TenantContext.runAs(a.f.tenant, () -> svc.current(a.device, a.worker)).status())
            .as("same-tenant positive control").isEqualTo("connected");

        // Tenant B can't see or touch A's pairing / events.
        assertThat(TenantContext.runAs(b.f.tenant, () -> svc.current(a.device, a.worker)).status()).isEqualTo("none");
        assertThatThrownBy(() -> TenantContext.runAs(b.f.tenant, () -> svc.eventStatus(bySecret, event)))
            .isInstanceOf(ScanPairException.class);
        assertThatThrownBy(() -> TenantContext.runAs(b.f.tenant, () -> svc.outcome(a.worker, event, "rejected", "x")))
            .hasMessageContaining("not found");
        TenantContext.runAs(b.f.tenant, () -> svc.unpair(a.device, a.worker));
        TenantContext.runAs(b.f.tenant, () -> svc.revokeForUser(a.worker, "station_locked"));
        assertThat(svc.resolve("device_secret", claimed.deviceSecret())).as("B can't revoke A's").isNotNull();

        TenantContext.runAs(a.f.tenant, () -> svc.revokeForUser(a.worker, "station_locked"));
        assertThat(svc.resolve("device_secret", claimed.deviceSecret())).isNull();
        hub.shutdown();
    }
}
