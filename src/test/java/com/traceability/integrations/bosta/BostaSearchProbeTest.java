package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only v2 delivery-search probe (2026-10-03).
 *
 *   sp1 the exact dashboard request (POST /api/v2/deliveries/search, raw key as Authorization,
 *       {"stateCodes":[],"limit":50,"page":N,"sortBy":…}) and the logged fields: status, items,
 *       first / last tracking and creation times, newest-created-first, page-2 overlap, keys
 *   sp2 the fallback sorts are tried only when "-createdAt" fails
 *   sp3 every call goes through the shared rate limiter: after a 429 the key is blocked and nothing
 *       more is sent
 *   sp4 read-only: nothing but the search endpoint is ever called
 */
class BostaSearchProbeTest {

    private static final ObjectMapper M = new ObjectMapper();
    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();   // "path auth body"
    private final Map<String, Integer> statusForSort = new HashMap<>();
    private BostaV2Client client;
    private BostaSearchProbe probe;
    private final UUID tenant = UUID.fromString("d6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                + ex.getRequestHeaders().getFirst("Authorization") + " " + body);
            JsonNode req = M.readTree(body.isEmpty() ? "{}" : body);
            int status = statusForSort.getOrDefault(req.path("sortBy").asText(), 200);
            byte[] out = (status == 200 ? page(req.path("page").asInt(), req.path("limit").asInt()).toString()
                : "{\"success\":false,\"message\":\"bad sort\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        client = new BostaV2Client(M, "http://127.0.0.1:" + server.getAddress().getPort());
        probe = new BostaSearchProbe(client, M);
    }

    @AfterEach
    void stop() { server.stop(0); }

    /** 120 deliveries, newest created first; page/limit honoured, count always 0 (like Bosta). */
    private ObjectNode page(int page, int limit) {
        ObjectNode root = M.createObjectNode();
        root.put("success", true);
        ObjectNode data = root.putObject("data");
        ArrayNode ds = data.putArray("deliveries");
        long newest = 1791037009635L;
        for (int i = (page - 1) * limit; i < Math.min(120, page * limit); i++) {
            ObjectNode d = ds.addObject();
            d.put("trackingNumber", String.valueOf(1177840993L + i));
            d.put("creationTimestamp", newest - i * 60_000L);
            d.put("createdAt", "Sat Oct 03 2026 14:16:49 GMT+0000 (Coordinated Universal Time)");
            d.put("updatedAt", "2026-10-03T14:20:00.000Z");
            d.putObject("shopifyInfo").put("orderId", "1891" + i);
            d.put("businessReference", "BRK-449" + i + "-EG");
        }
        data.put("count", 0);
        data.put("page", page);
        data.put("limit", limit);
        return root;
    }

    @Test
    void sp1_dashboardRequest_andLoggedFields() throws Exception {
        List<Map<String, Object>> out = probe.probe(tenant, "BROEK", "broek-key");

        assertThat(out).hasSize(4);   // (a) and (b), pages 1 and 2; (c) not needed
        String first = calls.get(0);
        assertThat(first).startsWith("POST /api/v2/deliveries/search broek-key ");
        JsonNode body = M.readTree(first.substring(first.indexOf('{')));
        assertThat(body.path("stateCodes").isArray()).isTrue();
        assertThat(body.path("limit").asInt()).isEqualTo(50);
        assertThat(body.path("page").asInt()).isEqualTo(1);
        assertThat(body.path("sortBy").asText()).isEqualTo("-createdAt");

        Map<String, Object> p1 = out.get(0), p2 = out.get(1);
        assertThat(p1).containsEntry("status", 200).containsEntry("items", 50)
            .containsEntry("firstTracking", "1177840993").containsEntry("lastTracking", "1177841042")
            .containsEntry("newestCreatedFirst", true).containsEntry("count", "0");
        assertThat(p1.get("responseKeys")).isEqualTo(List.of("success", "data"));
        assertThat(p1.get("dataKeys")).isEqualTo(List.of("deliveries", "count", "page", "limit"));
        assertThat(String.valueOf(p1.get("itemKeys"))).contains("shopifyInfo", "businessReference", "creationTimestamp");
        assertThat(p2).containsEntry("page", 2).containsEntry("items", 50).containsEntry("overlapWithPage1", 0)
            .containsEntry("firstTracking", "1177841043");
        assertThat(out.get(2)).containsEntry("sortBy", "-updatedAt");
    }

    @Test
    void sp2_fallbackSorts_onlyWhenCreatedAtFails() {
        statusForSort.put("-createdAt", 400);

        List<Map<String, Object>> out = probe.probe(tenant, "BROEK", "broek-key");

        assertThat(out).extracting(m -> m.get("sortBy"))
            .containsExactly("-createdAt", "-createdAt", "-updatedAt", "-updatedAt",
                "createdAt:-1", "createdAt:-1", "-creationTimestamp", "-creationTimestamp");
        assertThat(out.get(0)).containsEntry("status", 400).containsEntry("error", "HTTP 400");
    }

    @Test
    void sp3_throughTheRateLimiter_429BlocksTheKey() {
        client.setRateLimiter(new BostaRateLimiter(100, 5, 100));   // max wait 100 ms
        statusForSort.put("-createdAt", 429);

        List<Map<String, Object>> out = probe.probe(tenant, "BROEK", "broek-key");

        assertThat(calls).as("one request, then the key is blocked for retry-after").hasSize(1);
        assertThat(out.get(1)).containsEntry("status", 0);
        assertThat((String) out.get(1).get("error")).startsWith("rate limited before sending");
    }

    @Test
    void sp4_readOnly_onlyTheSearchEndpoint() {
        probe.probe(tenant, "BROEK", "broek-key");
        assertThat(calls).allMatch(c -> c.startsWith("POST /api/v2/deliveries/search "));
    }
}
