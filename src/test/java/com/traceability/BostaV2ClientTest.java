package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.traceability.integrations.bosta.BostaV2Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns portal Step 4c-2 — BostaV2Client against a real local HTTP server (no Spring), so the
 * request Bosta would receive — path and Authorization header — is observed, not assumed.
 * Fixtures follow the vendor spec's documented shapes (docs/vendor/bosta-api.yaml), including
 * Arabic names and pickupAvailability=false at both district and city level.
 */
class BostaV2ClientTest {

    private HttpServer server;
    private final Map<String, String> authSeen = new ConcurrentHashMap<>();
    private final Map<String, Integer> statusFor = new ConcurrentHashMap<>();
    private final Map<String, String> bodyFor = new ConcurrentHashMap<>();
    private BostaV2Client client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            authSeen.put(path, auth == null ? "<none>" : auth);
            byte[] out = bodyFor.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(statusFor.getOrDefault(path, 200), out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        client = new BostaV2Client(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void getAllDistricts_noAuthorizationHeader_parsesArabicAndAvailability() throws Exception {
        bodyFor.put("/api/v2/cities/getAllDistricts", fixture("get-all-districts.json"));

        List<BostaV2Client.District> ds = client.fetchAllDistricts();

        assertThat(authSeen.get("/api/v2/cities/getAllDistricts")).as("sent with NO Authorization header").isEqualTo("<none>");
        assertThat(ds).hasSize(4);
        BostaV2Client.District d10 = find(ds, "wY_JL43TilR");
        assertThat(d10.cityId()).isEqualTo("FceDyHXwpSYYF9zGW");
        assertThat(d10.cityName()).isEqualTo("Cairo");
        assertThat(d10.cityNameAr()).isEqualTo("القاهرة");
        assertThat(d10.zoneName()).isEqualTo("New Cairo");
        assertThat(d10.zoneNameAr()).isEqualTo("القاهره الجديده");
        assertThat(d10.districtName()).isEqualTo("1st Settlement - District 10");
        assertThat(d10.districtNameAr()).isEqualTo("التجمع الاول - الحي 10");
        assertThat(d10.pickupAvailable()).isTrue();
        assertThat(find(ds, "_jLhFsfVsLu").pickupAvailable()).as("district says false").isFalse();
        assertThat(find(ds, "_jLhFsfVsLu").dropoffAvailable()).isTrue();
        assertThat(find(ds, "zoJP71_5Ca1").pickupAvailable()).as("its city says false").isFalse();
    }

    @Test
    void getAllDistricts_401_isAuthRequired() {
        statusFor.put("/api/v2/cities/getAllDistricts", 401);
        bodyFor.put("/api/v2/cities/getAllDistricts", "{\"success\":false,\"message\":\"User is not authorized!\",\"errorCode\":1007}");
        assertThatThrownBy(() -> client.fetchAllDistricts()).isInstanceOf(BostaV2Client.AuthRequiredException.class);
    }

    @Test
    void pickupLocations_rawKeyHeader_parsesIdNameDefaultCity() throws Exception {
        bodyFor.put("/api/v2/pickup-locations", fixture("pickup-locations.json"));

        List<BostaV2Client.PickupLocation> ls = client.listPickupLocations("raw-api-key-123");

        assertThat(authSeen.get("/api/v2/pickup-locations")).as("raw key, no Bearer prefix").isEqualTo("raw-api-key-123");
        assertThat(ls).containsExactly(
            new BostaV2Client.PickupLocation("yfWPU0tP2", "Maadi Warehouse", true, "Cairo"),
            new BostaV2Client.PickupLocation("5f0a7def4a839b00139d6203", "Original Business Location", false, null));
    }

    @Test
    void pickupLocations_401_isKeyRefused() {
        statusFor.put("/api/v2/pickup-locations", 401);
        assertThatThrownBy(() -> client.listPickupLocations("bad-key")).isInstanceOf(BostaV2Client.KeyRefusedException.class);
    }

    private static BostaV2Client.District find(List<BostaV2Client.District> ds, String id) {
        return ds.stream().filter(d -> d.districtId().equals(id)).findFirst().orElseThrow();
    }

    static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/bosta/" + name), StandardCharsets.UTF_8);
    }
}
