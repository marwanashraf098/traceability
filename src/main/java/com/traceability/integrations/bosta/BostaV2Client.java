package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Returns portal Step 4c-2 — the two Bosta v2 READS, kept apart from {@link BostaHttpGateway}
 * so the global {@code bosta.api-version} (v0) and every existing v0 call stay untouched.
 * Both URLs are built here as {base}/api/v2/…; the base is the same {@code BOSTA_BASE_URL}.
 *
 *   GET /api/v2/cities/getAllDistricts — Bosta's reference data, sent WITHOUT an
 *       Authorization header (the spec marks it {@code security: []}). A 401/403 means that is
 *       no longer true: {@link AuthRequiredException}, and the caller must not reach for a
 *       tenant's key.
 *   GET /api/v2/pickup-locations — the tenant's own pickup locations, with the tenant's
 *       decrypted API key as the raw Authorization header (same as the v0 client, no
 *       "Bearer "). 401/403 → {@link KeyRefusedException}.
 *
 * No Resilience4j retry: both are cheap reads a caller can repeat. Explicit timeouts (the v0
 * RestClient has none).
 *
 * Step 4c-3 — the ONE Bosta write this class makes, under MODE B AMENDMENT #2:
 *   POST /api/v2/deliveries?apiVersion=1 — create a type 25 CUSTOMER_RETURN_PICKUP, from an
 *       approved return request, with the tenant's raw key. {@link #createReturnPickup}.
 *       Exactly ONE HTTP attempt: no retry decorator, no internal retry, its own timeouts
 *       (connect 5 s, read 20 s). A duplicate POST would book a second courier, so anything
 *       that might have reached Bosta is reported AMBIGUOUS and never re-sent automatically.
 *       Never logs the payload or the response body (customer PII) — request id, outcome and
 *       HTTP status only.
 */
@Component
public class BostaV2Client {

    /** One district row of getAllDistricts, flattened with its city. Arabic = Bosta's "OtherName". */
    public record District(String districtId, String cityId, String cityName, String cityNameAr,
                           String zoneId, String zoneName, String zoneNameAr,
                           String districtName, String districtNameAr,
                           boolean pickupAvailable, boolean dropoffAvailable) {}

    public record PickupLocation(String id, String name, boolean isDefault, String cityName) {}

    /** getAllDistricts answered 401/403 — it needs auth now. */
    public static class AuthRequiredException extends BostaException {
        public AuthRequiredException(int status) { super("Bosta getAllDistricts answered " + status); }
    }

    /** pickup-locations answered 401/403 for the tenant's key. */
    public static class KeyRefusedException extends BostaException {
        public KeyRefusedException(int status) { super("Bosta refused the API key for pickup-locations (" + status + ")"); }
    }

    /** Everything the type 25 create sends (see {@link #returnPickupPayload}). */
    public record ReturnPickup(String uniqueBusinessReference, String businessReference, String businessLocationId,
                               String firstLine, String secondLine, String buildingNumber, String floor,
                               String apartment, String city, String districtId,
                               String receiverFirstName, String receiverLastName, String receiverPhone,
                               int itemsCount, String description, String returnNotes) {}

    public enum CreateOutcome {
        /** Bosta answered 2xx with a tracking number. */
        CREATED,
        /** Definitely not created (4xx incl. 429, or the connection was never made) — safe to retry. */
        NOT_CREATED,
        /** May or may not have been created (5xx, timeout, reset, unreadable 2xx) — never re-send. */
        AMBIGUOUS
    }

    public record CreateResult(CreateOutcome outcome, String deliveryId, String trackingNumber,
                               int httpStatus, String message) {}

    private static final Logger log = LoggerFactory.getLogger(BostaV2Client.class);

    private final RestClient restClient;
    private final RestClient createClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public BostaV2Client(ObjectMapper mapper, String baseUrl) {
        this(mapper, baseUrl, Duration.ofSeconds(20));
    }

    @Autowired
    public BostaV2Client(ObjectMapper mapper, @Value("${bosta.base-url}") String baseUrl,
                         @Value("${bosta.create-read-timeout:20s}") Duration createReadTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        // The create call only: connect 5 s, read 20 s. Separate client so nothing else shares it.
        SimpleClientHttpRequestFactory createFactory = new SimpleClientHttpRequestFactory();
        createFactory.setConnectTimeout(Duration.ofSeconds(5));
        createFactory.setReadTimeout(createReadTimeout);
        this.createClient = RestClient.builder().requestFactory(createFactory).build();
        this.mapper     = mapper;
        this.baseUrl    = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * POST {base}/api/v2/deliveries?apiVersion=1 — ONE attempt, never retried here.
     * 2xx with data.trackingNumber → CREATED; 429 or other 4xx → NOT_CREATED (Bosta's message);
     * connection refused / unknown host (nothing sent) → NOT_CREATED; 5xx, timeout, reset or an
     * unreadable 2xx → AMBIGUOUS.
     */
    public CreateResult createReturnPickup(String apiKey, ReturnPickup p) {
        String url = baseUrl + "/api/v2/deliveries?apiVersion=1";
        String json = returnPickupPayload(mapper, p).toString();
        CreateResult result;
        try {
            result = createClient.post().uri(url)
                .header("Authorization", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8))
                .exchange((req, resp) -> {
                    int status = resp.getStatusCode().value();
                    byte[] raw = resp.getBody().readAllBytes();
                    return interpret(status, raw);
                });
        } catch (ResourceAccessException e) {
            Throwable c = e.getCause();
            boolean neverSent = c instanceof ConnectException || c instanceof UnknownHostException
                || c instanceof NoRouteToHostException;
            result = neverSent
                ? new CreateResult(CreateOutcome.NOT_CREATED, null, null, 0, "Couldn't connect to Bosta.")
                : new CreateResult(CreateOutcome.AMBIGUOUS, null, null, 0,
                    "No answer from Bosta (timeout or dropped connection).");
        } catch (RuntimeException e) {
            result = new CreateResult(CreateOutcome.AMBIGUOUS, null, null, 0, "Unexpected error talking to Bosta.");
        }
        log.info("Bosta createReturnPickup request={} outcome={} status={}",
            p.uniqueBusinessReference(), result.outcome(), result.httpStatus());
        return result;
    }

    private CreateResult interpret(int status, byte[] raw) {
        JsonNode body = null;
        try { body = raw.length == 0 ? null : mapper.readTree(raw); } catch (Exception ignored) { /* unreadable */ }
        if (status >= 200 && status < 300) {
            JsonNode data = body == null ? null : body.path("data");
            String tracking = data == null ? null : text(data, "trackingNumber");
            if (tracking == null) {
                return new CreateResult(CreateOutcome.AMBIGUOUS, null, null, status,
                    "Bosta answered without a tracking number.");
            }
            return new CreateResult(CreateOutcome.CREATED, text(data, "_id"), tracking, status, null);
        }
        if (status == 429) {
            return new CreateResult(CreateOutcome.NOT_CREATED, null, null, status,
                "Bosta is rate-limiting requests. Try again in a few minutes.");
        }
        if (status >= 400 && status < 500) {
            String msg = body == null ? null : text(body, "message");
            return new CreateResult(CreateOutcome.NOT_CREATED, null, null, status,
                msg != null ? truncate(msg, 300) : "Bosta rejected the request (HTTP " + status + ").");
        }
        return new CreateResult(CreateOutcome.AMBIGUOUS, null, null, status,
            "Bosta answered with a server error (HTTP " + status + ").");
    }

    /**
     * The exact type 25 create body. Customer on dropOffAddress (the spec's contract — Bosta
     * stores it as pickupAddress on a CRP). Never pickupAddress, returnAddress,
     * allowToOpenPackage or webhookUrl.
     */
    public static ObjectNode returnPickupPayload(ObjectMapper mapper, ReturnPickup p) {
        ObjectNode b = mapper.createObjectNode();
        b.put("type", 25);
        b.put("cod", 0);
        ObjectNode drop = b.putObject("dropOffAddress");
        drop.put("firstLine", p.firstLine());
        putIfPresent(drop, "secondLine", p.secondLine());
        putIfPresent(drop, "buildingNumber", p.buildingNumber());
        putIfPresent(drop, "floor", p.floor());
        putIfPresent(drop, "apartment", p.apartment());
        drop.put("city", p.city());
        drop.put("districtId", p.districtId());
        b.put("businessLocationId", p.businessLocationId());
        ObjectNode receiver = b.putObject("receiver");
        receiver.put("firstName", p.receiverFirstName());
        putIfPresent(receiver, "lastName", p.receiverLastName());
        receiver.put("phone", p.receiverPhone());
        b.put("businessReference", p.businessReference());
        b.put("uniqueBusinessReference", p.uniqueBusinessReference());
        ObjectNode details = b.putObject("returnSpecs").putObject("packageDetails");
        details.put("itemsCount", p.itemsCount());
        details.put("description", p.description());
        b.put("returnNotes", p.returnNotes());
        return b;
    }

    private static void putIfPresent(ObjectNode n, String field, String value) {
        if (value != null && !value.isBlank()) n.put(field, value);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public List<District> fetchAllDistricts() {
        String url = baseUrl + "/api/v2/cities/getAllDistricts";
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return parseAllDistricts(body == null ? null : mapper.readTree(body));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) throw new AuthRequiredException(status);
            if (e.getStatusCode().is5xxServerError()) throw new BostaTransientException("Bosta 5xx on getAllDistricts", e);
            throw new BostaException("Bosta getAllDistricts error (" + status + ")", e);
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error on getAllDistricts", e);
        } catch (BostaException e) {
            throw e;
        } catch (Exception e) {
            throw new BostaException("Unreadable getAllDistricts response", e);
        }
    }

    public List<PickupLocation> listPickupLocations(String apiKey) {
        String url = baseUrl + "/api/v2/pickup-locations";
        try {
            String body = restClient.get().uri(url).header("Authorization", apiKey).retrieve().body(String.class);
            return parsePickupLocations(body == null ? null : mapper.readTree(body));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) throw new KeyRefusedException(status);
            if (e.getStatusCode().is5xxServerError()) throw new BostaTransientException("Bosta 5xx on pickup-locations", e);
            throw new BostaException("Bosta pickup-locations error (" + status + ")", e);
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error on pickup-locations", e);
        } catch (BostaException e) {
            throw e;
        } catch (Exception e) {
            throw new BostaException("Unreadable pickup-locations response", e);
        }
    }

    /**
     * {success, data: [ {cityId, cityName, cityOtherName, pickupAvailability, districts: [
     * {zoneId, zoneName, zoneOtherName, districtId, districtName, districtOtherName,
     * pickupAvailability, dropOffAvailability} ]} ]}. {@code data.list} is accepted too.
     * A district is pickup-available only when both it and its city say so (a missing city
     * flag counts as true). Rows without a districtId or cityId are skipped.
     */
    static List<District> parseAllDistricts(JsonNode body) {
        List<District> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode cities = body.path("data");
        if (cities.isObject()) cities = cities.path("list");
        if (!cities.isArray()) return out;
        for (JsonNode city : cities) {
            String cityId = text(city, "cityId");
            String cityName = text(city, "cityName");
            if (cityId == null || cityName == null) continue;
            boolean cityPickup = city.path("pickupAvailability").asBoolean(true);
            boolean cityDropoff = city.path("dropOffAvailability").asBoolean(true);
            for (JsonNode d : city.path("districts")) {
                String districtId = text(d, "districtId");
                String districtName = text(d, "districtName");
                if (districtId == null || districtName == null) continue;
                out.add(new District(districtId, cityId, cityName, text(city, "cityOtherName"),
                    text(d, "zoneId"), text(d, "zoneName"), text(d, "zoneOtherName"),
                    districtName, text(d, "districtOtherName"),
                    cityPickup && d.path("pickupAvailability").asBoolean(false),
                    cityDropoff && d.path("dropOffAvailability").asBoolean(false)));
            }
        }
        return out;
    }

    /** {success, data: {list: [ {_id, locationName, isDefault?, address: {city: {name}}} ], ...}}. */
    static List<PickupLocation> parsePickupLocations(JsonNode body) {
        List<PickupLocation> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode list = body.path("data");
        if (list.isObject()) list = list.path("list");
        if (!list.isArray()) return out;
        for (JsonNode l : list) {
            String id = text(l, "_id");
            if (id == null) continue;
            String name = text(l, "locationName");
            out.add(new PickupLocation(id, name != null ? name : id, l.path("isDefault").asBoolean(false),
                text(l.path("address").path("city"), "name")));
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
