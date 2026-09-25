package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
 * No Bosta writes here. No Resilience4j retry: both are cheap reads a caller can repeat.
 * Explicit timeouts (the v0 RestClient has none).
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

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public BostaV2Client(ObjectMapper mapper, @Value("${bosta.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.mapper     = mapper;
        this.baseUrl    = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
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
