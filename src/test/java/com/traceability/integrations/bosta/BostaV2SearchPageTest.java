package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BostaV2Client.searchDeliveriesPage — discovery's page read on the v2 search (2026-10-03), against
 * the contract the prod probe proved.
 *
 *   sq1 the dashboard request (POST /api/v2/deliveries/search, raw key as Authorization,
 *       {"stateCodes":[],"limit":50,"page":N,"sortBy":"-createdAt"}); data.deliveries returned in order
 *   sq2 a 429 → BostaRateLimitException with Bosta's retry-after, and the key is blocked in the shared
 *       limiter (nothing more is sent)
 *   sq3 5xx and a 2xx without data.deliveries → BostaTransientException; another 4xx → BostaException
 *       (not transient)
 */
class BostaV2SearchPageTest {

    private static final ObjectMapper M = new ObjectMapper();
    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"success\":true,\"data\":{\"deliveries\":[" +
        "{\"trackingNumber\":\"1177840993\"},{\"trackingNumber\":1177840994}],\"count\":0,\"page\":2,\"limit\":50}}";
    private volatile String retryAfter;
    private BostaV2Client client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                + ex.getRequestHeaders().getFirst("Authorization") + " "
                + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfter != null) ex.getResponseHeaders().add("Retry-After", retryAfter);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        client = new BostaV2Client(M, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void sq1_dashboardRequest_itemsInOrder() throws Exception {
        List<JsonNode> items = client.searchDeliveriesPage("broek-key", 2, 50, "-createdAt");

        assertThat(items).extracting(i -> i.path("trackingNumber").asText()).containsExactly("1177840993", "1177840994");
        assertThat(calls).hasSize(1);
        String call = calls.get(0);
        assertThat(call).startsWith("POST /api/v2/deliveries/search broek-key ");
        JsonNode req = M.readTree(call.substring(call.indexOf('{')));
        assertThat(req.path("stateCodes").isArray()).isTrue();
        assertThat(req.path("stateCodes").size()).isZero();
        assertThat(req.path("limit").asInt()).isEqualTo(50);
        assertThat(req.path("page").asInt()).isEqualTo(2);
        assertThat(req.path("sortBy").asText()).isEqualTo("-createdAt");
    }

    @Test
    void sq2_429_rateLimitException_andTheKeyIsBlocked() {
        client.setRateLimiter(new BostaRateLimiter(100, 5, 100));   // max wait 100 ms
        status = 429;
        retryAfter = "30";
        body = "{\"success\":false,\"errorCode\":429}";

        assertThatThrownBy(() -> client.searchDeliveriesPage("femine-key", 1, 50, "-createdAt"))
            .isInstanceOfSatisfying(BostaRateLimitException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(30));
        assertThatThrownBy(() -> client.searchDeliveriesPage("femine-key", 2, 50, "-createdAt"))
            .isInstanceOf(BostaRateLimitException.class);
        assertThat(calls).as("blocked for retry-after: the second page is never sent").hasSize(1);
    }

    @Test
    void sq3_failures_mappedForTheWalk() {
        status = 502;
        assertThatThrownBy(() -> client.searchDeliveriesPage("k", 1, 50, "-createdAt"))
            .isInstanceOf(BostaTransientException.class);

        status = 200;
        body = "{\"success\":true,\"data\":{}}";
        assertThatThrownBy(() -> client.searchDeliveriesPage("k", 1, 50, "-createdAt"))
            .isInstanceOf(BostaTransientException.class);

        status = 401;
        body = "{\"success\":false,\"message\":\"Unauthorized\"}";
        assertThatThrownBy(() -> client.searchDeliveriesPage("k", 1, 50, "-createdAt"))
            .isInstanceOf(BostaException.class)
            .isNotInstanceOf(BostaTransientException.class)
            .isNotInstanceOf(BostaRateLimitException.class);
    }
}
